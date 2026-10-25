package com.oska.loudspeak.core

import android.content.Context
import org.json.JSONObject

/**
 * 离线自动判定（§R9）。
 *
 * 两套后端，同一条接口（优先级：PC 桥，失败一律回退规则）：
 *  1) 规则后端（默认、永远可用、纯离线）：基于 RuleEngine 的代词/口头禅/句式问题，
 *     直接生成「本场改进评语」与每句追问语。不需要任何模型文件、不需要联网。
 *  2) 局域网 PC 判定桥（可选、默认关、需用户认可）：开启后把分句 POST 到家里 PC 上跑大模型，
 *     取回 JSON 判定（idx + flags + summary）。默认关闭，关闭时判定数据绝不离开手机。
 *
 * 【2026-10-24 移除：手机本地 Qwen 后端】
 * 此前预留了「拷 Qwen GGUF 进手机 → LlamaRunner 走 llama.cpp JNI 本地推理」的第三条路，已废弃：
 *  - 原生推理库从未打进 APK，`LlamaRunner.available` 恒为 false，本地推理一次也没跑起来过；
 *  - 真跑起来还需额外做 libllama.so 打包 + JNI C 实现，且 0.6B Q4 在无 NPU 的 A3x 上
 *    单次判定预计 25~45 秒、质量也只是「勉强」（见 §5.7）。
 * 判定上调质量的唯一路线收敛为 **PC 判定桥**（PC 上跑大模型，不受手机算力约束）。
 */
object QwenJudge {

    /**
     * 判定入口。
     * @return 总评语（可空，null=无内容） + 每句判定文本（index→文本，可空表示该句无需判定）
     */
    fun judge(
        ctx: Context,
        sentences: List<RuleEngine.Sentence>
    ): JudgeResult {
        // 规则后端：先给每句问题句填上规则追问语（PC 桥没接上时它就是最终判定）
        val perSentence = HashMap<Int, String?>()
        for (s in sentences) if (s.isProblem) perSentence[s.index] = s.followUp.takeIf { it.isNotEmpty() }
        val overallRules = buildOverallRules(sentences)

        // 局域网 PC 判定桥（§R9「PC 局域网加速」）：用户认可并开启后才走；失败静默回退
        if (Prefs.pcJudgeEnabled(ctx)) {
            try {
                val pc = PcJudge.judge(ctx, sentences)
                if (pc != null) {
                    val per = HashMap<Int, String?>()
                    for (s in sentences) if (s.isProblem) per[s.index] = s.followUp.takeIf { it.isNotEmpty() }
                    CrashReporter.report("info", "判定走局域网 PC 桥", null)
                    return JudgeResult(pc, per)
                }
            } catch (e: Throwable) {
                CrashReporter.report("warn", "PC 判定桥回退规则：${e.message?.take(120)}", e)
            }
        }
        return JudgeResult(overallRules, perSentence)
    }

    /** 规则总评语（离线、确定可用） */
    private fun buildOverallRules(sentences: List<RuleEngine.Sentence>): String {
        val total = sentences.size
        val ok = sentences.count { !it.uncertain }
        val pron = sentences.sumOf { it.pronouns.size }
        val fill = sentences.sumOf { it.fillers.size }
        val incomplete = sentences.count { it.isProblem && it.pronouns.isEmpty() }
        return buildString {
            append("本次共 ${total} 句，说清楚 ${ok} 句。")
            if (pron > 0) append("用了 $pron 处代词，下次试试直接说出名字；")
            if (fill > 0) append("$fill 处口头禅可以省掉；")
            if (incomplete > 0) append("有 $incomplete 句没说完，把话说完整。")
            if (pron == 0 && fill == 0 && incomplete == 0) append("整体讲得很清楚，继续保持。")
        }
    }

    /** 主谓宾结构类问题（由 PC 桥大模型或规则产生，词汇一致，驱动 followUp 与标注） */
    private val STRUCT_FLAGS = setOf("缺主体", "半截句", "要素缺失")

    /**
     * 解析模型的 JSON 输出（本地 Qwen 与 PC 桥共用，格式一致）：
     *  - 整体评语 → 作为最终总评返回（null 则上层用规则总评）
     *  - 逐句 flags → 写回对应 Sentence.flags（先清掉旧的结构性标记，再填模型判的），
     *    这样 followUp / 标注 / 计数都跟着模型判定走，而代词 / 口头禅仍由规则保底。
     * 解析失败（模型没按 JSON 出 / 字段缺失 / 抛异常）→ 返回 null，上层整段回退规则，不污染任何句子。
     */
    internal fun parseLlm(text: String, sentences: List<RuleEngine.Sentence>): String? {
        val raw = text.trim().takeIf { it.isNotEmpty() } ?: return null
        // 模型可能在外面裹了 ```json ... ``` 或多余文字，截出第一个 { 到最后一个 }
        val json = runCatching {
            val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
            if (s < 0 || e <= s) return null
            JSONObject(raw.substring(s, e + 1))
        }.getOrNull() ?: return null

        val byIndex = sentences.associateBy { it.index }
        runCatching {
            val arr = json.getJSONArray("sentences")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val idx = o.optInt("idx", -1)
                val s = byIndex[idx] ?: continue
                // flags 是可选的：只有开了「L2 用大模型」服务端才给（levels 门控）
                val flags = o.optJSONArray("flags")
                if (flags != null) {
                    // 清掉旧的结构性标记（代词/口头禅不在 flags 里，不受影响），填模型判的
                    s.flags.removeAll(STRUCT_FLAGS)
                    for (j in 0 until flags.length()) {
                        val f = flags.optString(j)
                        if (f in STRUCT_FLAGS) s.flags.add(f)
                    }
                }
                // L1 大模型 / L3：代词原文 + 是否歧义。
                // 注意只认原文，位置由 App 端子串定位 —— 让模型数字符下标它数不准。
                val pr = o.optJSONArray("pronouns")
                if (pr != null) {
                    val list = ArrayList<RuleEngine.ModelPronoun>()
                    for (j in 0 until pr.length()) {
                        val p = pr.optJSONObject(j) ?: continue
                        val w = p.optString("text", "").trim()
                        if (w.isEmpty()) continue
                        list.add(RuleEngine.ModelPronoun(w, p.optBoolean("ambiguous", false)))
                    }
                    s.modelPronouns = list
                }
                // L4：更清楚的说法 + 语境化追问（非空才覆盖，空串不抹掉规则追问）
                o.optString("rewrite", "").trim().takeIf { it.isNotEmpty() }?.let { s.rewrite = it }
                o.optString("followup", "").trim().takeIf { it.isNotEmpty() }?.let { s.modelFollowUp = it }
            }
        }.onFailure { return null }

        val summary = runCatching { json.optString("summary", "") }.getOrNull().orEmpty().trim()
        return summary.ifEmpty { null }
    }
}

/** 判定结果 */
data class JudgeResult(
    val overall: String?,
    val perSentence: Map<Int, String?>
)
