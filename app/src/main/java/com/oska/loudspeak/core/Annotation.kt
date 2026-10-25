package com.oska.loudspeak.core

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView

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

    /** L4 展开状态：存句子 index。点一下句末的「→」展开改写，再点收起。 */
    private val expanded = HashSet<Int>()

    /** 由界面设置：点「→」之后重建整段文本（MainActivity = 自己的 renderAll）。 */
    var onRewriteToggle: (() -> Unit)? = null

    /**
     * 只有开了 L4（文本里才有可点的「→」）才挂 LinkMovementMethod，
     * 没开时保持 TextView 原样 —— 零副作用，不碰别的手势。
     */
    fun attachInteraction(tv: TextView, prefs: AnnotationPrefs) {
        tv.movementMethod = if (prefs.enabled && prefs.l4) LinkMovementMethod.getInstance() else null
    }

    /** 给 [st, sb.length) 上色，并把字号压到标注字号（设置里 8..16sp，默认 10）。 */
    private fun paint(sb: SpannableStringBuilder, st: Int, color: Int, prefs: AnnotationPrefs) {
        sb.setSpan(ForegroundColorSpan(color), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(AbsoluteSizeSpan(prefs.fontSizeSp, true), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // 统一审美配色（深色界面上的高对比，避免刺眼）
    val C_PRONOUN = 0xFFFF5252.toInt()   // 代词：警示红
    val C_FILLER  = 0xFF7C8B96.toInt()   // 口头禅：灰蓝（弱化）
    val C_UNCLEAR = 0xFF6E7B84.toInt()   // 不清：中性灰
    val C_ISSUE   = 0xFFFFB74D.toInt()   // 句式问题：琥珀橙
    val C_MUZZY   = 0xFFE8A33D.toInt()   // 模糊词（L2）：暗金（比问题橙弱一档，不抢眼）
    val C_SELF    = 0xFF6E7B84.toInt()   // 自评：中性灰
    val C_PUNCT   = 0xFF7A8792.toInt()   // 补上的句末标点：淡灰，别人一眼看不出是我们加的
    val C_L2     = 0xFFF5A623.toInt()   // L2 不完整句：橙
    val C_L3     = 0xFFB8860B.toInt()   // L3 指代歧义：暗金（比 L2 橙弱一档，不抢眼）
    val C_L4     = 0xFF2DA8A0.toInt()   // L4 更清楚的说法：蓝绿

    // 「说得顺不顺」（R12）单独一套颜色：用青蓝，避开代词红 / 句式橙，别跟现有标注打架
    val C_FLUENCY_HEAD = 0xFF546E7A.toInt()   // 小标题：跟章节分隔线一个调子
    val C_FLUENCY      = 0xFF9FB3BF.toInt()   // 正文：淡
    val C_FLUENCY_NUM  = 0xFF7FD3E8.toInt()   // 停顿时长：稍微亮一点，扫一眼就看见
    val C_FLUENCY_DIM  = 0xFF637985.toInt()   // 引述的句子原文：压暗

    /** 生成标注图例（设置页「标注图例」按钮用）。四层递进：符号 + 颜色说话，几乎不写字。 */
    fun legend(): String = buildString {
        append("识别标注图例（自动，不用提示词）：\n\n")
        append("① 🔴 红字 + 下划线 ＝ 你用了代词（这个/那些/这样…）\n")
        append("    把「这个」换成它的名字，比如「这个三角形」→「红色的三角形」\n\n")
        append("② 🟠 整句橙色下划线 + 句尾 ⚠ ＝ 这句话没说完 / 缺主体 / 缺要素\n")
        append("    把话补完整，先说清是谁、再说什么\n\n")
        append("③ 🟡 代词后面一个小「?」＝ 旁听的人猜不到你指的是哪个\n")
        append("    这处最该改：只有你自己知道「这个」是啥\n\n")
        append("④ 🟢 句尾一个「→」＝ 有一句更清楚的示范（点一下才展开）\n")
        append("    这是给参考，不是纠错\n\n")
        append("❓ 灰色「?」＝ 没听清（识别不到）\n")
        append("灰色「〔已自评〕」＝ 你自己标了「这句我没说清楚」\n\n")
        append("字形大小：设置 → 标注 → 标注字号，可调 8–16。")
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
                appendGap(sb, sentences.getOrNull(i + 1))
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
            val sentStart = sb.length
            if (s.uncertain) {
                val st = sb.length
                sb.append("?")
                paint(sb, st, C_UNCLEAR, prefs)
            } else {
                // ---- 四层：L1 代词 / L3 歧义 都要先在句内定位 ----
                val mp = s.modelPronouns ?: emptyList()
                val pronHits: List<Pair<String, Int>> = when {
                    !prefs.l1 -> emptyList()
                    // L1「用大模型」子开关：改用模型给的代词原文（App 端子串定位），替代本地词表
                    prefs.l1Model -> RuleEngine.locatePronouns(s.text, mp.map { it.word })
                    else -> RuleEngine.matchPronouns(s.text, prefs.pronounList)
                }
                val pronPos = HashSet<Int>()
                pronHits.forEach { pronPos.add(it.second) }
                // L3：旁听者猜不到指什么的代词。已被 L1 标红的只挂一个 ?，不再另起一处标记
                val ambAll = if (prefs.l3) RuleEngine.locatePronouns(
                    s.text, mp.filter { it.ambiguous }.map { it.word }) else emptyList()
                val ambPos = HashSet<Int>()
                ambAll.forEach { ambPos.add(it.second) }
                val ambOnly = ambAll.filter { it.second !in pronPos }
                val fill = if (prefs.fillers) RuleEngine.matchFillers(s.text) else emptyList()
                val muzz = if (prefs.muzzy) RuleEngine.matchMuzzy(s.text) else emptyList()
                val marks = (pronHits.map { Mark(it.first, it.second, Mark.PRONOUN) } +
                        ambOnly.map { Mark(it.first, it.second, Mark.AMBIG) } +
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
                            paint(sb, st, C_PRONOUN, prefs)
                            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        Mark.AMBIG -> paint(sb, st, C_L3, prefs)
                        Mark.MUZZY -> {
                            paint(sb, st, C_MUZZY, prefs)
                            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        else -> {
                            paint(sb, st, C_FILLER, prefs)
                            // 2026-10-23 用户要求：删除线（穿文字）改下划线（文字下方）
                            sb.setSpan(UnderlineSpan(), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    idx = m.pos + m.word.length
                    // L3 符号：歧义代词后面挂一个暗金「?」，不写字、不解释
                    if ((m.kind == Mark.PRONOUN || m.kind == Mark.AMBIG) && m.pos in ambPos) {
                        val qs = sb.length
                        sb.append("?")
                        paint(sb, qs, C_L3, prefs)
                    }
                }
                if (idx < s.text.length) sb.append(s.text.substring(idx))
                // 补上的句末标点（Punctuator），淡一点，别跟正文抢注意力
                if (s.punct.isNotEmpty()) {
                    val ps = sb.length
                    sb.append(s.punct)
                    paint(sb, ps, C_PUNCT, prefs)
                }
            }
            if (s.selfMarked) {
                val ps = sb.length
                sb.append("〔已自评〕")
                paint(sb, ps, C_SELF, prefs)
            }
            // L2 不完整句：整句橙下划线 + 句尾一个 ⚠。不写字（要少文字，用线条和颜色说话）
            if (prefs.l2 && s.flags.isNotEmpty()) {
                if (sentStart < sb.length) {
                    sb.setSpan(UnderlineSpan(), sentStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val ps = sb.length
                sb.append(" ⚠")
                paint(sb, ps, C_L2, prefs)
            }
            // L4 更清楚的说法：句末一个蓝绿「→」，点一下才展开改写小字（默认折叠，不占版面）
            val rw = s.rewrite
            if (prefs.l4 && !rw.isNullOrBlank()) {
                val ps = sb.length
                sb.append(" →")
                paint(sb, ps, C_L4, prefs)
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        if (!expanded.add(s.index)) expanded.remove(s.index)
                        onRewriteToggle?.invoke()
                    }
                }, ps, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (s.index in expanded) {
                    val rs = sb.length
                    sb.append(rw)
                    paint(sb, rs, C_L4, prefs)
                }
            }
            appendGap(sb, sentences.getOrNull(i + 1))
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
        companion object { const val PRONOUN = 0; const val FILLER = 1; const val MUZZY = 2; const val AMBIG = 3 }
    }

    /**
     * 句与句之间的连接（2026-10-24：识别文字要连续从左到右读）。
     * 默认垫一个空格；但下一句以标点开头（碎片并句 / 只剩标点的空句）时不要垫，
     * 否则会出现「每天在干嘛？ ，如果说。」这种空格夹标点的断裂感。
     */
    private fun appendGap(sb: SpannableStringBuilder, next: RuleEngine.Sentence?) {
        if (next == null || next.mergePrev) return
        val c = next.displayText.trim().firstOrNull() ?: return
        if (c in "，。？！、；：…—·）】》」』\"',.:;?!") return
        sb.append(" ")
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
    /** 代词词表（可编辑；通配符 ? = 一个字、* = 多个字，如 `这?` 覆盖 这个/这些/这样…）。默认见 Prefs.DEFAULT_PRONOUNS */
    val pronounList: List<String> = RuleEngine.PRONOUNS,

    // ---- 四层标注（标注多层次设计 v1）：每层独立可关，见 settings_mark.xml ----
    /** L1 代词标红（红 + 下划线）。本地词表为默认；[l1Model] 为真时改用大模型返回的 pronouns */
    val l1: Boolean = true,
    /** L1 子开关：用大模型标代词，代替本地词表（需开 PC 判定） */
    val l1Model: Boolean = false,
    /** L2 不完整句（橙下划线 + 句尾 ⚠） */
    val l2: Boolean = true,
    /** L2 子开关：用大模型判不完整句（需开 PC 判定） */
    val l2Model: Boolean = false,
    /** L3 指代歧义（暗金 ?）—— 模型专属，旁听者猜不到指什么时才标 */
    val l3: Boolean = false,
    /** L4 更清楚的说法（蓝绿 →，点按才展开改写小字）—— 模型专属 */
    val l4: Boolean = false,
    /** 标注字号（sp）：作用于所有标注文字与符号，设置页可调 8..16，默认 10 */
    val fontSizeSp: Int = 10
)
