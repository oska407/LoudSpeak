package com.oska.loudspeak.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * 分贝卡（§6.3.3）：深色液晶底 + 扫描线 + 24 段条 + 阈值刻线 + 大数字；
 * L ≥ 阈值 绿 #4ade80，L < 阈值 红 #ff6b5e（用平滑后的 ema 判色）。
 */
class LcdDbView(private val v: View) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4ADE80.toInt(); typeface = Typeface.MONOSPACE; textAlign = Paint.Align.RIGHT
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6E7B84.toInt(); textSize = 28f
    }
    var displayL = 0f
    var threshold = 30

    fun update(l: Float, threshold_: Int) {
        displayL = l
        threshold = threshold_
        v.invalidate()
    }

    fun onDraw(c: Canvas) {
        val w = v.width.toFloat(); val h = v.height.toFloat()
        // 深色液晶底
        paint.color = 0xFF0E1A12.toInt()
        c.drawRoundRect(0f, 0f, w, h, 16f, 16f, paint)
        // 扫描线
        paint.color = 0x12000000
        var y = 0f
        while (y < h) { c.drawRect(0f, y, w, y + 2f, paint); y += 8f }

        val ok = displayL >= threshold
        val color = if (ok) 0xFF4ADE80.toInt() else 0xFFFF6B5E.toInt()
        textPaint.color = color

        // 数字（占上半区，右对齐）
        textPaint.textSize = h * 0.42f
        c.drawText("%.0f".format(displayL), w - 24f, h * 0.46f, textPaint)
        // 单位
        labelPaint.textSize = h * 0.14f
        labelPaint.color = color
        c.drawText("dB", w - 24f, h * 0.64f, labelPaint)
        labelPaint.color = 0xFF6E7B84.toInt()
        c.drawText(if (ok) "正常" else "偏小", w - 24f, h * 0.86f, labelPaint)

        // 24 段条（下半区）
        val segCount = 24
        val barTop = h * 0.68f
        val barH = h * 0.22f
        val leftMargin = 24f
        val rightMargin = 110f
        val availW = w - leftMargin - rightMargin
        val gap = 3f
        val segW = (availW - gap * (segCount - 1)) / segCount
        val lit = (displayL / 60f * segCount).toInt().coerceIn(0, segCount)
        for (i in 0 until segCount) {
            val x = leftMargin + i * (segW + gap)
            paint.color = if (i < lit) color else 0xFF1E2C24.toInt()
            c.drawRect(x, barTop, x + segW, barTop + barH, paint)
        }
        // 阈值刻线
        val tx = leftMargin + (threshold / 60f) * availW
        paint.color = 0xFFFFD54F.toInt()
        c.drawRect(tx - 1.5f, barTop - 10f, tx + 1.5f, barTop + barH + 10f, paint)
    }
}
