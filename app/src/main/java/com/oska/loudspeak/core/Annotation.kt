package com.oska.loudspeak.core

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan

/**
 * 识别文字标注体系（§R9）：利用模型/规则自动标注，**不用提示词**，色彩 + 符号双编码，且不复杂。
 *
 * 四类标注（与 RuleEngine 的输出一一对应）：
 *  - 代词（这个/那个/它…）：红色字 + 红色实线下划线。下划线即「符号」，提示「这里要换成具体名字」。
 *  - 口头禅（嗯/啊/呃…）：灰蓝字 + 下划线。下划线即「符号」，提示「能省就省」。
 *    （2026-10-23 用户要求：横划线不要穿过文字，统一移到文字下方 —— 删除线改下划线，
 *    靠颜色（灰 vs 红）区分口头禅与代词。）
 *  - 识别不清：灰色「?」字。「?」本身就是符号（来自 ASR 的 uncertain，不是我们强加的标注）。
 *  - 句式问题（缺主体/半截句/要素缺失）：橙色「⚠ 标签」。⚠ 即符号。
 *
 * 全部开关在设置里可逐项关；主开关关掉则只显示纯文字 + 必要的「?」。
 */
object Annotator {

    // 统一审美配色（深色界面上的高对比，避免刺眼）
    val C_PRONOUN = 0xFFFF5252.toInt()   // 代词：警示红
    val C_FILLER  = 0xFF7C8B96.toInt()   // 口头禅：灰蓝（弱化）
    val C_UNCLEAR = 0xFF6E7B84.toInt()   // 不清：中性灰
    val C_ISSUE   = 0xFFFFB74D.toInt()   // 句式问题：琥珀橙
    val C_MUZZY   = 0xFFE8A33D.toInt()   // 模糊词（L2）：暗金（比问题橙弱一档，不抢眼）
    val C_SELF    = 0xFF6E7B84.toInt()   // 自评：中性灰
    val C_PUNCT   = 0xFF7A8792.toInt()   // 补上的句末标点：淡灰，别人一眼看不出是我们加的

    // 「说得顺不顺」（R12）单独一套颜色：用青蓝，避开代词红 / 句式橙，别跟现有标注打架
    val C_FLUENCY_HEAD = 0xFF546E7A.toInt()   // 小标题：跟章节分隔线一个调子
    val C_FLUENCY      = 0xFF9FB3BF.toInt()   // 正文：淡
    val C_FLUENCY_NUM  = 0xFF7FD3E8.toInt()   // 停顿时长：稍微亮一点，扫一眼就看见
    val C_FLUENCY_DIM  = 0xFF637985.toInt()   // 引述的句子原文：压暗

    /** 生成标注图例（设置页「标注图例」按钮用） */
    fun legend(): String = buildString {
        append("识别标注图例（自动，不用提示词）：\n\n")
        append("🔴 红字 + 下划线 ＝ 代词（这个/那个/它…）\n")
        append("    要改成具体名字，比如「这个三角形」→「红色的三角形」\n\n")
        append("🩶 灰字 + 下划线 ＝ 口头禅（嗯/啊/呃…）\n")
        append("    能省就省，让话更干净\n\n")
        append("🟠 暗金 + 下划线 ＝ 模糊词（东西/玩意儿…，第 9 次会话后才开）\n")
        append("    它等于没说，换成具体名字\n\n")
        append("❓ 灰色「?」＝ 没听清（识别不到）\n\n")
        append("⚠ 橙色「⚠ 标签」＝ 句子不完整（缺主体/半截句/要素缺失）\n\n")
        append("灰色「〔已自评〕」＝ 你自己标了「这句我没说清楚」")
    }

