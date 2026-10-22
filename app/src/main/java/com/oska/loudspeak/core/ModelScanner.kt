package com.oska.loudspeak.core

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * 模型扫描 / 安装（§5.4）：把「用户从哪儿下载都行」的模型收进 App 私有目录。
 *
 * 两类模型，一套扫描：
 *  - ASR（SenseVoice）：同目录有 `tokens.txt` + 任意 `.onnx` → 一个候选
 *  - LLM（Qwen）：任意 `.gguf` → 一个候选
 *
 * 早先版本只认死文件名 `model.int8.onnx` 且只扫一层，所以「明明放着却扫不到」。
 * 现在按扩展名 + 关键字打分挑最像的那个，递归 6 层、上限 3000 个目录。
 *
 * 注意：Android 13+ 无法直接读 /sdcard/Download（分区存储），那边是 best-effort，
 * 可靠通道是 ① /sdcard/Android/data/com.oska.loudspeak/files/（数据线/MTP 可放）
 * ② 设置页的 SAF「选择目录」（授权后一定能读）。
 */
object ModelScanner {

    enum class Kind { ASR, LLM }

    class Found(val kind: Kind, val model: File, val tokens: File?, val where: String) {
        val sizeMb: Long get() = model.length() / 1_000_000
        fun label(): String {
            val kindName = if (kind == Kind.ASR) "语音识别" else "Qwen"
            val tok = if (kind == Kind.ASR) {
                if (tokens?.isFile == true) " + tokens.txt" else " ⚠缺 tokens.txt"
            } else ""
            return "$kindName：$where/${model.name}（${sizeMb}MB$tok）"
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
                // --- LLM：任意 .gguf ---
                val ggufs = try {
                    dir.listFiles { f: File -> f.isFile && f.name.lowercase(Locale.US).endsWith(".gguf") }
                } catch (_: Exception) { null }
                ggufs?.forEach { g ->
                    if (seen.add(g.absolutePath)) found.add(Found(Kind.LLM, g, null, where(dir)))
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
            p.contains("/files/models/llm") -> "已安装"
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

    /** LLM → <models>/llm/<原文件名>（Qwen3-0.6B-Q4_K_M.gguf 之类） */
    fun installLlm(gguf: File, modelsDir: String): Boolean = try {
        val dstDir = File(modelsDir, "llm").apply { mkdirs() }
        val dst = File(dstDir, gguf.name)
        if (!same(gguf, dst)) gguf.copyTo(dst, overwrite = true)
        dst.isFile
    } catch (_: Exception) { false }

    fun install(f: Found, modelsDir: String): Boolean = when (f.kind) {
        Kind.ASR -> installAsr(f.model, f.tokens, modelsDir)
        Kind.LLM -> installLlm(f.model, modelsDir)
    }

    private fun same(a: File, b: File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: Exception) { false }
}
