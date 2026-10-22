package com.oska.loudspeak.core

/**
 * 规则引擎（§8）：L1 指代词（词表 100% 准）+ L4 三层规则（高召回）+ 跨句兜底（§8.4.2）。
 * LLM 复核（§8.4.3）在 A3x 上默认关闭，judge 恒为 null，判定只看 flags（§5.7 纯规则模式）。
 * 硬约束：阈值宁松勿紧（§2.1）——L4 默认不启用，只在会话次数达标后开（§8.6）。
 */
object RuleEngine {

    /** L1 默认词表（§8.1 / §14.2 D3）：不含 他/她/其 */
    val PRONOUNS = listOf("这个", "那个", "这些", "那些", "这样", "那样", "它")

    /** 口头禅/填充词（§R9 标注体系）：纯语气词，与代词不重叠；弱化显示，提示「能省就省」 */
    val FILLERS = listOf("嗯", "啊", "呃", "额", "哦", "唔", "哎", "呀", "诶")

    /** 单句数据（对应 §10.3 transcript 字段的内存形态） */
    class Sentence(
        val index: Int,
        val tStartMs: Long,
        val tEndMs: Long,
        val text: String,            // ASR 原文（可能 null→显示?）
        val uncertain: Boolean,      // 识别不清（ASR 返回 null）
        val pronouns: MutableList<Pair<String, Int>> = mutableListOf(), // 词→句内位置
        val fillers: MutableList<Pair<String, Int>> = mutableListOf(),  // 口头禅→句内位置（§R9）
        val flags: MutableList<String> = mutableListOf(),               // 缺主体/半截句/要素缺失
        var selfMarked: Boolean = false,
        val take: Int = 1,
        var judge: String? = null    // 追问语（规则生成；LLM 模式下由模型生成，当前恒 null→用规则追问）
    ) {
        /** 一句内 ? 过半 → 整句退出判定（§6.3.2） */
        val judgeable: Boolean get() = !uncertain
        val isProblem: Boolean get() = judgeable && !selfMarked && (pronouns.isNotEmpty() || flags.isNotEmpty())
        val followUp: String
            get() = when {
                pronouns.isNotEmpty() -> "这个是哪个？能叫出它的名字吗？"
                flags.contains("半截句") -> "这句话说完了吗？把话补完整。"
                flags.contains("缺主体") -> "是谁/是什么等于它？先说主体。"
                flags.contains("要素缺失") -> "先说题目要求什么，再说怎么算。"
                else -> ""
            }
    }

    /** 对一句做 L1 匹配。返回命中列表（词, 位置）。 */
    fun matchPronouns(text: String): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (p in PRONOUNS) {
            var i = text.indexOf(p)
            while (i >= 0) {
                hits.add(p to i)
                i = text.indexOf(p, i + p.length)
            }
        }
        return hits.sortedBy { it.second }
    }

    /** 口头禅匹配（§R9 标注体系）。返回命中列表（词, 位置）。 */
    fun matchFillers(text: String): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (f in FILLERS) {
            var i = text.indexOf(f)
            while (i >= 0) {
                hits.add(f to i)
                i = text.indexOf(f, i + f.length)
            }
        }
        return hits.sortedBy { it.second }
    }

    /**
     * 整段判定（§12.3）：逐句 L1 + L4 高召回 → 跨句兜底撤销部分「缺主体」。
     * l4Enabled：按「第 N 次会话」节奏（§8.6），第 13 次会话起才开 L4-a。
     */
    fun evaluate(sentences: List<Sentence>, l4Enabled: Boolean) {
        // L1
        for (s in sentences) {
            if (!s.judgeable || s.selfMarked) continue
            s.pronouns.clear(); s.flags.clear(); s.fillers.clear()
            s.pronouns.addAll(matchPronouns(s.text))
            s.fillers.addAll(matchFillers(s.text))
        }
        if (!l4Enabled) return

        // L4-a 缺主体：句首以动词/介词开头且前句无主体（简化词表）
        val subjectHints = listOf("x", "y", "2x", "3x", "左边", "右边", "分子", "分母", "方程", "题目",
            "这道题", "等式", "两边", "式子", "因式", "多项式", "单项式", "结果", "答案", "它")
        val startsWithAction = listOf("等于", "加上", "减去", "乘以", "除以", "移项", "合并", "通分", "约分", "然后", "所以", "因为")
        for (s in sentences) {
            if (!s.judgeable || s.selfMarked || s.pronouns.isNotEmpty()) continue
            if (startsWithAction.any { s.text.startsWith(it) }) s.flags.add("缺主体")
        }
        // 跨句兜底（§8.4.2）：前后句已提供明确主体 → 撤销
        for (i in sentences.indices) {
            val s = sentences[i]
            if (!s.flags.contains("缺主体")) continue
            val prev = sentences.getOrNull(i - 1)?.text ?: ""
            val next = sentences.getOrNull(i + 1)?.text ?: ""
            if (subjectHints.any { prev.contains(it) } || subjectHints.any { next.contains(it) }) {
                s.flags.remove("缺主体")
            }
        }
        // L4-b 半截句：<6 字且不含因果词；或以「然后/所以/就/再」结尾
        for (s in sentences) {
            if (!s.judgeable || s.selfMarked || s.pronouns.isNotEmpty() || s.flags.isNotEmpty()) continue
            val t = s.text
            val causal = listOf("因为", "所以", "由于", "因此")
            if ((t.length < 6 && causal.none { t.contains(it) }) ||
                listOf("然后", "所以", "就", "再").any { t.endsWith(it) }) {
                s.flags.add("半截句")
            }
        }
        // L4-c 要素缺失（§8.4.1）：整段讲了结论词但全程没思路词 → 全段从宽，仅标注最后一句
        val idea = listOf("因为", "所以", "先", "再", "移项", "通分", "两边", "代入", "化简")
        val conclusion = listOf("所以", "答案是", "结果是", "解得")
        if (sentences.isNotEmpty() && sentences.none { idea.any { w -> it.text.contains(w) } } &&
            sentences.any { conclusion.any { w -> it.text.contains(w) } }) {
            sentences.last().flags.add("要素缺失")
        }
    }

    /** 分级开关（§8.6）：第 13 次会话起开 L4-a；仅此一级，宁漏勿错 */
    fun l4Enabled(sessionCount: Int): Boolean = sessionCount >= 13

    /** 第 2 遍改善语（§9.2 步 3）：只呈现改善的部分 */
    fun improvementMessage(prevPronouns: Int, curPronouns: Int): String =
        "第 2 遍，你把 ${prevPronouns - curPronouns} 处「这个」说出了名字。"
}
