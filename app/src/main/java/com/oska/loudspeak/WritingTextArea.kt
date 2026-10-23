package com.oska.loudspeak

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.abs

/**
 * 第 3 行「识别文字区」：支持三种焦点行为（v6 §6 修订）。
 *
 * 1. **连续书写**：识别文字按一句接一句连续排版（从左到右、自动换行），
 *    顶部对齐、逐行往下写。（2026-10-24 用户要求：去掉每行下面的横格线 ——
 *    原「作文格子纸」样式的 RuledText 格线已删，本类现在就是普通 TextView。）
 * 2. **自动滚动**：来了新句子就带着阻尼滑到最新一行（焦点跟着识别结果走）。
 * 3. **手动拖动**：手按上去就不自动动，随便翻；松手 2.5 秒不再看之后，
 *    用阻尼把这个区缓慢拉回「最新识别文字」的位置。
 *
 * ⚠️ 触摸只用 setOnTouchListener 监听，**不要**设 isClickable = true —— 过去在波形条上
 * 这么干过，整行的触摸事件被吞掉，踩了大坑。这里返回 false，让 ScrollView 正常处理滚动。
 */
class WritingTextArea @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    /** 手离开后多久开始回收焦点（毫秒） */
    var idleReturnMs: Long = 2500L
    /** 回收焦点时的阻尼：每帧只走剩余距离的百分之几，越小越"粘" */
    var returnDamping: Float = 0.14f
    /** 跟随新文字时的阻尼：比回收快一些，否则新句子会被落在后面 */
    var followDamping: Float = 0.34f

    /** 内部真正显示文字的那个 View（带格线） */
    val text: RuledText

    private var dragging = false
    private var lastTouchMs = 0L
    private var following = false

    private val density = context.resources.displayMetrics.density
    private fun px(dp: Int) = (dp * density).toInt()

    // ⚠️ 必须写成 object：写成 Runnable { postDelayed(this, …) } 时 lambda 里的 this 是
    // 外层 WritingTextArea（SAM 转换不引入新的 this），编译期直接报类型不匹配。
    private val ticker = object : Runnable {
        override fun run() {
            step()
            postDelayed(this, 60L)
        }
    }

    init {
        isVerticalScrollBarEnabled = true
        isFillViewport = true
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    following = false
                    lastTouchMs = System.currentTimeMillis()
                }
                MotionEvent.ACTION_MOVE -> lastTouchMs = System.currentTimeMillis()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    lastTouchMs = System.currentTimeMillis()
                }
            }
            false   // 不消费，交给 ScrollView 正常滚动 + fling
        }
        val padL = px(10)
        val padT = px(8)
        val padR = px(14)
        val padB = px(18)
        text = RuledText(context).apply {
            setTextColor(0xFFDCE3E8.toInt())
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)   // 22sp → 18sp（小两号）
            includeFontPadding = false
            setLineSpacing(8f * density, 1.0f)   // TextView 没有 lineSpacingExtra 的 setter
            isSingleLine = false
            setPadding(padL, padT, padR, padB)
        }
        addView(text, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applySkin()
    }

    /**
     * 换皮肤后重新上色（R11）。
     * 文字区是自绘控件，走不到 Skin.recolor 的「认色」逻辑，得自己取一次调色板：
     * 否则浅色皮肤下会出现「白字压白纸」，字全看不见。
     */
    fun applySkin() {
        val p = com.oska.loudspeak.core.Skin.current
        text.setTextColor(p.text)
        text.typeface = try {
            android.graphics.Typeface.create(p.font, android.graphics.Typeface.NORMAL)
        } catch (_: Exception) { text.typeface }
        // 横格线已按用户要求去掉（2026-10-24）；这里只刷字色与字体
        text.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        postDelayed(ticker, 120L)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    /** 设置识别文字；若此刻没在手拨，立刻带着阻尼跟上最新一行 */
    fun setContent(c: CharSequence) {
        text.text = c
        following = true
        post { step() }
    }

    /** 清空并回到顶格（开新会话 / 清屏用） */
    fun reset() {
        following = false
        lastTouchMs = 0L
        text.text = ""
        scrollTo(0, 0)
    }

    /** 最新一行的位置 = 可滚动到底的位置 */
    private fun targetY(): Int {
        val child = getChildAt(0) ?: return 0
        return (child.height - height).coerceAtLeast(0)
    }

    private fun step() {
        if (dragging) return
        val d = targetY() - scrollY
        if (d == 0) { following = false; return }
        if (!following && System.currentTimeMillis() - lastTouchMs < idleReturnMs) return

        val k = if (following) followDamping else returnDamping
        val move = if (abs(d) <= 2) d else {
            var v = (d * k).toInt()
            if (v == 0) v = if (d > 0) 1 else -1   // 距离小于 1/k 时别卡住不动
            v
        }
        scrollBy(0, move)
    }
}

/**
 * 识别文字的正文 TextView。
 * 曾经画「作文格线」（每行文字下方一条横线），2026-10-24 按用户要求整条删掉；
 * 现在没有自绘内容，保留这个类名只是少动一处调用。
 */
class RuledText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr)
