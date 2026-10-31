package com.oska.loudspeak.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.util.Locale

/**
 * 模型扫描 / 安装（§5.4）：把「用户从哪儿下载都行」的模型收进 App 私有目录。
 *
 * 只管一类模型：ASR（SenseVoice）—— 同目录有 `tokens.txt` + 任意 `.onnx` → 一个候选。
 * （2026-10-24：原先还会把 `.gguf` 当 LLM 候选扫进来，随「手机本地 Qwen」整条路线废弃而移除。）
 *
 * 早先版本只认死文件名 `model.int8.onnx` 且只扫一层，所以「明明放着却扫不到」。
 * 现在按扩展名 + 关键字打分挑最像的那个，递归 6 层、上限 3000 个目录。
 *
 * 注意：Android 13+ 无法直接读 /sdcard/Download（分区存储），那边是 best-effort，
 * 可靠通道是 ① /sdcard/Android/data/com.oska.loudspeak/files/（数据线/MTP 可放）
 * ② 设置页的 SAF「选择目录」（授权后一定能读）。
 */
object ModelScanner {

    enum class Kind { ASR }

    class Found(val kind: Kind, val model: File, val tokens: File?, val where: String) {
        val sizeMb: Long get() = model.length() / 1_000_000
        fun label(): String {
            val tok = if (tokens?.isFile == true) " + tokens.txt" else " ⚠缺 tokens.txt"
            return "语音识别：$where/${model.name}（${sizeMb}MB$tok）"
        }
    }

    private fun roots(ctx: Context): List<File> {
        val out = ArrayList<File>()
        ctx.getExternalFilesDir(null)?.let { out.add(it) }   // 数据线/MTP 可放，无需权限
        out.add(ctx.filesDir)                                // 内部私有
        // 下面几处 Android 13+ 大多读不到，能读到算赚（isDirectory/canRead 会挡住）
        out.add(File("/storage/emulated/0/Download"))
        out.add(File("/storage/emulated/0/Download/SpeakClear"))
        out.add(File("/storage/emulated/0/SpeakClear"))
        out.add(File("/storage/emulated/0/Documents"))
        return out
    }

    fun scan(ctx: Context): List<Found> {
        val found = ArrayList<Found>()
        val seen = HashSet<String>()
        for (root in roots(ctx)) {
            val real = try { root.canonicalFile } catch (_: Exception) { root }
            if (!real.isDirectory || !real.canRead()) continue
            walk(real, 0, intArrayOf(0)) { dir ->
                // --- ASR：tokens.txt 与同目录的 .onnx ---
                val tokens = File(dir, "tokens.txt")
                if (tokens.isFile) {
                    val onnx = try {
                        dir.listFiles { f: File -> f.isFile && f.name.lowercase(Locale.US).endsWith(".onnx") }
                            ?.maxByOrNull { score(it.name) }
                    } catch (_: Exception) { null }
                    if (onnx != null && seen.add(onnx.absolutePath)) {
                        found.add(Found(Kind.ASR, onnx, tokens, where(dir)))
                    }
                }
            }
        }
        return found
    }

    /** 名字越像 SenseVoice 主模型分越高 */
    private fun score(name: String): Int {
        val n = name.lowercase(Locale.US)
        var s = 0
        if (n.contains("sense")) s += 6
        if (n.contains("asr")) s += 4
        if (n.contains("model")) s += 3
        if (n.contains("int8")) s += 2
        if (n.startsWith("model")) s += 2
        return s
    }

    private fun where(dir: File): String = try {
        val p = dir.canonicalPath.replace('\\', '/')
        when {
            p.contains("/files/models/asr") -> "已安装"
            p.endsWith("/files") -> "App 目录"
            else -> dir.name.ifBlank { p.substringAfterLast('/') }
        }
    } catch (_: Exception) { dir.name }

    private fun walk(dir: File, depth: Int, counter: IntArray, hit: (File) -> Unit) {
        if (depth > 6 || counter[0] > 3000) return
        counter[0]++
        hit(dir)
        val children = try { dir.listFiles() ?: return } catch (_: Exception) { return }
        for (c in children) {
            // 别往缓存和别的 App 的 obb 里钻
            if (!c.isDirectory) continue
            if (c.name == "cache" || c.name == "code_cache" || c.name == "obb") continue
            walk(c, depth + 1, counter, hit)
        }
    }

    // ---------------- 安装（拷进 app 私有活动目录） ----------------

    /** ASR → <models>/asr/model.int8.onnx + tokens.txt（AsrEngine 只认这两个名字） */
    fun installAsr(model: File, tokens: File?, modelsDir: String): Boolean = try {
        val dstDir = File(modelsDir, "asr").apply { mkdirs() }
        val dstModel = File(dstDir, "model.int8.onnx")
        val dstTokens = File(dstDir, "tokens.txt")
        if (!same(model, dstModel)) model.copyTo(dstModel, overwrite = true)
        if (tokens != null && !same(tokens, dstTokens)) tokens.copyTo(dstTokens, overwrite = true)
        dstModel.isFile && dstTokens.isFile
    } catch (_: Exception) { false }

    fun install(f: Found, modelsDir: String): Boolean =
        installAsr(f.model, f.tokens, modelsDir)

    private fun same(a: File, b: File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: Exception) { false }

