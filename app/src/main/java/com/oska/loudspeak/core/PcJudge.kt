package com.oska.loudspeak.core

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 局域网 PC 判定桥（§R9「PC 局域网加速」路线）。
 *
 * 手机把分句 POST 到家里 PC 上跑大模型，取回约定格式的 JSON 判定
 * （idx + flags + summary），经 [QwenJudge.parseLlm] 写回各句 flags。
 *
 * 【2026-10-24】手机本地 Qwen 后端已废弃，本桥是**唯一**的模型判定通路。
 * 好处是 PC 侧模型可以随便换（4B / 8B 都行），因为两边只靠下面这段 JSON 契约说话。
 *
 * 设计定位（2026-10-24 路线拍板：先规则、后 PC 桥，分步）：
 *  - 默认 **完全关闭**（[Prefs.pcJudgeEnabled]=false），关闭时本类不会被调用，数据绝不离开手机；
 *  - 仅当用户明确认可「家庭可信 LAN 内可接受」并打开开关才启用；
 *  - 启用需要：① AndroidManifest 的 INTERNET 权限（已加，默认不触发任何联网）② 家里 PC 起
 *    judge_server.py（监听 [Prefs.pcIp]:[Prefs.pcJudgePort]，默认 8011），POST /judge 收
 *    `{"sentences":[{"idx":0,"text":"..."}]}`，回上面的 JSON。注意 8011 不是 Ollama 自己的 11434，
 *    两者不能混：judge_server 是中间适配层，负责把大模型的回答翻译成约定好的 JSON；
 *  - 任一环节失败（无网 / 超时 / 服务没起 / 返回非 JSON）→ 返回 null，上层 [QwenJudge] 回退规则后端。
 *
 * 复用：返回的 JSON 解析直接走 [QwenJudge.parseLlm]。下面这份 JSON 就是双方的**全部契约**，
 * PC 侧用任何框架（llama.cpp / Ollama / 自写 FastAPI）只要产出这个格式，手机端一行都不用改。
 */
object PcJudge {
    private const val TAG = "PcJudge"
    private const val CONNECT_MS = 8000
    private const val READ_MS = 90000   // PC 上跑 8B，几十句够用但要留余量；超时就回退规则

    /** 是否启用（由设置开关控制，默认 false） */
    fun available(ctx: Context): Boolean = Prefs.pcJudgeEnabled(ctx)

    /**
     * 向家里 PC 的 /judge 发分句，取回判定。
     * @return 总评（可空）；副作用：把 PC 判的结构性 flags 写回各句（经 [QwenJudge.parseLlm]）。
     *         失败返回 null，调用方回退规则。
     */
    fun judge(ctx: Context, sentences: List<RuleEngine.Sentence>): String? {
        if (!Prefs.pcJudgeEnabled(ctx)) return null
        val ip = Prefs.pcIp(ctx).takeIf { it.isNotBlank() } ?: return null
        val body = try {
            JSONObject().apply {
                put("sentences", JSONArray().apply {
                    sentences.forEach { put(JSONObject().put("idx", it.index).put("text", it.text)) }
                })
            }.toString()
        } catch (e: Exception) { return null }

        return try {
            val resp = post(ip, Prefs.pcJudgePort(ctx), body)
            QwenJudge.parseLlm(resp, sentences)   // 按约定的 JSON 契约解析
        } catch (e: Throwable) {
            CrashReporter.report("warn", "PC 判定桥回退规则：${e.message?.take(120)}", e)
            null
        }
    }

    /**
     * 设置页「测试一下」用：拿两句样例真跑一次 /judge，把结果（或失败原因）说成人话。
     * 跟 [judge] 走同一条路、同一份超时设置，所以它能通 = 判定真能用。
     * @return Pair(是否成功, 给人看的一句话)
     */
    fun probe(ip: String, port: Int): Pair<Boolean, String> {
        if (ip.isBlank()) return false to "还没填 PC 的 IP"
        val demo = JSONArray().apply {
            put(JSONObject().put("idx", 0).put("text", "我们先把 x 移项到左边"))
            put(JSONObject().put("idx", 1).put("text", "然后"))
        }
        val body = JSONObject().put("sentences", demo).toString()
        return try {
            val t0 = System.currentTimeMillis()
            val resp = post(ip, port, body)
            val ms = System.currentTimeMillis() - t0
            val o = JSONObject(resp)
            val model = o.optString("model", "").takeIf { it.isNotBlank() } ?: "未知模型"
            val elapsed = o.optDouble("elapsed_s", 0.0)
            val summary = o.optString("summary", "").trim()
            if (summary.isEmpty()) false to "服务回了，但没有总评（summary 为空），不会被采用"
            else true to "✓ 通了：$model｜$elapsed 秒｜手机到这里 ${ms}ms\n总评：$summary"
        } catch (e: IOException) {
            false to "✗ 连不上：${e.message?.take(100)}\n检查：PC 上跑 start-server.bat 了吗？浏览器开 http://$ip:$port/health 能看到 model 才算通。"
        } catch (e: Throwable) {
            false to "✗ 失败：${e.message?.take(100)}"
        }
    }

    /** POST 一段 JSON 到 http://ip:port/judge，拿回响应正文；非 200 抛 IOException（带上服务端 detail）。 */
    private fun post(ip: String, port: Int, body: String): String {
        val conn = (URL("http://$ip:$port/judge").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connectTimeout = CONNECT_MS
            readTimeout = READ_MS
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val err = try {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } catch (_: Exception) { null }
            conn.disconnect()
            Log.w(TAG, "PC /judge 返回 $code: ${err?.take(200)}")
            throw IOException("HTTP $code${err?.take(120)?.let { " · $it" } ?: ""}")
        }
        val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()
        return resp
    }
}
