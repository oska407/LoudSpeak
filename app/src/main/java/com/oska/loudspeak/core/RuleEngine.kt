package com.oska.loudspeak.core

/**
 * 规则引擎（§8）：L1 指代词（词表 100% 准）+ L4 三层规则（高召回）+ 跨句兜底（§8.4.2）。
 * LLM 复核（§8.4.3）在 A3x 上默认关闭，judge 恒为 null，判定只看 flags（§5.7 纯规则模式）。
 * 硬约束：阈值宁松勿紧（§2.1）——L4 默认不启用，只在会话次数达标后开（§8.6）。
 */
object RuleEngine {

    /** L1 默认词表（§8.1 / §14.2 D3）。可在设置里增删，这里是出厂默认：
     *  含 这/那/这边/那边 的各形态，且「这个/那个」作为子串会自动覆盖「这个X / 那个X」。 */
    val PRONOUNS = listOf("这个", "那个", "这些", "那些", "这样", "那样", "这边", "那边", "它")

    /** 口头禅/填充词（§R9 标注体系）：纯语气词，与代词不重叠；弱化显示，提示「能省就省」 */
    val FILLERS = listOf("嗯", "啊", "呃", "额", "哦", "唔", "哎", "呀", "诶")

    /** L2 模糊词（§8.2 / D3）：默认关闭，第 9 次会话起才开 —— 放了等于没说 */
    val MUZZY = listOf("东西", "玩意儿", "什么的", "有点")

    // ---- L4 主谓宾 / 句子结构词表（§8.4，结构分析用；可调） ----
    /** 句首的「动词/介词/连接」类：正常应先有主语，直接开头多半缺主体 */
    val VERB_STARTERS = listOf("等于", "加上", "减去", "乘以", "除以", "移项", "合并", "通分", "约分",
        "代入", "化简", "展开", "去括号", "解得", "结果是", "答案是", "先", "再", "然后",
        "所以", "因为", "由于", "因此", "但是", "如果", "接着", "于是", "设", "令", "把", "被")
    /** 主语线索：出现即「这一带有主体」，可抵消缺主体 */
    val SUBJECT_HINTS = listOf("x", "y", "2x", "3x", "左边", "右边", "分子", "分母", "方程", "题目",
        "这道题", "等式", "两边", "式子", "因式", "多项式", "单项式", "结果", "答案", "它",
        "三角形", "正方形", "图形", "长方形", "角", "边", "数", "我们", "我")
    /** 句末悬空连接词：话没说完 */
    val DANGLING_END = listOf("所以", "然后", "就", "再", "因为", "但是", "如果", "而且",
        "那", "那么", "接着", "于是", "因此")
    /** 从句引导词：开了头通常要有主句收尾，否则半截 */
    val SUBORDINATORS = listOf("因为", "由于", "如果", "但是", "虽然", "即使", "要是", "假如")
    /** 主句收束词：与从句引导词配对 */
    val MAIN_CLAUSE = listOf("所以", "因此", "于是", "就", "那", "那么", "结果")
    /** 思路词（解题过程） */
    val REASONING = listOf("因为", "先", "再", "移项", "通分", "两边", "代入", "化简", "设", "令",
        "去括号", "展开", "合并", "由于", "第一步", "首先", "接着")
    /** 结论词（答案/结果） */
    val CONCLUSION = listOf("所以", "答案是", "结果是", "解得", "因此", "最终", "等于")


    /**
     * 分级开关（§8.6）。顺序按「她自己能否察觉」排：L1 → L3 → L2 → L4-a → L4-b/c，
     * 不是按编号排。index 即第几级（1..5）。
     */
    data class Levels(
        val index: Int,
        val l2: Boolean,      // 模糊词（东西/玩意儿…）
        val l4a: Boolean,     // 缺主体
        val l4bc: Boolean     // 半截句 / 要素缺失
    ) {
        val l4: Boolean get() = l4a || l4bc
        val name: String get() = levelName(index)
    }

    /** 按「第 N 次会话」自动递进（§8.6 表） */
    fun autoLevelIndex(sessionCount: Int): Int = when {
        sessionCount >= 20 -> 5
        sessionCount >= 13 -> 4
        sessionCount >= 9 -> 3
        sessionCount >= 5 -> 2
        else -> 1
    }

    fun levelName(i: Int): String = when (i) {
        1 -> "L1 指代词"
        2 -> "L1 指代词 + L3 口头禅"
        3 -> "L1 + L3 + L2 模糊词"
        4 -> "L1 + L3 + L2 + L4-a 缺主体"
        else -> "全开（含 L4-b 半截句 / L4-c 要素缺失）"
    }

