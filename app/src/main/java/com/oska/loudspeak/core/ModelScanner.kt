package com.oska.loudspeak.core

import android.content.Context
import java.io.File

/**
 * 模型扫描（设置里用）：扫常见位置找 model.int8.onnx + 同目录 tokens.txt，
 * 列出候选给用户选；选中后由调用方拷入 App 私有模型目录再加载。
 * 扫描是深受限的（深度 ≤4、最多 500 个目录），不会卡 UI。
 */
object ModelScanner {

    class Found(val model: File, val tokens: File) {
        fun label(): String {
            val sizeMb = model.length() / 1_000_000
            val ok = sizeMb in 50..600
            return "${model.parent} （${sizeMb}MB${if (ok) "" else " ⚠体积异常"}）"
        }
    }

    fun scan(ctx: Context): List<Found> {
        val roots = mutableListOf<File>()
        ctx.getExternalFilesDir(null)?.let { roots.add(it) }        // app 私有外部（USB/MTP 可放）
        roots.add(ctx.filesDir)                                      // app 私有内部
        roots.add(File("/storage/emulated/0/Download/SpeakClear"))   // 设计默认位置（best-effort）
        roots.add(File("/storage/emulated/0/SpeakClear"))
        roots.add(File("/storage/emulated/0/Download"))              // 权限允许时才扫得到

        val found = ArrayList<Found>()
        val seen = HashSet<String>()
        for (root in roots) {
            if (!root.isDirectory) continue
            walk(root, depth = 0, counter = intArrayOf(0)) { dir ->
                val m = File(dir, "model.int8.onnx")
                val t = File(dir, "tokens.txt")
                if (m.isFile && t.isFile && seen.add(m.absolutePath)) {
                    found.add(Found(m, t))
                }
            }
        }
        return found
    }

    private fun walk(dir: File, depth: Int, counter: intArrayOf, hit: (File) -> Unit) {
        if (depth > 4 || counter[0] > 500) return
        counter[0]++
        hit(dir)
        val children = try { dir.listFiles() ?: return } catch (_: Exception) { return }
        for (c in children) if (c.isDirectory) walk(c, depth + 1, counter, hit)
    }

    /** 把选中的模型拷入 App 私有活动目录（ONNX 需要可直接读的文件路径） */
    fun install(model: File, tokens: File, targetDir: String): Boolean = try {
        val dst = File(targetDir, "asr").apply { mkdirs() }
        model.copyTo(File(dst, "model.int8.onnx"), overwrite = true)
        tokens.copyTo(File(dst, "tokens.txt"), overwrite = true)
        true
    } catch (_: Exception) { false }
}
