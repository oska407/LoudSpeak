package com.oska.loudspeak.core

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络模型通道（R11 **预留**，§3.3 双通道的 App 侧入口）。
 *
 * 设计里 PC 是「增强」不是「依赖」：通了走 GPU 大模型，不通立刻回本地小模型，
 * 所以这里只负责三件事：**选哪条路 / 探活 / 说清现在走的是哪条**。
 *
 * 联网仅用于「局域网 PC 判定桥」（§R9）：Manifest 已声明 INTERNET 权限并放行明文
 * http://（usesCleartextTraffic=true，因为家里 PC 的 judge/asr 服务是 http 而非 https）。
 * 但仍严格守住「默认不联网」：开关 pcJudgeEnabled 默认 false，关着时本类不会被调用、
 * 任何代码路径都不发网络请求；只有用户明确打开开关、且家里 PC 起了对应服务才走局域网。
 * 任一环节失败（无网 / 超时 / 服务没起 / 返回非 JSON）→ 上层回退本地规则，不会卡住。
 */
object NetChannel {
    const val AUTO = 0
    const val LOCAL = 1
    const val PC = 2

    fun name(mode: Int): String = when (mode) {
        LOCAL -> "只用本地（完全离线）"
        PC -> "只用 PC"
        else -> "自动（PC 通就用 PC）"
    }

    /** 网络通道在本机是否具备运行条件 —— 没有 INTERNET 权限时恒为 false */
    fun available(ctx: Context): Boolean = hasInternetPermission(ctx)

    private fun hasInternetPermission(ctx: Context): Boolean = try {
        ctx.packageManager.checkPermission(
            android.Manifest.permission.INTERNET, ctx.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    /**
     * 探活：GET http://<ip>:8010/health，2 秒超时（§3.3「探活 2s 自动选路」）。
     * @return true 通 / false 不通 / **null 不知道**（没权限或没配置）
     */
    fun probe(ctx: Context): Boolean? {
        if (!available(ctx)) return null
        return try {
            val url = URL("http://${Prefs.pcIp(ctx)}:${Prefs.pcAsrPort(ctx)}/health")
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 2000
            c.readTimeout = 2000
            c.requestMethod = "GET"
            val ok = c.responseCode in 200..299
            c.disconnect()
            ok
        } catch (_: Exception) { false }
    }

    /** 判定/识别该走 PC 吗（本轮实际只会返回 false，见类注释） */
    fun shouldUsePc(ctx: Context): Boolean = when (Prefs.netMode(ctx)) {
        LOCAL -> false
        PC -> available(ctx)
        else -> available(ctx) && (probe(ctx) == true)
    }

    /** 设置页上那行状态说明 */
    fun statusText(ctx: Context): String {
        if (!available(ctx)) {
            return "状态：通道未启用（App 未申请网络权限）\n" +
                    "现在所有识别与判定都在本机完成；下面的选择会先记住，接上 PC 批次后立即生效。"
        }
        val r = probe(ctx)
        return when (r) {
            true -> "状态：PC 可达 🟢（${Prefs.pcIp(ctx)}）"
            false -> "状态：PC 不可达 🔴，已回退本地"
            else -> "状态：未知"
        }
    }
}
