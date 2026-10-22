package com.oska.loudspeak.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃/错误报告（调试用，完全离线）：
 * - 全局未捕获异常 → 落盘 error_reports/crash-*.txt（设备信息 + 内存 + 堆栈）
 * - 运行期错误（ASR 加载失败、相机绑定失败等）→ report() 同样落盘
 * - 保留最近 20 份；「设置 → 错误报告」里查看/清空
 */
object CrashReporter {

    private var dir: File? = null
    private var appCtx: Context? = null
    private val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        dir = File(ctx.getExternalFilesDir(null), "error_reports").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write("crash", "${t.name} 未捕获异常", e)
            previous?.uncaughtException(t, e)
        }
    }

    /** 运行期错误上报（不崩溃，仅留档供调试） */
    fun report(tag: String, message: String, err: Throwable? = null) = write(tag, message, err)

    private fun write(kind: String, message: String, err: Throwable?) {
        val d = dir ?: return
        try {
            val sb = StringBuilder()
            sb.append("kind: $kind\n")
            sb.append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            sb.append("message: $message\n")
            sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            sb.append("memory: ").append(memSnapshot()).append('\n')
            if (err != null) {
                sb.append("\nstack:\n").append(android.util.Log.getStackTraceString(err)).append('\n')
            }
            File(d, "$kind-${fmt.format(Date())}.txt").writeText(sb.toString())
            val files = d.listFiles()?.sortedBy { it.name } ?: emptyList()
            if (files.size > 20) files.take(files.size - 20).forEach { it.delete() }
        } catch (_: Exception) {
            // 收集错误的代码绝不能再抛
        }
    }

    private fun memSnapshot(): String = try {
        val am = appCtx?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return "n/a"
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val pid = android.os.Process.myPid()
        val pmi = am.getProcessMemoryInfo(intArrayOf(pid))[0]
        "total=%.2fGB avail=%.2fGB pss=%.1fMB".format(
            mi.totalMem / 1e9, mi.availMem / 1e9, pmi.totalPss / 1024f)
    } catch (_: Exception) { "n/a" }

    fun list(): List<File> = dir?.listFiles()?.filter { it.isFile }?.sortedByDescending { it.name } ?: emptyList()

    fun read(f: File): String = try { f.readText() } catch (e: Exception) { "读取失败：${e.message}" }

    fun clear() { dir?.listFiles()?.forEach { it.delete() } }
}
