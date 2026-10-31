package com.oska.loudspeak.core

/**
 * 规则引擎（§8）：L1 指代词（词表 100% 准）+ L4 三层规则（高召回）+ 跨句兜底（§8.4.2）。
 * LLM 复核（§8.4.3）在 A3x 上默认关闭，judge 恒为 null，判定只看 flags（§5.7 纯规则模式）。
 * 硬约束：阈值宁松勿紧（§2.1）——L4 默认不启用，只在会话次数达标后开（§8.6）。
 */
object RuleEngine {

    /** L1 默认词表（§8.1 / §14.2 D3）。可在设置里增删，这里是出厂默认。
     *  用通配符覆盖：? = 一个字，* = 多个字（直到空白/标点）。
     *  「这?」覆盖 这个/这些/这样/这边…；「那?」覆盖 那个/那些/那样/那边…。 */
    val PRONOUNS = listOf("这?", "那?")

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
        var absWords: Array<String> = emptyArray(),

        /**
         * 音量不达标标注（回放改版补充）：true = 这个字说话时平均音量低于阈值。
         * 长度 = [text].length；录制时按当时阈值算好存进 transcript.json，回放原样恢复，
         * 不随阈值改变重算（避免改设置后历史回放结果漂走）。
         */
        var lowVol: BooleanArray = BooleanArray(0),

