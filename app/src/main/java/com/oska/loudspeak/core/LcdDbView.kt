package com.oska.loudspeak.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * 分贝卡（§6.3.3）：只有**大数字**，L ≥ 阈值亮色、L < 阈值红色（用平滑后的 ema 判色）。
 *
 * 2026-10-23 用户要求「分贝数字下方不要有分贝动态条」→ 24 段条 / LED / 渐变条 / 细刻度尺全部删掉，
 * 数字改为**垂直居中**（按字高算基线，不再为条让位）。判定改由「数字 + dB + 正常/偏小」三者同色表达。
 *
 * 皮肤仍影响观感（背景、扫描线、字体），只是不再有四种条形态：
 *  - BARS 经典：深色液晶底 + 扫描线 + 等宽字
 *  - MINIMAL 纸张：无扫描线 + 衬线字（安静）
 *  - LED 终端：扫描线 + 等宽字（机器味）
 *  - GLOW 霓虹：扫描线 + 窄体粗字
 */
class LcdDbView(private val v: View) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4ADE80.toInt(); typeface = Typeface.MONOSPACE; textAlign = Paint.Align.LEFT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6E7B84.toInt(); textAlign = Paint.Align.RIGHT
    }
    var displayL = 0f
    var threshold = 30

    fun update(l: Float, threshold_: Int) {
        displayL = l
        threshold = threshold_
        v.invalidate()
    }

    fun onDraw(c: Canvas) {
        val p = Skin.current
        val style = p.dbStyle
        val w = v.width.toFloat(); val h = v.height.toFloat()
        val d = v.resources.displayMetrics.density
        val pad = 10f * d

        // ---- 底 ----
        paint.color = p.lcdBg
        c.drawRoundRect(0f, 0f, w, h, if (p.radiusDp > 12) 16f else 6f, 6f, paint)
        if (style == Skin.DB_BARS || style == Skin.DB_GLOW) {
            // 扫描线：终端那种更密更重
            paint.color = 0x12000000
            var y = 0f
            while (y < h) { c.drawRect(0f, y, w, y + 2f, paint); y += 8f }
        }

        val ok = displayL >= threshold
        val color = if (ok) p.lcdOn else 0xFFFF6B5E.toInt()
        textPaint.color = color
        textPaint.typeface = when (style) {
            Skin.DB_MINIMAL -> try { Typeface.create(p.font, Typeface.NORMAL) } catch (_: Exception) { Typeface.SERIF }
            Skin.DB_GLOW -> try { Typeface.create("sans-serif-condensed", Typeface.BOLD) } catch (_: Exception) { Typeface.MONOSPACE }
            else -> Typeface.MONOSPACE
        }

        // ---- 数字：放**右侧**、顶天立地（R12 用户要求）----
        // 原来数字在左、dB/正常 挤在右上角，红圈那块反而最显眼；现在把数字挪到右侧，
        // 让它贴着右边缘撑满整个高度，左侧留给单位与判定。
        val txt = "%.0f".format(displayL)
        textPaint.textAlign = Paint.Align.RIGHT
        val maxW = (w * 0.62f).coerceAtLeast(1f)
        var numSize = h * (if (style == Skin.DB_MINIMAL) 0.78f else 0.92f)
        textPaint.textSize = numSize
        val tw = textPaint.measureText(txt)
        if (tw > maxW) {
            numSize *= maxW / tw
            textPaint.textSize = numSize
        }
        // 条删掉了，不再给条让位：按数字字高算基线，让数字在卡内**垂直居中**
        val capH = numSize * 0.72f
        val baseline = (h + capH) / 2f
        c.drawText(txt, w - pad, baseline, textPaint)

        // ---- 左侧：单位 + 判定（跟着阈值一起变色）----
        labelPaint.typeface = textPaint.typeface
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = h * 0.17f
        labelPaint.color = color
        c.drawText("dB", pad, h * 0.40f, labelPaint)
        labelPaint.textSize = h * 0.16f
        c.drawText(if (ok) "正常" else "偏小", pad, h * 0.64f, labelPaint)
    }
}
