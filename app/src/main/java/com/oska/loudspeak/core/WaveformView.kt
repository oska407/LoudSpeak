package com.oska.loudspeak.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/**
 * 波形条（§6.3.1）。
 * 录制中：滚动窗口，新样本从右侧进入向左推移，静音保持低平线不塌陷；
 * 回放中：显示全程概览 + 播放游标，可拖拉 seek（仅 ①② 档；③④ 档与录制中手势关闭）。
 */
class WaveformView(v: View) {
    private val view = v
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt() }

    /** 滚动窗口样本（0..1） */
    private val window = FloatArray(160)
    private var windowPos = 0

    /** 全程概览样本（回放用） */
    private var overview = FloatArray(0)
    private var totalFrames = 0L
    private var frameIndex = 0L

    /** 回放游标 0..1，<0 表示非回放 */
    var playhead = -1f
    var onSeek: ((Float) -> Unit)? = null

    private var dragStart = -1f
    private var dragging = false

    init {
        // ⚠️ 不要设 isClickable=true：那样非回放状态下 View.onTouchEvent 也会吞掉
        // ACTION_DOWN（clickable 的默认行为），整行事件都白瞎。OnTouchListener 本身不依赖它。
        v.setOnTouchListener { _, e ->
            if (playhead >= 0 && overview.isNotEmpty()) {
                when (e.action) {
                    android.view.MotionEvent.ACTION_DOWN -> { dragStart = e.x; dragging = false; true }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        dragging = dragging || Math.abs(e.x - dragStart) > view.width * 0.02f
                        seekTo(e.x); true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        if (!dragging) seekTo(e.x) else onSeek?.invoke(e.x / view.width)
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    private fun seekTo(x: Float) {
        val p = (x / view.width).coerceIn(0f, 1f)
        playhead = p
        onSeek?.invoke(p)
        view.invalidate()
    }

    /** 录制中：推进滚动窗口一帧（amplitude 0..1） */
    fun pushLive(amplitude: Float) {
        window[windowPos] = amplitude.coerceIn(0f, 1f)
        windowPos = (windowPos + 1) % window.size
        // 同时累计全程概览（降采样：约每 1/800 帧）
        if (overview.isNotEmpty()) {
            val idx = (frameIndex * overview.size / (totalFrames.coerceAtLeast(1))).toInt()
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
        // 重放已累计帧
        // 简化：把传入 samples 均匀铺进 overview
        for (i in overview.indices) overview[i] = 0f
        val n = samples.size
        for (i in 0 until n) {
            val idx = (i * overview.size / n.coerceAtLeast(1)).coerceIn(0, overview.size - 1)
            if (samples[i] > overview[idx]) overview[idx] = samples[i]
        }
        playhead = 0f
        view.invalidate()
    }

    fun reset() {
        window.fill(0f); windowPos = 0
        overview = FloatArray(0); playhead = -1f
        totalFrames = 0; frameIndex = 0
        view.invalidate()
    }

    fun onDraw(c: Canvas) {
        val w = view.width.toFloat(); val h = view.height.toFloat()
        val mid = h / 2f
        paint.color = 0xFF2A3A46.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        paint.color = 0xFF4ADE80.toInt()
        paint.strokeWidth = 4f

        if (playhead >= 0 && overview.isNotEmpty()) {
            // 回放：全程概览 + 已播放部分提亮 + 游标
            val n = overview.size
            val step = w / n
            for (i in 0 until n) {
                val a = overview[i]
                val barH = (a * (h * 0.8f)).coerceAtLeast(3f)
                paint.color = if (i.toFloat() / n <= playhead) 0xFF4ADE80.toInt() else 0xFF2E4A5A.toInt()
                val x = i * step
                c.drawLine(x, mid - barH / 2, x, mid + barH / 2, paint)
            }
            cursorPaint.strokeWidth = 3f
            c.drawLine(playhead * w, 0f, playhead * w, h, cursorPaint)
        } else {
            // 录制中：滚动窗口
            val n = window.size
            val step = w / n
            for (i in 0 until n) {
                val a = window[(windowPos + i) % n]
                val barH = (a * (h * 0.8f)).coerceAtLeast(3f)
                val x = i * step
                c.drawLine(x, mid - barH / 2, x, mid + barH / 2, paint)
            }
        }
    }
}
