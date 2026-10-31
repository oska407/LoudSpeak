package com.oska.loudspeak.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 波形条（§6.3.1，v2）。
 * 录制中：滚动窗口，新样本从右侧进入向左推移；
 * 回放中：全程概览 + 播放头(黄) + 起点(蓝绿菱形) 双把手 + 句分隔虚线 + 时间气泡，
 * 拖动时**只更新把手与气泡、不真 seek**，松手才回调；长音频靠气泡看时间，不必放大。
 */
class WaveformView(v: View) {
    private val view = v
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2DA8A0.toInt() }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222831.toInt() }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 13f; textAlign = Paint.Align.CENTER }

    /** 滚动窗口样本（0..1），录制中用 */
    private val window = FloatArray(320)
    private var windowPos = 0

    /** 全程概览样本（回放用） */
    private var overview = FloatArray(0)
    private var totalFrames = 0L
    private var frameIndex = 0L

    /** 回放游标 0..1，<0 表示非回放 */
    var playhead = -1f
    /** 起点把手 0..1（A-B 循环起点） */
    var startFrac = 0f
    /** 句分隔位置（0..1 列表） */
    var sentenceFracs: List<Float> = emptyList()

    /** 播放头拖到某处 → frac（松手才真 seek） */
    var onSeek: ((Float) -> Unit)? = null
    /** 起点把手拖到某处 → frac */
    var onStartSeek: ((Float) -> Unit)? = null
    /** 双击波形：在该处设起点并从那里开始播 */
    var onDoubleTap: ((Float) -> Unit)? = null
    /** 把 frac 转成时间字符串，用于气泡 */
    var timeFormat: ((Float) -> String)? = null

    private var dragMode = -1 // 0=playhead, 1=start
    private var dragging = false
    private var downX = -1f
    private var lastTapMs = 0L

    init {
        v.setOnTouchListener { _, e ->
            if (playhead >= 0 && overview.isNotEmpty()) {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.x
                        val w = view.width.toFloat()
                        val dStart = Math.abs(e.x - startFrac * w)
                        val dHead = Math.abs(e.x - playhead * w)
                        dragMode = if (dStart <= 16f.dp() && dStart <= dHead) 1 else 0
                        dragging = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        dragging = dragging || Math.abs(e.x - downX) > view.width * 0.02f
                        val frac = (e.x / view.width).coerceIn(0f, 1f)
                        if (dragMode == 1) startFrac = frac else playhead = frac
                        view.invalidate()
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        val frac = (e.x / view.width).coerceIn(0f, 1f)
                        val now = System.currentTimeMillis()
                        if (!dragging) {
                            if (now - lastTapMs < 300) {
                                // 双击：在此设起点并从这里播放
                                playhead = frac; startFrac = frac
                                onSeek?.invoke(frac); onStartSeek?.invoke(frac); onDoubleTap?.invoke(frac)
                            } else {
                                playhead = frac; onSeek?.invoke(frac)
                            }
                            lastTapMs = now
                        } else if (dragMode == 1) {
                            onStartSeek?.invoke(frac)
                        } else {
                            onSeek?.invoke(frac)
                        }
                        dragMode = -1
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun Float.dp() = this * view.resources.displayMetrics.density

    /** 录制中：推进滚动窗口一帧（amplitude 0..1） */
    fun pushLive(amplitude: Float) {
        window[windowPos] = amplitude.coerceIn(0f, 1f)
        windowPos = (windowPos + 1) % window.size
        if (overview.isNotEmpty()) {
            val idx = (frameIndex * overview.size / totalFrames.coerceAtLeast(1)).toInt()
                .coerceIn(0, overview.size - 1)
            if (amplitude > overview[idx]) overview[idx] = amplitude
        }
        frameIndex++
        view.invalidate()
    }

    /** 回放前：按总帧数建全程概览；samples = 全程振幅列表 */
    fun setOverview(samples: List<Float>, totalFramesCount: Long) {
        overview = FloatArray(300)
        totalFrames = totalFramesCount.coerceAtLeast(1)
        frameIndex = 0
        for (i in overview.indices) overview[i] = 0f
        val n = samples.size
        for (i in 0 until n) {
            val idx = (i * overview.size / n.coerceAtLeast(1)).toInt().coerceIn(0, overview.size - 1)
            if (samples[i] > overview[idx]) overview[idx] = samples[i]
        }
        playhead = 0f
        view.invalidate()
    }

    fun reset() {
        window.fill(0f); windowPos = 0
        overview = FloatArray(0); playhead = -1f; startFrac = 0f; sentenceFracs = emptyList()
        totalFrames = 0; frameIndex = 0
        view.invalidate()
    }

    /** 主动重绘宿主（外部改了 playhead/startFrac 后调用） */
    fun invalidate() { view.invalidate() }

    fun onDraw(c: Canvas) {
        val w = view.width.toFloat(); val h = view.height.toFloat()
        val mid = h / 2f
        paint.color = Skin.current.stroke
        c.drawRect(0f, 0f, w, h, paint)

        if (playhead >= 0 && overview.isNotEmpty()) {
            val n = overview.size
            val step = w / n
            paint.strokeWidth = (step * 0.55f).coerceIn(1.2f, 4f)

            // 句分隔虚线（faint）
            paint.color = (Skin.current.stroke and 0x00FFFFFF) or 0x55000000.toInt()
            paint.strokeWidth = 1f
            for (sf in sentenceFracs) {
                val x = sf * w
                for (yy in 0 until h.toInt() step 6) c.drawLine(x, yy.toFloat(), x, (yy + 3).toFloat(), paint)
            }

            // 振幅柱：已播部分提亮，未播部分压暗
            for (i in 0 until n) {
                val a = overview[i]
                val barH = (a * (h * 0.8f)).coerceAtLeast(3f)
                paint.color = if (i.toFloat() / n <= playhead) Skin.current.lcdOn
                else (Skin.current.stroke and 0x00FFFFFF) or 0xCC000000.toInt()
                val x = i * step
                c.drawLine(x, mid - barH / 2, x, mid + barH / 2, paint)
            }

            // 起点把手（蓝绿菱形，顶部）
            drawDiamond(c, startFrac * w, 6f)
            // 播放头（黄竖线）
            cursorPaint.strokeWidth = 3f
            c.drawLine(playhead * w, 0f, playhead * w, h, cursorPaint)

            // 拖动气泡（仅拖动时）
            if (dragging) {
                val frac = if (dragMode == 1) startFrac else playhead
                val bx = (frac * w).coerceIn(28f, w - 28f)
                val label = timeFormat?.invoke(frac) ?: ""
                val tw = bubbleText.measureText(label)
                val bw = tw + 16f; val bh = 22f
                val by = if (dragMode == 1) 10f else 10f
                bubblePaint.color = 0xFF222831.toInt()
                c.drawRoundRect(bx - bw / 2, by, bx + bw / 2, by + bh, 6f, 6f, bubblePaint)
                c.drawLine(frac * w, by + bh, frac * w, by + bh + 6f, cursorPaint)
                bubbleText.color = 0xFFFFFFFF.toInt()
                c.drawText(label, bx, by + bh - 6f, bubbleText)
            }
        } else {
            // 录制中：滚动窗口
            val n = window.size
            val step = w / n
            paint.strokeWidth = (step * 0.55f).coerceIn(1.2f, 4f)
            for (i in 0 until n) {
                val a = window[(windowPos + i) % n]
                val barH = (a * (h * 0.8f)).coerceAtLeast(3f)
                val x = i * step
                c.drawLine(x, mid - barH / 2, x, mid + barH / 2, paint)
            }
        }
    }

    private fun drawDiamond(c: Canvas, cx: Float, r: Float) {
        startPaint.style = Paint.Style.FILL
        c.drawPath(android.graphics.Path().apply {
            moveTo(cx, 2f); lineTo(cx + r, r + 2f); lineTo(cx, 2f + 2 * r); lineTo(cx - r, r + 2f); close()
        }, startPaint)
    }
}