        // ---- 四层标注（标注多层次设计 v1）：大模型结果挂在句子上 ----
        /** 大模型判出的代词（原文 + 是否歧义）。null = 还没跑模型 / 模型层没开 */
        var modelPronouns: MutableList<ModelPronoun>? = null,
        /** L4：大模型给的「更清楚的说法」，null = 没给 */
        var rewrite: String? = null,
        /** L4：大模型给的智能追问语；非空时优先于规则追问 [followUp] */
        var modelFollowUp: String? = null
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
                // L4 开时大模型会给语境化的追问（如「你刚说等于 8，是哪个式子？」），比罐头追问有用
                !modelFollowUp.isNullOrBlank() -> modelFollowUp!!
                pronouns.isNotEmpty() -> "这个是哪个？能叫出它的名字吗？"
                flags.contains("模糊词") -> "「东西/玩意儿」是什么？换成它的名字。"
                flags.contains("半截句") -> "这句话说完了吗？把话补完整。"
                flags.contains("缺主体") -> "是谁/是什么等于它？先说主体。"
                flags.contains("要素缺失") -> "先说题目要求什么，再说怎么算。"
                else -> ""
            }
    }
    /** 大模型返回的一个代词：原文 + 旁听者是否猜不到（歧义）。
     * 只存原文、不存字符下标 —— 让模型数 offset 它数不准，App 端自己子串定位。 */
    data class ModelPronoun(val word: String, val ambiguous: Boolean)

    /**
     * 把大模型给的代词原文在句内定位，返回（词, 位置），按位置升序且不重叠。
     * 同一个词出现多次会全部命中；被更长命中包含的短命中会被丢掉，避免同一处重复标记。
     */
    fun locatePronouns(text: String, words: List<String>): List<Pair<String, Int>> {
        val hits = ArrayList<Pair<String, Int>>()
        for (w in words) {
            if (w.isBlank()) continue
            var from = 0
            while (from <= text.length - w.length) {
                val p = text.indexOf(w, from)
                if (p < 0) break
                hits.add(w to p)
                from = p + w.length
            }
        }
        hits.sortWith(compareBy<Pair<String, Int>> { it.second }.thenByDescending { it.first.length })
        val out = ArrayList<Pair<String, Int>>()
        var end = -1
        for (h in hits) {
            if (h.second < end) continue          // 被前面那条更长的命中包含 → 丢掉
            out.add(h)
            end = h.second + h.first.length
        }
        return out
    }


    /**
     * 对一句做 L1 匹配。返回命中列表（词, 位置）。
     * @param list 代词词表（来自设置，可编辑；默认 PRONOUNS）。支持通配符：
     *   - `?` 代表「一个字」：如 `这?` 覆盖 这个/这些/这样/这边…（这 + 任意一个字）。
     *   - `*` 代表「多个字」（至少一个，直到空白/标点为止）：如 `这*` 把「这个三角形」整段标红。
     *   纯文字（无通配符）按原词精确匹配，向下兼容。
     */
    fun matchPronouns(text: String, list: List<String> = PRONOUNS): List<Pair<String, Int>> {
        val hits = mutableListOf<Pair<String, Int>>()
        for (raw in list) {
            val pat = raw.trim()
            if (pat.isEmpty()) continue
            var s = 0
            while (s <= text.length) {
                val end = matchPatternAt(text, s, pat)
                if (end > s) {
                    hits.add(text.substring(s, end) to s)
                    s = end
                } else {
                    s++
                }
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

    /** 命中边界：空白与中英文标点都算，避免 `*` 跨句吞字 */
    private fun isBoundary(c: Char): Boolean = c.isWhitespace()
        || c == '，' || c == '。' || c == '、' || c == '；' || c == '：'
        || c == '！' || c == '？' || c == '…' || c == '（' || c == '）'
        || c == '"' || c == '“' || c == '”' || c == '\'' || c == '‘' || c == '’'
        || c == ',' || c == '.' || c == ';' || c == ':' || c == '!' || c == '?'
        || c == '(' || c == ')'

    /**
     * 从 text[start] 起尝试匹配 pattern（支持 ? 一个字、* 多个字），返回结束下标（不含）；不匹配返回 -1。
     */
    private fun matchPatternAt(text: String, start: Int, pattern: String): Int {
        var ti = start
        var pi = 0
        while (pi < pattern.length) {
            val p = pattern[pi]
            when {
                p == '?' -> {
                    if (ti >= text.length) return -1
                    ti++
                    pi++
                }
                p == '*' -> {
                    val nextLit = (pi + 1 until pattern.length)
                        .firstOrNull { pattern[it] != '*' }?.let { pattern[it] }
                    if (nextLit == null) {
                        // * 在末尾：吃掉直到边界（至少一个字）
                        if (ti >= text.length) return -1
                        while (ti < text.length && !isBoundary(text[ti])) ti++
                    } else {
                        // * 在中间：吃到下一个字面量前（遇边界则整段不匹配）
                        while (ti < text.length && !isBoundary(text[ti])) {
                            if (text[ti] == nextLit) break
                            ti++
                        }
                        if (ti >= text.length || text[ti] != nextLit) return -1
                        // 停在 nextLit 上，交给下一个字面量匹配
                    }
                    pi++
                }
                else -> {
                    if (ti >= text.length || text[ti] != p) return -1
                    ti++
                    pi++
                }
            }
        }
        return ti
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

    /**
     * 音量不达标标注（回放改版补充）：逐字判断「这个字说话时音量是否低于阈值」。
     *
     * 对齐思路：SenseVoice 的 [absWords]（剥壳后的 token）与 [absMs]（每字会话绝对毫秒）
     * 一一对应，且 [text] 大致等于各 token 拼接。中文下逐 token 基本是一字一 token，
     * 用**贪心顺序定位**（从已匹配位置往后找该 token）就能把每个 token 的时间戳
     * 映射到 [text] 的字符区间；英文子词/标点等匹配不上的 token 跳过（不漂移后续定位）。
     *
     * @param volDb 录音时每 ~[volIntervalMs] 一帧的原始 dB（0..60），index 0 对应 [volStartMs]
     * @param threshold 「达标线」dB，低于即算不达标（[Prefs.dbThreshold]）
     * @return 长度 = text.length，true = 该字说话时平均音量低于阈值
     */
    fun lowVolumeFlags(
        text: String, absWords: Array<String>, absMs: LongArray,
        volDb: List<Int>, volStartMs: Long, volIntervalMs: Long, threshold: Int
    ): BooleanArray {
        val out = BooleanArray(text.length)
        val n = minOf(absMs.size, absWords.size)
        if (n == 0 || volDb.isEmpty()) return out
        // 逐 token 贪心定位到 text，给覆盖的字符打上该 token 的时间戳
        val charT = LongArray(text.length) { -1L }
        var pos = 0
        for (k in 0 until n) {
            val w = absWords[k]
            if (w.isEmpty()) continue
            val p = text.indexOf(w, pos)
            if (p < 0) continue
            val t = absMs[k]
            for (j in p until minOf(p + w.length, text.length)) charT[j] = t
            pos = p + w.length
        }
        // 没匹配到的字符（句首/句尾零星字）：用句首或句尾时间戳兜底
        for (i in text.indices) {
            val t = if (charT[i] >= 0) charT[i]
            else if (i < text.length / 2) absMs.first() else absMs.last()
            if (volAt(volDb, volStartMs, volIntervalMs, t) < threshold) out[i] = true
        }
        return out
    }

    /** 在时间轴上取某时刻附近 ±50ms 的 dB 平均；窗口内无样本则取最近样本 */
    private fun volAt(volDb: List<Int>, startMs: Long, intervalMs: Long, tMs: Long): Int {
        if (volDb.isEmpty()) return 60
        val lo = tMs - 50; val hi = tMs + 50
        var sum = 0; var cnt = 0
        for (i in volDb.indices) {
            val tt = startMs + i * intervalMs
            if (tt in lo..hi) { sum += volDb[i]; cnt++ }
        }
        if (cnt == 0) {
            var best = volDb[0]; var bd = Long.MAX_VALUE
            for (i in volDb.indices) {
                val d = kotlin.math.abs(startMs + i * intervalMs - tMs)
                if (d < bd) { bd = d; best = volDb[i] }
            }
            return best
        }
        return sum / cnt
    }
}
