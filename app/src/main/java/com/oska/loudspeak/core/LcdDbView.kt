package com.oska.loudspeak.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View

/**
 * 分贝卡（§6.3.3）：深色液晶底 + 扫描线 + 24 段条 + 阈值刻线 + **顶天立地的大数字**；
 * L ≥ 阈值 绿 #4ade80，L < 阈值 红 #ff6b5e（用平滑后的 ema 判色）。
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
        val w = v.width.toFloat(); val h = v.height.toFloat()
        val d = v.resources.displayMetrics.density
        val pad = 10f * d

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

        // ---- 数字：顶天立地 ----
        val txt = "%.0f".format(displayL)
        val maxW = (w * 0.60f).coerceAtLeast(1f)     // 右侧 40% 留给 dB / 正常
        var numSize = h * 0.82f
        textPaint.textSize = numSize
        val tw = textPaint.measureText(txt)
        if (tw > maxW) {
            numSize *= maxW / tw
            textPaint.textSize = numSize
        }
        // 基线压到 0.80h：数字顶到上沿、底边留给分段条
        c.drawText(txt, pad, h * 0.80f, textPaint)

        // ---- 右上：单位 + 判定 ----
        labelPaint.textSize = h * 0.15f
        labelPaint.color = color
        c.drawText("dB", w - pad, h * 0.30f, labelPaint)
        labelPaint.textSize = h * 0.14f
        labelPaint.color = if (ok) 0xFF4ADE80.toInt() else 0xFFFF6B5E.toInt()
        c.drawText(if (ok) "正常" else "偏小", w - pad, h * 0.50f, labelPaint)

        // ---- 24 段条（底部一条，通栏）----
        val segCount = 24
        val barTop = h * 0.84f
        val barH = (h * 0.11f).coerceAtLeast(3f * d)
        val availW = w - pad * 2
        val gap = 2.5f * d
        val segW = ((availW - gap * (segCount - 1)) / segCount).coerceAtLeast(1f)
        val lit = (displayL / 60f * segCount).toInt().coerceIn(0, segCount)
        for (i in 0 until segCount) {
            val x = pad + i * (segW + gap)
            paint.color = if (i < lit) color else 0xFF1E2C24.toInt()
            c.drawRect(x, barTop, x + segW, barTop + barH, paint)
        }
        // 阈值刻线
        val tx = pad + (threshold / 60f) * availW
        paint.color = 0xFFFFD54F.toInt()
        c.drawRect(tx - 1.5f * d, barTop - 6f * d, tx + 1.5f * d, barTop + barH + 4f * d, paint)
    }
}
