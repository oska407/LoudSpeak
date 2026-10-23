package com.oska.loudspeak.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * 分贝卡（§6.3.3）：大数字主视觉 + 底部强度细条。L ≥ 阈值亮色、L < 阈值红色（用平滑后的 ema 判色）。
 *
 * 演变史（别再翻烧饼）：
 *  - R12：数字从左侧挪到右侧、顶天立地，左侧 dB/正常偏小。
 *  - R13：用户要求「数字下方不要有分贝动态条」→ 四种条形态全删。
 *  - 2026-10-23：用户要求 ①「dB 单位写在数字右侧」②「恢复强度横条，但不挤占计数的位置」。
 *    → 条画在**数字字底空隙**（数字无下延部，那一圈本来就是空的），数字大小/位置一律不动；
 *    「dB」从左侧挪到数字右侧，与数字同一基线；左侧只剩「正常/偏小」判定。
 *
 * 皮肤仍影响观感（背景、扫描线、字体），条只有一种形态（24 段 + 阈值刻线）。
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

        // ---- 单位 dB 量宽（画在数字右侧、同一基线）----
        labelPaint.typeface = textPaint.typeface
        labelPaint.textSize = h * 0.17f
        labelPaint.color = color
        val dbTxt = "dB"
        val dbW = labelPaint.measureText(dbTxt)
        val gap = 4f * d

        // ---- 数字：贴右（dB 左边）、按字高垂直居中（R12 起不变）----
        val txt = "%.0f".format(displayL)
        textPaint.textAlign = Paint.Align.RIGHT
        val numRight = w - pad - dbW - gap
        val maxW = (numRight - pad).coerceAtLeast(1f)
        var numSize = h * (if (style == Skin.DB_MINIMAL) 0.78f else 0.92f)
        textPaint.textSize = numSize
        val tw = textPaint.measureText(txt)
        if (tw > maxW) {
            numSize *= maxW / tw
            textPaint.textSize = numSize
        }
        val capH = numSize * 0.72f
        val baseline = (h + capH) / 2f
        c.drawText(txt, numRight, baseline, textPaint)
        // dB 与数字同一基线（2026-10-23 用户要求：单位在计数右侧）
        c.drawText(dbTxt, w - pad, baseline, labelPaint)

        // ---- 左侧：判定（跟着阈值一起变色）----
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.textSize = h * 0.16f
        c.drawText(if (ok) "正常" else "偏小", pad, h * 0.52f, labelPaint)

        // ---- 底部强度细条（2026-10-23 恢复）----
        // 数字无下延部，字底到卡底本来就是空的 —— 条画在这里，不缩数字、不挤占计数。
        drawLevelBar(c, w, h, d, pad, color)
    }

    /** 24 段细条 + 阈值刻线：亮到 displayL/60，亮段颜色跟判定同色，暗段留极淡的影子 */
    private fun drawLevelBar(c: Canvas, w: Float, h: Float, d: Float, pad: Float, color: Int) {
        val barH = 4f * d
        val barBottom = 3.5f * d
        val top = h - barBottom - barH
        val span = w - pad * 2
        val n = 24
        val segGap = 2f * d
        val segW = (span - segGap * (n - 1)) / n
        val lit = (displayL.coerceIn(0f, 60f) / 60f * n).toInt()
        val dim = (color and 0x00FFFFFF) or 0x26000000
        paint.strokeWidth = 0f
        for (i in 0 until n) {
            val x0 = pad + i * (segW + segGap)
            paint.color = if (i < lit) color else dim
            c.drawRoundRect(x0, top, x0 + segW, top + barH, 1.5f * d, 1.5f * d, paint)
        }
        // 阈值刻线：亮白细线压在对应段上，一眼看出「过线才算响」
        val thX = pad + (threshold.coerceIn(0, 60) / 60f) * span
        paint.color = 0xB3FFFFFF.toInt()
        c.drawRect(thX - 1f * d, top - 1.5f * d, thX + 1f * d, top + barH + 1.5f * d, paint)
    }
}
