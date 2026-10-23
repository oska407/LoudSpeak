package com.oska.loudspeak.core

import java.util.Locale

/**
 * 「说得顺不顺」—— SenseVoice 字级时间戳的三个副产品（R12）。
 *
 * 背景：这是**只有模型能给**的能力，**规则永远做不到**。
 * [VadSegmenter] 靠全局静音切句，只能看见「句与句之间」的停顿；
 * 而「想到了不知道怎么说」恰恰发生在**句子内部**。
 * 有了 `OfflineRecognizerResult.timestamps`（每个字的起点，单位秒），
 * 就能把一句内部的停顿位置精确到第几个字。
 *
 * 三个指标，来自同一个数组 `absMs`（**会话绝对毫秒**，见 [AsrResult.absMs]）：
 *  ① 开口前想了多久 = absMs[0] - 本句开始
 *  ② 句内卡顿       = 相邻两个字的时间差超过阈值
 *  ③ 语速           = 字数 / 说话时长，**说话时长按首尾字跨度算**
 *
 * ⚠️ 三个必须记住的点：
 *  1. **语速分母不是 VAD 窗口**。VAD 要攒够 800ms 静音才收句，尾部静音被算进「这一句的时长」了，
 *     按 tEndMs-tStartMs 算会系统性低估语速（成人样例实测 3.43 → 4.7 字/秒，差三成）。
 *     这里一律用 `最后一个字 - 第一个字`。
 *  2. **卡顿必须按「逻辑句」算**。若某句被 [Punctuator] 判定为碎片并回了上一句
 *     （`mergePrev=true`），它前面那次停顿恰恰是最值得看的卡壳 —— VAD 说「这不算句号」，
 *     那它就是句内停顿。按原始 VAD 句算会整片漏掉。
 *  3. **这是模型推测的对齐，不是真值**。字认错了，位置跟着错。所以阈值宁松勿紧，
 *     并且——按项目约定——这里只做**标注/线索**，绝不给孩子打分。
 *
 * 独立开关，不进 L1–L5 分级（§8.6）：流畅度是「怎么说」，和「说得完整不完整」是两个维度，
 * 混在一起分级表会变得没法解释。
 */
object Fluency {

    /** 卡顿阈值默认值（ms）。必须 > VAD 静音阈值（800）才不会互相打架。 */
    const val DEFAULT_GAP_MS = 1200
    /** 报告里列出「停得最久」的几处 */
    const val DEFAULT_TOP_N = 3

    /** 一次「卡住」：在 [word] 这个字之后停了 [gapMs] 毫秒 */
    class Gap(val atMs: Long, val gapMs: Long, val word: String, val context: String)

    class Report(
        /** 逻辑句数（碎片并回上一句后） */
        val nSentence: Int,
        /** 总字数（不含空白） */
        val nWord: Int,
        /** 纯说话时长（首尾字跨度累计，不含前后静音） */
        val speakMs: Long,
        /** 语速：字/秒 */
        val cps: Float,
        /** 开口前想最久的一次（毫秒） */
        val maxLeadMs: Long,
        val maxLeadText: String,
        /** 停得最久的几处（按时长降序） */
        val gaps: List<Gap>,
        /** 超过阈值但没进榜的还有多少处 */
        val nGapTotal: Int,
        /** 生成这份报告时用的阈值 */
        val gapMs: Int
    ) {
        val cpsText: String get() = String.format(Locale.US, "%.1f", cps)
    }

