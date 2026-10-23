package com.oska.loudspeak.core

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * 分贝卡（§6.3.3）：数字**顶天立地**，L ≥ 阈值亮色、L < 阈值红色（用平滑后的 ema 判色）。
 *
 * R11 起跟皮肤走（设置页「外观」可切），四种形态**故意做得不一样**，换皮肤时不只是一层色：
 *  - BARS  经典：深色液晶底 + 扫描线 + 24 段粗条（原来那套）
 *  - MINIMAL 纸张：去掉条与扫描线，只留大数字 + 一条细刻度尺（衬线字，安静）
 *  - LED   终端：40 段细 LED 点阵 + 更重的扫描线（等宽字，机器味）
 *  - GLOW  霓虹：渐变发光条 + 顶部高光（青色发光）
 *
 * 数字是第 1 行唯一要「一眼看到」的东西，所以按卡片高度的 80% 起排，
 * 宽度不够才等比缩（右侧给 dB / 正常-偏小 留一条窄栏目）。
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

        // ---- 数字：顶天立地 ----
        val txt = "%.0f".format(displayL)
        val rightCol = if (style == Skin.DB_MINIMAL) 0.70f else 0.60f
        val maxW = (w * rightCol).coerceAtLeast(1f)
        var numSize = h * (if (style == Skin.DB_MINIMAL) 0.70f else 0.82f)
        textPaint.textSize = numSize
        val tw = textPaint.measureText(txt)
        if (tw > maxW) {
            numSize *= maxW / tw
            textPaint.textSize = numSize
        }
        val baseline = if (style == Skin.DB_MINIMAL) h * 0.72f else h * 0.80f
        c.drawText(txt, pad, baseline, textPaint)

        // ---- 右上：单位 + 判定 ----
        labelPaint.typeface = textPaint.typeface
        labelPaint.textSize = h * 0.15f
        labelPaint.color = color
        c.drawText("dB", w - pad, h * 0.30f, labelPaint)
        labelPaint.textSize = h * 0.14f
        c.drawText(if (ok) "正常" else "偏小", w - pad, h * 0.50f, labelPaint)

        val availW = w - pad * 2
        when (style) {
            Skin.DB_MINIMAL -> {
                // 只有一条细刻度尺：太粗会抢走数字的注意力
                val y = h * 0.88f
                paint.color = p.lcdOff
                c.drawRect(pad, y, pad + availW, y + 1.5f * d, paint)
                paint.color = color
                c.drawRect(pad, y, pad + availW * (displayL / 60f).coerceIn(0f, 1f), y + 1.5f * d, paint)
                val tx = pad + (threshold / 60f) * availW
                paint.color = 0xFFFFD54F.toInt()
                c.drawRect(tx - 1f * d, y - 4f * d, tx + 1f * d, y + 5f * d, paint)
            }
            Skin.DB_LED -> {
                // 40 段细 LED
                val segCount = 40
                val barTop = h * 0.84f
                val barH = (h * 0.11f).coerceAtLeast(3f * d)
                val gap = 1.2f * d
                val segW = ((availW - gap * (segCount - 1)) / segCount).coerceAtLeast(1f)
                val lit = (displayL / 60f * segCount).toInt().coerceIn(0, segCount)
                for (i in 0 until segCount) {
                    val x = pad + i * (segW + gap)
                    paint.color = if (i < lit) color else p.lcdOff
                    c.drawRect(x, barTop, x + segW, barTop + barH, paint)
                }
                val tx = pad + (threshold / 60f) * availW
                paint.color = 0xFFD7C85B.toInt()
                c.drawRect(tx - 1f * d, barTop - 5f * d, tx + 1f * d, barTop + barH + 4f * d, paint)
            }
            Skin.DB_GLOW -> {
                // 发光渐变条：亮的一段有从亮到透的渐变，像灯管
                val barTop = h * 0.84f
                val barH = (h * 0.11f).coerceAtLeast(4f * d)
                paint.color = p.lcdOff
                c.drawRoundRect(pad, barTop, pad + availW, barTop + barH, barH / 2, barH / 2, paint)
                val litW = availW * (displayL / 60f).coerceIn(0f, 1f)
                if (litW > 1f) {
                    paint.shader = LinearGradient(pad, 0f, pad + litW, 0f,
                        color, (color and 0x00FFFFFF) or 0x66000000, Shader.TileMode.CLAMP)
                    c.drawRoundRect(pad, barTop, pad + litW, barTop + barH, barH / 2, barH / 2, paint)
                    paint.shader = null
                }
                val tx = pad + (threshold / 60f) * availW
                paint.color = 0xFFFFD54F.toInt()
                c.drawRect(tx - 1f * d, barTop - 5f * d, tx + 1f * d, barTop + barH + 4f * d, paint)
            }
            else -> {
                // 24 段粗条（经典）
                val segCount = 24
                val barTop = h * 0.84f
                val barH = (h * 0.11f).coerceAtLeast(3f * d)
                val gap = 2.5f * d
                val segW = ((availW - gap * (segCount - 1)) / segCount).coerceAtLeast(1f)
                val lit = (displayL / 60f * segCount).toInt().coerceIn(0, segCount)
                for (i in 0 until segCount) {
                    val x = pad + i * (segW + gap)
                    paint.color = if (i < lit) color else p.lcdOff
                    c.drawRect(x, barTop, x + segW, barTop + barH, paint)
                }
                val tx = pad + (threshold / 60f) * availW
                paint.color = 0xFFFFD54F.toInt()
                c.drawRect(tx - 1.5f * d, barTop - 6f * d, tx + 1.5f * d, barTop + barH + 4f * d, paint)
            }
        }
    }
}
