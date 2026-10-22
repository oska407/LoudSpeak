package com.oska.loudspeak.core

/**
 * 分贝器（照「雯雯的出题库」朗读题口径，§6.3.3）：
 *   L = 20·log10(RMS) + 60，区间 [0,60]，相对响度
 *   采样：每 50ms 一帧原始 L（测量用，不平滑）
 *   显示：ema = ema·0.75 + L·0.25；峰值保持 500ms 后每次 −3
 * 变色用平滑后的 ema，不用瞬时值（换气不闪红绿）。
 */
class DbMeter {
    var ema = 0f; private set
    var peak = 0f; private set
    private var peakAt = 0L

    fun frame(rawL: Float, nowMs: Long) {
        ema = ema * 0.75f + rawL * 0.25f
        if (rawL > peak) { peak = rawL; peakAt = nowMs }
        else if (nowMs - peakAt > 500) { peak = (peak - 3f).coerceAtLeast(0f); peakAt = nowMs }
    }

    fun clamp(v: Float): Float = v.coerceIn(0f, 60f)
    fun raw(rms: Float): Float {
        val dbfs = if (rms <= 0f) -99f else 20f * Math.log10(rms.toDouble()).toFloat()
        return clamp(dbfs + 60f)
    }
}