    /**
     * 取当前级别：override 1..5 表示手动锁定（设置页可改），0 = 自动按次数递进。
     * 手动锁定是为了「她已经做得到，别再等次数」这种情况，也方便真机逐级验证。
     */
    fun levels(sessionCount: Int, override: Int): Levels {
        val i = if (override in 1..5) override else autoLevelIndex(sessionCount)
        return Levels(i, l2 = i >= 3, l4a = i >= 4, l4bc = i >= 5)
    }

    /** 单句数据（对应 §10.3 transcript 字段的内存形态） */
    class Sentence(
        val index: Int,
        val tStartMs: Long,
        val tEndMs: Long,
        val text: String,            // ASR 原文（可能 null→显示?）
        val uncertain: Boolean,      // 识别不清（ASR 返回 null）
        val pronouns: MutableList<Pair<String, Int>> = mutableListOf(), // 词→句内位置
        val fillers: MutableList<Pair<String, Int>> = mutableListOf(),  // 口头禅→句内位置（§R9）
        val muzzy: MutableList<Pair<String, Int>> = mutableListOf(),    // 模糊词→句内位置（L2）
        val flags: MutableList<String> = mutableListOf(),               // 缺主体/半截句/要素缺失
        var selfMarked: Boolean = false,
        val take: Int = 1,
        var judge: String? = null,   // 追问语（规则生成；LLM 模式下由模型生成，当前恒 null→用规则追问）
        /** 句末标点（Punctuator 按语法补的，**不是 ASR 给的**） */
        var punct: String = "",
        /** 这一句其实是上一句的碎片，只是被停顿切开了 —— 显示时接上去，不单独算一句 */
        var mergePrev: Boolean = false,
        /**
         * 每个字在**会话时间轴**上的绝对毫秒（SenseVoice 字级时间戳换算，见 [AsrResult.absMs]）。
         * 空数组 = 没拿到时间戳（识别失败，或模型不支持），此时流畅度报告里跳过这一句。
         */
        var absMs: LongArray = LongArray(0),
        /** 与 [absMs] 一一对应的字表（已剥壳） */
        var absWords: Array<String> = emptyArray()
    ) {
        /** 显示用文本：原文 + 补的标点。**原文一个字都不改**（硬约定） */
        val displayText: String get() = text + punct
        /** 统计口径：并回上一句的碎片不算独立一句 */
        val counted: Boolean get() = !mergePrev

        /** 一句内 ? 过半 → 整句退出判定（§6.3.2） */
        val judgeable: Boolean get() = !uncertain
        val isProblem: Boolean get() = judgeable && !selfMarked && (pronouns.isNotEmpty() || flags.isNotEmpty())
        val followUp: String
            get() = when {
                pronouns.isNotEmpty() -> "这个是哪个？能叫出它的名字吗？"
                flags.contains("模糊词") -> "「东西/玩意儿」是什么？换成它的名字。"
                flags.contains("半截句") -> "这句话说完了吗？把话补完整。"
                flags.contains("缺主体") -> "是谁/是什么等于它？先说主体。"
                flags.contains("要素缺失") -> "先说题目要求什么，再说怎么算。"
                else -> ""
            }
    }

    /**
     * 对一句做 L1 匹配。返回命中列表（词, 位置）。
     * @param list 代词词表（来自设置，可编辑；默认 PRONOUNS）。
     *   支持「前缀写法」：条目以 * 结尾（如 "这个*"）表示「这个词开头的整段都标」，
     *   一直标到空白/标点为止 —— 把「这个三角形」整段提示换成名字。
     */
    fun matchPronouns(text: String, list: List<String> = PRONOUNS): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (raw in list) {
            val base = raw.trim().removeSuffix("*").removePrefix("*")
            if (base.isEmpty()) continue
            val prefix = raw != base
            var i = text.indexOf(base)
            while (i >= 0) {
                val end = if (prefix) {
                    var e = i + base.length
                    while (e < text.length) {
                        val c = text[e]
                        if (c.isWhitespace() || c == '，' || c == '。' || c == '、' ||
                            c == ',' || c == '.' || c == '；' || c == ';') break
                        e++
                    }
                    e
                } else i + base.length
                hits.add(text.substring(i, end) to i)
                i = text.indexOf(base, end)
            }
        }
        // 去掉互相重叠的命中（按位置排好后，跳过落在已标区间内的）
        hits.sortBy { it.second }
        val out = mutableListOf<Pair<String, Int>>()
        var lastEnd = -1
        for ((w, p) in hits) {
            if (p >= lastEnd) { out.add(w to p); lastEnd = p + w.length }
        }
        return out
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

