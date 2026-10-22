package com.oska.loudspeak.core

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager

/**
 * 全局配置（SharedPreferences 落地）。
 * 「第 N 次会话」计数也在这里：③ ④ 档不落盘，但同样计入会话次数（§12.0）。
 */
object Prefs {
    private const val FILE = "loudspeak"

    // 四档模式（§6.1.1）：开始前选定，会中不可切
    const val MODE_FULL = "full"                // ① 完整录制
    const val MODE_AUDIO = "audio"              // ② 仅音频
    const val MODE_DISPLAY_VIDEO = "display_video" // ③ 纯显示·带画面（默认）
    const val MODE_DISPLAY_AUDIO = "display_audio" // ④ 纯显示·音频

    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): String = sp(ctx).getString("mode", MODE_DISPLAY_VIDEO)!!
    fun setMode(ctx: Context, m: String) = sp(ctx).edit().putString("mode", m).apply()

    fun dbThreshold(ctx: Context): Int = sp(ctx).getInt("dbThreshold", 30)
    fun setDbThreshold(ctx: Context, v: Int) = sp(ctx).edit().putInt("dbThreshold", v.coerceIn(10, 50)).apply()

    /** 静音断句时长 ms，可调 400–1200（§14.2 B2），默认 800 */
    fun vadSilenceMs(ctx: Context): Int = sp(ctx).getInt("vadSilenceMs", 800)
    fun setVadSilenceMs(ctx: Context, v: Int) = sp(ctx).edit().putInt("vadSilenceMs", v.coerceIn(400, 1200)).apply()

    /** 红晕呈现：0 泛红提醒（默认）/ 1 仅计数不泛红 / 2 完全静默（§7.3） */
    fun glowMode(ctx: Context): Int = sp(ctx).getInt("glowMode", 0)
    fun setGlowMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("glowMode", v).apply()

    /** 红晕防抖秒数，默认 3，可调 1–10（§7.1） */
    fun glowDebounceSec(ctx: Context): Int = sp(ctx).getInt("glowDebounceSec", 3)
    fun setGlowDebounceSec(ctx: Context, v: Int) = sp(ctx).edit().putInt("glowDebounceSec", v.coerceIn(1, 10)).apply()

    /** 第 N 次会话计数（所有档位都计，§12.0） */
    fun sessionCount(ctx: Context): Int = sp(ctx).getInt("sessionCount", 0)
    fun incSessionCount(ctx: Context): Int {
        val n = sessionCount(ctx) + 1
        sp(ctx).edit().putInt("sessionCount", n).apply()
        return n
    }

    /** 首次引导是否已完成（§12.1） */
    fun firstRunDone(ctx: Context): Boolean = sp(ctx).getBoolean("firstRunDone", false)
    fun setFirstRunDone(ctx: Context) = sp(ctx).edit().putBoolean("firstRunDone", true).apply()

    /**
     * PC 服务端 IP（§3.3 双通道）。PC 换机 / 换 IP 时只改这一处：
     * ASR = http://<ip>:8010，Qwen = http://<ip>:11434。
     */
    fun pcIp(ctx: Context): String = sp(ctx).getString("pcIp", "192.168.3.15")!!
    fun setPcIp(ctx: Context, ip: String) = sp(ctx).edit().putString("pcIp", ip.trim()).apply()
    fun pcAsrPort(ctx: Context): Int = sp(ctx).getInt("pcAsrPort", 8010)
    fun pcLlmPort(ctx: Context): Int = sp(ctx).getInt("pcLlmPort", 11434)

    /** 模型目录（app 私有拷贝工作区，SAF 导入后落地于此） */
    fun modelsDir(ctx: Context): String =
        ctx.getExternalFilesDir(null)?.let { "${it.absolutePath}/models" } ?: "${ctx.filesDir.absolutePath}/models"

    /** 本地 Qwen 目录（models 下的 llm 子目录，放 .gguf） */
    fun llmDir(ctx: Context): String = "${modelsDir(ctx)}/llm"

    /** 本地 Qwen 开关：默认关（§5.7 可选、录后按需加载、判完立即释放） */
    fun llmEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("llmEnabled", false)
    fun setLlmEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("llmEnabled", v).apply()

    /** 会话根目录（app 外部私有，免权限写入；文件管理器/USB 可访问） */
    fun sessionsRoot(ctx: Context): String =
        ctx.getExternalFilesDir(null)?.let { "${it.absolutePath}/SpeakClear/sessions" }
            ?: "${ctx.filesDir.absolutePath}/SpeakClear/sessions"

    /**
     * 取景水平偏移：**按屏幕朝向分别存**（Surface.ROTATION_0/90/180/270）。
     * -1 = 露出画面最右一段，0 = 正中，+1 = 露出最左一段。
     *
     * ⚠️ 为什么要分朝向：本 app 是 sensorLandscape（允许横屏正反两个方向）。
     * 手机掉个头，镜头相对孩子的左右就翻过来了，同一个偏移量在另一头一定是错的。
     * 这里**不猜映射关系**（是取反还是别的，取决于镜像 + 旋转怎么叠，各机型还不统一），
     * 而是一头一套：自动标定（画面里肤色质心）写这里；用户手拖过就以手拖的为准，不再自动改。
     */
    fun displayRotation(ctx: Context): Int {
        val r = if (android.os.Build.VERSION.SDK_INT >= 30) {
            ctx.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
        }
        return r ?: 0
    }

    private fun biasKey(rot: Int) = "cropBias_r$rot"
    private fun manualKey(rot: Int) = "cropBiasManual_r$rot"

    fun cropBias(ctx: Context, rot: Int): Float = sp(ctx).getFloat(biasKey(rot), 0f)
    fun setCropBias(ctx: Context, rot: Int, v: Float, manual: Boolean = false) =
        sp(ctx).edit().putFloat(biasKey(rot), v.coerceIn(-1f, 1f))
            .putBoolean(manualKey(rot), manual).apply()
    /** 用户是否手动拖过取景（拖过就不再自动标定） */
    fun cropBiasManual(ctx: Context, rot: Int): Boolean = sp(ctx).getBoolean(manualKey(rot), false)
    /** 这个朝向标定过吗 —— 没标定过必须重新自动标定，绝不能拿另一个朝向的值顶上 */
    fun hasCropBias(ctx: Context, rot: Int): Boolean = sp(ctx).contains(biasKey(rot))
}