    /**
     * 把句子序列渲染成带标注的富文本。
     * @param chapters 当前已画到的章节数（每 60 秒一段，决定分隔行）
     * @param fluency 「说得顺不顺」报告（R12）；非 null 时接在所有识别文字**最后**。
     *                事后才生成，实时不打扰 —— 孩子正在组织思路时蹦一个「停了 2.4 秒」
     *                出来，那是打断不是提示。
     */
    fun build(
        sentences: List<RuleEngine.Sentence>,
        chapters: Int,
        prefs: AnnotationPrefs,
        fluency: Fluency.Report? = null
    ): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        if (!prefs.enabled) {
            // 主开关关：纯文字（仍保留 ? 表示不清，因为那是识别结果而非我们标上去的）
            var lastChapter = 0
            sentences.forEachIndexed { i, s ->
                while (lastChapter < chapters && lastChapter * 60000 < s.tEndMs) {
                    lastChapter++; appendSep(sb, lastChapter)
                }
                sb.append(if (s.uncertain) "?" else s.displayText)
                // 并回上一句的碎片直接接着写，不留空格
                val nextMerged = sentences.getOrNull(i + 1)?.mergePrev == true
                if (!nextMerged) sb.append(" ")
            }
            if (sb.isEmpty()) sb.append("（点「录音」开始练习）")
            // 流畅度报告独立于标注主开关：它是一段说明文字，不是给字加的标记
            if (fluency != null) appendFluency(sb, fluency)
            return sb
        }
        var lastChapter = 0
        sentences.forEachIndexed { i, s ->
            while (lastChapter < chapters && lastChapter * 60000 < s.tEndMs) {
                lastChapter++; appendSep(sb, lastChapter)
            }
            if (s.uncertain) {
                val st = sb.length
                sb.append("?")
                sb.setSpan(ForegroundColorSpan(C_UNCLEAR), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                // 代词 / 口头禅 / 模糊词 按位置合并，避免重叠互相覆盖
                // 代词命中用「设置里可编辑的词表」（prefs.pronounList），与终判 evaluate 同一套
                val pron = if (prefs.pronouns) RuleEngine.matchPronouns(s.text, prefs.pronounList) else emptyList()
                val fill = if (prefs.fillers) RuleEngine.matchFillers(s.text) else emptyList()
                val muzz = if (prefs.muzzy) RuleEngine.matchMuzzy(s.text) else emptyList()
                val marks = (pron.map { Mark(it.first, it.second, Mark.PRONOUN) } +
                        fill.map { Mark(it.first, it.second, Mark.FILLER) } +
                        muzz.map { Mark(it.first, it.second, Mark.MUZZY) })
                    .sortedBy { it.pos }
                var idx = 0
                for (m in marks) {
                    if (m.pos > idx) sb.append(s.text.substring(idx, m.pos))
                    val st = sb.length
                    sb.append(m.word)
                    when (m.kind) {
                        Mark.PRONOUN -> {
                            sb.setSpan(ForegroundColorSpan(C_PRONOUN), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        Mark.MUZZY -> {
                            sb.setSpan(ForegroundColorSpan(C_MUZZY), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        else -> {
                            sb.setSpan(ForegroundColorSpan(C_FILLER), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            // 2026-10-23 用户要求：删除线（穿文字）改下划线（文字下方）
                            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    idx = m.pos + m.word.length
                }
                if (idx < s.text.length) sb.append(s.text.substring(idx))
                // 补上的句末标点（Punctuator），淡一点，别跟正文抢注意力
                if (s.punct.isNotEmpty()) {
                    val ps = sb.length
                    sb.append(s.punct)
                    sb.setSpan(ForegroundColorSpan(C_PUNCT), ps, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (s.selfMarked) {
                val ps = sb.length
                sb.append("〔已自评〕")
                sb.setSpan(ForegroundColorSpan(C_SELF), ps, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (prefs.issues && s.flags.isNotEmpty()) {
                val ps = sb.length
                sb.append(" ⚠${s.flags.joinToString("·")}")
                sb.setSpan(ForegroundColorSpan(C_ISSUE), ps, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val nextMerged = sentences.getOrNull(i + 1)?.mergePrev == true
            if (!nextMerged) sb.append(" ")
        }
        if (sb.isEmpty()) sb.append("（点「录音」开始练习）")
        if (fluency != null) appendFluency(sb, fluency)
        return sb
    }

    /** 把 R12 报告按行接在最后，每行一个颜色 */
    private fun appendFluency(sb: SpannableStringBuilder, r: Fluency.Report) {
        for ((line, color) in Fluency.lines(r)) {
            val st = sb.length
            sb.append(line)
            sb.setSpan(ForegroundColorSpan(color), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 改进摘要：一句话点出本场要改的地方，配合诊断一起看，能很快定位问题 */
    fun summary(sentences: List<RuleEngine.Sentence>): String {
        val pron = sentences.sumOf { it.pronouns.size }
        val fill = sentences.sumOf { it.fillers.size }
        val muzz = sentences.sumOf { it.muzzy.size }
        val incomplete = sentences.count { it.isProblem && it.pronouns.isEmpty() }
        val parts = mutableListOf<String>()
        if (pron > 0) parts.add("代词 $pron 处")
        if (muzz > 0) parts.add("模糊词 $muzz 处")
        if (fill > 0) parts.add("口头禅 $fill 处")
        if (incomplete > 0) parts.add("不完整句 $incomplete 句")
        return if (parts.isEmpty()) "这段讲得很清楚，没有要改的地方。"
        else "本场要改：${parts.joinToString("，")}。"
    }

    private class Mark(val word: String, val pos: Int, val kind: Int) {
        companion object { const val PRONOUN = 0; const val FILLER = 1; const val MUZZY = 2 }
    }

    private fun appendSep(sb: SpannableStringBuilder, chapter: Int) {
        val st = sb.length
        sb.append("\n──── ").append(chapter.toString()).append(":00 ────\n")
        sb.setSpan(ForegroundColorSpan(0xFF37474F.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** 标注开关集合（来自设置） */
data class AnnotationPrefs(
    val enabled: Boolean,
    val pronouns: Boolean,
    val fillers: Boolean,
    val issues: Boolean,
    /** 模糊词（东西/玩意儿…）：L2，默认关闭，第 9 次会话起才开 */
    val muzzy: Boolean = false,
    /** 代词词表（可编辑；"这个*" 这类带 * 的会把整段「这个X」标红）。默认见 Prefs.DEFAULT_PRONOUNS */
    val pronounList: List<String> = RuleEngine.PRONOUNS
)