    /**
     * 按「逻辑句」分组：被并回上一句的碎片（mergePrev）跟着上一句走。
     * 卡顿要在这个粒度上重算，否则所有跨 VAD 句的卡壳全被漏掉（见类注释第 2 点）。
     */
    fun groupsOf(sentences: List<RuleEngine.Sentence>): List<List<RuleEngine.Sentence>> {
        val out = ArrayList<List<RuleEngine.Sentence>>()
        var cur = ArrayList<RuleEngine.Sentence>()
        for (s in sentences) {
            if (s.mergePrev && cur.isNotEmpty()) {
                cur.add(s)
            } else {
                if (cur.isNotEmpty()) out.add(cur)
                cur = ArrayList()
                cur.add(s)
            }
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    /**
     * 算一份报告。数据不足（一句没有时间戳）返回 null —— 调用方不显示这一段即可。
     * @param gapMs 卡顿阈值（毫秒）
     * @param topN 报告列出停得最久的几处
     */
    fun analyze(sentences: List<RuleEngine.Sentence>, gapMs: Int, topN: Int): Report? {
        val groups = groupsOf(sentences.filter { it.judgeable })
        if (groups.isEmpty()) return null

        var nWord = 0
        var speakMs = 0L
        var maxLead = 0L
        var maxLeadText = ""
        val gaps = ArrayList<Gap>()
        var nGapTotal = 0

        for (g in groups) {
            // 把这一组里所有句子的时间戳串成一条
            val times = ArrayList<Long>()
            val words = ArrayList<String>()
            val contextOf = ArrayList<String>()
            val eligible = ArrayList<Boolean>()   // 这个位置与前字之间的间隔要不要算「句内」
            for (si in g.indices) {
                val s = g[si]
                val ms = s.absMs
                val ws = s.absWords
                val n = minOf(ms.size, ws.size)
                if (n == 0) continue
                // ② 卡顿：只有「句内」或「被合并回上句的碎片的首字」才计数
                //    正常 VAD 句与句之间的停顿是自然停顿，不算卡壳
                for (k in 0 until n) {
                    times.add(ms[k])
                    words.add(ws[k])
                    contextOf.add(s.text)
                    eligible.add(k > 0 || (si > 0 && s.mergePrev))
                }
                nWord += s.text.count { !it.isWhitespace() }
            }
            if (times.isEmpty()) continue

            // ① 开口前想了多久
            val lead = times[0] - g[0].tStartMs
            if (lead > maxLead) { maxLead = lead; maxLeadText = g[0].text }

            // ③ 说话时长按首尾字跨度（不是 VAD 窗口！）
            val span = times[times.size - 1] - times[0]
            if (span > 0) speakMs += span

            // ② 句内卡顿
            for (i in 1 until times.size) {
                if (!eligible[i]) continue
                val d = times[i] - times[i - 1]
                if (d >= gapMs) {
                    nGapTotal++
                    gaps.add(Gap(times[i - 1], d, words[i - 1], contextOf[i - 1]))
                }
            }
        }

        if (speakMs <= 0) return null
        gaps.sortByDescending { it.gapMs }
        val cps = nWord * 1000f / speakMs
        return Report(
            nSentence = groups.size,
            nWord = nWord,
            speakMs = speakMs,
            cps = cps,
            maxLeadMs = maxLead.coerceAtLeast(0),
            maxLeadText = maxLeadText,
            gaps = gaps.take(topN),
            nGapTotal = nGapTotal,
            gapMs = gapMs
        )
    }

    /**
     * 报告渲染成「行文本 + 颜色」，交给 [Annotator] 接在识别文字最后。
     *
     * 文案刻意中性：只说「停了多久、停在哪」，不说「不好 / 太慢」——
     * 停顿多往往是在想，不是错。
     */
    fun lines(r: Report): List<Pair<String, Int>> {
        val L = ArrayList<Pair<String, Int>>()
        L.add("\n\n── 说得顺不顺 ──\n" to Annotator.C_FLUENCY_HEAD)

        val secLead = r.maxLeadMs / 1000f
        L.add("讲了 ${r.nSentence} 句、${r.nWord} 个字，平均每秒 ${r.cpsText} 个字\n" to Annotator.C_FLUENCY)
        if (r.maxLeadMs >= 1500) {
            L.add("开口前想最久的一次：${String.format(Locale.US, "%.1f", secLead)} 秒\n" to Annotator.C_FLUENCY)
        }
        if (r.gaps.isEmpty()) {
            L.add("没有超过 ${r.gapMs / 1000f} 秒的停顿。\n" to Annotator.C_FLUENCY)
        } else {
            val head = "停得最久的 ${r.gaps.size} 处"
            val rest = if (r.nGapTotal > r.gaps.size) "（一共 ${r.nGapTotal} 处，只列最久的）" else ""
            L.add("$head$rest：\n" to Annotator.C_FLUENCY)
            for (i in r.gaps.indices) {
                val gp = r.gaps[i]
                L.add("  ${i + 1}) 停了 ${String.format(Locale.US, "%.1f", gp.gapMs / 1000f)} 秒，在「${gp.word}」字后\n" to Annotator.C_FLUENCY_NUM)
                L.add("     ${clip(gp.context)}\n" to Annotator.C_FLUENCY_DIM)
            }
            L.add("停顿常常是在想，不一定是没想好。\n" to Annotator.C_FLUENCY_DIM)
        }
        return L
    }

    /** 上下文太长会挤成一片，砍一刀（不改原文的含义，只是这里不显示全部） */
    private fun clip(t: String): String {
        val s = t.trim()
        return if (s.length <= 24) s else s.substring(0, 24) + "…"
    }
}
