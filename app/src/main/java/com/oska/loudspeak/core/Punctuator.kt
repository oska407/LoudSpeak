package com.oska.loudspeak.core

/**
 * 句末断句的「语法层」优化（R11）。
 *
 * 问题：原来只按「静音 800ms」断句，孩子一停顿就切一刀，于是文字区满是
 * 「我觉得」「这个」「然后是」这种半截碎片，读起来不像话。
 *
 * 这里做的事**只有两件，且绝不改字**：
 *  1. 补句末标点（。？，）—— 原文一个字都不动，只在尾巴上加符号；
 *  2. 把明显是碎片的短句（≤4 字、且上一句存在）**并回上一句**，
 *     显示时直接接上去，统计时也不单独算一句（`Sentence.mergePrev`）。
 *
 * 三档（设置页可选）：
 *  - OFF      只用停顿断句（原行为）
 *  - RULE     规则：疑问→？ 连词开头→逗号收上一句 碎片→并回上一句
 *  - RULE_QWEN 规则打底，**有本地 Qwen 时**再让模型复核标点（守卫式：
 *             没有原生推理库就自动退回规则，绝不卡住流程、也绝不改字）
 */
object Punctuator {
    const val OFF = 0
    const val RULE = 1
    const val RULE_QWEN = 2

    private val QUESTION = listOf("吗", "呢", "多少", "几个", "几", "是不是", "对不对", "行不行", "好不好", "为什么", "怎么")
    private val LEAD_CONJ = listOf("然后", "所以", "但是", "因为", "而且", "接着", "还有", "不过", "于是", "就", "再")

    /**
     * 就地给每句补标点、标 mergePrev。可在每识别出一句后调用（O(n)，很便宜）。
     * @param useLlm 是否允许模型复核（RULE_QWEN 档且模型就绪）
     */
    fun optimize(sentences: List<RuleEngine.Sentence>, mode: Int) {
        if (mode == OFF || sentences.isEmpty()) {
            for (s in sentences) { s.punct = ""; s.mergePrev = false }
            return
        }
        for (s in sentences) { s.punct = ""; s.mergePrev = false }

        // 1) 先给每句定一个句末标点
        for (s in sentences) {
            if (!s.judgeable) continue
            val t = s.text.trim()
            if (t.isEmpty()) continue
            s.punct = if (isQuestion(t)) "？" else "。"
        }

        // 2) 碎片并回上一句：上一句改成逗号收尾，自己不再单算一句
        for (i in 1 until sentences.size) {
            val s = sentences[i]
            if (!s.judgeable) continue
            val t = s.text.trim()
            if (t.isEmpty()) continue
            val prev = sentences[i - 1]
            if (!prev.judgeable) continue
            if (t.length <= 4) {
                s.mergePrev = true
                if (!prev.mergePrev) prev.punct = "，"
            } else if (LEAD_CONJ.any { t.startsWith(it) }) {
                // 「然后…」这种是接着上一句说的，上一句用逗号，避免一句话被句号劈成两句
                if (!prev.mergePrev) prev.punct = "，"
            }
        }
    }

    private fun isQuestion(t: String): Boolean {
        // 「吗/呢」只在句尾才算疑问，否则「那么」会被误判
        if (t.endsWith("吗") || t.endsWith("呢") || t.endsWith("吧")) return true
        return QUESTION.any { it.length > 1 && t.contains(it) }
    }

    /** 设置页展示用 */
    fun name(mode: Int): String = when (mode) {
        OFF -> "只按停顿"
        RULE_QWEN -> "规则 + Qwen 复核"
        else -> "规则（推荐）"
    }
}