    /** 模糊词匹配（L2，§8.2）。返回命中列表（词, 位置）。 */
    fun matchMuzzy(text: String): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (m in MUZZY) {
            var i = text.indexOf(m)
            while (i >= 0) {
                hits.add(m to i)
                i = text.indexOf(m, i + m.length)
            }
        }
        return hits.sortedBy { it.second }
    }

    /**
     * 整段判定（§12.3）：逐句 L1 + L2（可选）+ L4 高召回 → 跨句兜底撤销部分「缺主体」。
     * levels：按「第 N 次会话」自动递进或设置页手动锁定（§8.6）。
     */
    fun evaluate(sentences: List<Sentence>, levels: Levels, muzzyOn: Boolean = true,
                 pronounList: List<String> = PRONOUNS) {
        // L1 + L3（口头禅只做标注，不进问题计数）
        for (s in sentences) {
            if (!s.judgeable || s.selfMarked) continue
            s.pronouns.clear(); s.flags.clear(); s.fillers.clear(); s.muzzy.clear()
            // 代词命中用「设置里可编辑的词表」，与标注渲染（Annotator.build）同一套
            s.pronouns.addAll(matchPronouns(s.text, pronounList))
            s.fillers.addAll(matchFillers(s.text))
        }
        // L2 模糊词
        if (levels.l2 && muzzyOn) {
            for (s in sentences) {
                if (!s.judgeable || s.selfMarked) continue
                s.muzzy.addAll(matchMuzzy(s.text))
                if (s.muzzy.isNotEmpty() && s.pronouns.isEmpty()) s.flags.add("模糊词")
            }
        }
        if (!levels.l4) return

        // L4-a 缺主体：句首以动词/介词/连接开头，且本句无主体线索 → 疑缺主体
        if (levels.l4a) {
            for (s in sentences) {
                if (!s.judgeable || s.selfMarked || s.pronouns.isNotEmpty()) continue
                val t = s.text
                val startsAction = VERB_STARTERS.any { t.startsWith(it) }
                val hasSubject = SUBJECT_HINTS.any { t.contains(it) }
                if (startsAction && !hasSubject) s.flags.add("缺主体")
            }
            // 跨句兜底（§8.4.2）：前/后句已给主体 → 撤销
            for (i in sentences.indices) {
                val s = sentences[i]
                if (!s.flags.contains("缺主体")) continue
                val prev = sentences.getOrNull(i - 1)?.text ?: ""
                val next = sentences.getOrNull(i + 1)?.text ?: ""
                if (SUBJECT_HINTS.any { prev.contains(it) } || SUBJECT_HINTS.any { next.contains(it) }) {
                    s.flags.remove("缺主体")
                }
            }
        }
        if (!levels.l4bc) return

        // L4-b 半截句：句末悬空连接词，或开了从句引导词却没主句收尾（结构不完整）
        for (i in sentences.indices) {
            val s = sentences[i]
            if (!s.judgeable || s.selfMarked || s.pronouns.isNotEmpty() || s.flags.isNotEmpty()) continue
            val t = s.text
            val endsDangling = DANGLING_END.any { t.endsWith(it) }
            val startsSub = SUBORDINATORS.any { t.startsWith(it) }
            val nextMain = sentences.getOrNull(i + 1)?.text?.let { n -> MAIN_CLAUSE.any { w -> n.contains(w) } } ?: false
            val subNoMain = startsSub && !MAIN_CLAUSE.any { t.contains(it) } && !nextMain
            if (endsDangling || subNoMain) s.flags.add("半截句")
        }

        // L4-c 要素缺失（§8.4.1）：整段「有结论无思路」或「有思路无结论」→ 标注最后一句
        val hasReasoning = sentences.any { it.judgeable && REASONING.any { w -> it.text.contains(w) } }
        val hasConclusion = sentences.any { it.judgeable && CONCLUSION.any { w -> it.text.contains(w) } }
        if (sentences.isNotEmpty() && (hasConclusion != hasReasoning)) {
            // 只标最后一句、且不覆盖已判的更具体标记
            val last = sentences.last()
            if (last.judgeable && last.flags.none { it == "半截句" || it == "缺主体" }) last.flags.add("要素缺失")
        }
    }

    /** 兼容旧签名：只区分「L4 开不开」 */
    fun evaluate(sentences: List<Sentence>, l4Enabled: Boolean, pronounList: List<String> = PRONOUNS) =
        evaluate(sentences, Levels(if (l4Enabled) 5 else 1, l2 = false, l4a = l4Enabled, l4bc = l4Enabled), pronounList = pronounList)

    /** 分级开关（§8.6）：第 13 次会话起开 L4-a；仅此一级，宁漏勿错 */
    fun l4Enabled(sessionCount: Int): Boolean = sessionCount >= 13

    /** 第 2 遍改善语（§9.2 步 3）：只呈现改善的部分 */
    fun improvementMessage(prevPronouns: Int, curPronouns: Int): String =
        "第 2 遍，你把 ${prevPronouns - curPronouns} 处「这个」说出了名字。"
}