    // ---------------- SAF 多选文件导入（首次引导 / 主界面 / 设置页共用） ----------------

    /**
     * 从系统文件选择器选中的若干文件里挑出模型装上：
     *  - `tokens.txt` → <models>/asr/tokens.txt
     *  - 任意 `.onnx` → <models>/asr/model.int8.onnx（多个时按名字挑最像 SenseVoice 主模型的）
     *
     * 返回一句能直接显示给用户的结果说明（不抛异常）。
     */
    fun importUris(ctx: Context, uris: List<Uri>, modelsDir: String): String {
        if (uris.isEmpty()) return "没有选到文件"
        var onnxBest: Pair<String, Uri>? = null
        var onnxBestScore = -1
        var tokensUri: Uri? = null
        for (u in uris) {
            val name = queryName(ctx, u) ?: continue
            val lower = name.lowercase(Locale.US)
            when {
                lower == "tokens.txt" -> tokensUri = u
                lower.endsWith(".onnx") -> {
                    val sc = score(name)
                    if (sc > onnxBestScore) { onnxBestScore = sc; onnxBest = name to u }
                }
            }
        }

        var asrOk = false
        if (onnxBest != null && tokensUri != null) {
            val dstDir = File(modelsDir, "asr").apply { mkdirs() }
            val okModel = copyUri(ctx, onnxBest.second, File(dstDir, "model.int8.onnx"))
            val okTokens = copyUri(ctx, tokensUri, File(dstDir, "tokens.txt"))
            asrOk = okModel && okTokens
        }

        return buildString {
            append("ASR ")
            append(
                when {
                    asrOk -> "✓"
                    onnxBest == null && tokensUri == null -> "未选到"
                    onnxBest == null -> "✗ 缺 .onnx 模型文件"
                    tokensUri == null -> "✗ 缺 tokens.txt 词表"
                    else -> "✗ 拷贝失败"
                }
            )
        }
    }

    fun queryName(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: uri.lastPathSegment
    } catch (_: Exception) { uri.lastPathSegment }

    // ---------------- SAF 目录树导入（首次引导 / 设置页共用） ----------------

    /**
     * 把所选目录（含子目录，深度 ≤ 6）里的 ASR 文件装上对应位置：
     *   - `tokens.txt` → <models>/asr/tokens.txt
     *   - 任意 `.onnx` → <models>/asr/model.int8.onnx（多个时挑最像 SenseVoice 主模型的）
     * 返回一句能直接显示给用户的结果说明（不抛异常）。
     *
     * 早先只在设置页有这入口，首次引导「选整个文件夹」被埋在二级页里、用户以为没生效。
     * 现在抽到这里，首次引导也能直接调，且统一了「命名固定为 model.int8.onnx」的落地逻辑。
     */
    fun importTree(cr: ContentResolver, treeUri: Uri, modelsDir: String): String {
        return try {
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val onnx = ArrayList<Pair<String, Uri>>()
            val tokens = ArrayList<Pair<String, Uri>>()
            walkTree(cr, treeUri, rootId, onnx, tokens, 0)
            if (onnx.isEmpty() || tokens.isEmpty()) {
                return "未找到模型：需要同时有 tokens.txt 和一个 .onnx 文件（在所选文件夹或其子目录里）"
            }
            val destAsr = File(modelsDir, "asr").apply { mkdirs() }
            val best = onnx.maxByOrNull { scoreName(it.first) }!!
            val okModel = copyFrom(cr, best.second, File(destAsr, "model.int8.onnx"))
            val okTokens = copyFrom(cr, tokens.first().second, File(destAsr, "tokens.txt"))
            when {
                okModel && okTokens -> "ASR 模型已导入 ✓"
                !okModel && !okTokens -> "ASR 导入失败：两个文件都没写进去"
                !okModel -> "ASR 导入失败：model.int8.onnx 写入失败"
                else -> "ASR 导入失败：tokens.txt 写入失败"
            }
        } catch (e: Exception) {
            "导入失败：${e.message?.take(120) ?: e.javaClass.simpleName}"
        }
    }

    private fun walkTree(
        cr: ContentResolver, treeUri: Uri, dirId: String,
        onnx: MutableList<Pair<String, Uri>>, tokens: MutableList<Pair<String, Uri>>, depth: Int
    ) {
        if (depth > 6) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        cr.query(children, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(cr, treeUri, id, onnx, tokens, depth + 1); continue
                }
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                when {
                    name.equals("tokens.txt", true) -> tokens.add(name to uri)
                    name.endsWith(".onnx", true) -> onnx.add(name to uri)
                }
            }
        }
    }

    private fun scoreName(n: String): Int {
        val s = n.lowercase(Locale.US)
        var v = 0
        if (s.contains("sense")) v += 6
        if (s.contains("model")) v += 3
        if (s.contains("int8")) v += 2
        return v
    }

    private fun copyFrom(cr: ContentResolver, uri: Uri, dst: File): Boolean = try {
        cr.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
        } != null && dst.isFile && dst.length() > 0
    } catch (_: Exception) { false }

    /** 内容 Uri → 目标文件（1MB 缓冲，10 分钟视频量级也不占内存） */
    fun copyUri(ctx: Context, uri: Uri, dst: File): Boolean = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            dst.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
        } != null && dst.isFile && dst.length() > 0
    } catch (_: Exception) { false }
}
