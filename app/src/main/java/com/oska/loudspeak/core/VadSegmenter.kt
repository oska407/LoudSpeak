package com.oska.loudspeak.core

/**
 * RMS 静音断句（§15.6 / §14.2 B2）：
 * - 静音 ≥ vadSilenceMs（默认 800ms）判句末
 * - 最小句长 300ms（防碎句），最大句长 15s 强制在最近低能量点切断
 * 每帧 = 50ms PCM。
 */
class VadSegmenter(private val silenceMs: Int = 800) {

    /** 能量阈值：dB L 值低于此视为静音（相对响度口径，低本底取 20） */
    var silenceL = 20f

    private val buf = ArrayList<FloatArray>()   // 当前句的 50ms 帧
    private var speechMs = 0L
    private var silenceMs_ = 0L
    private var inSpeech = false
    private var curStartMs = 0L

    /** 喂入一帧。返回非 null = 一句结束（PCM 帧列表 + 起止时间）。 */
    fun feed(frame: FloatArray, l: Float, sessionElapsedMs: Long): Sentence? {
        if (!inSpeech) {
            if (l >= silenceL) { inSpeech = true; speechMs = 0; silenceMs_ = 0; curStartMs = sessionElapsedMs; buf.clear(); buf.add(frame); speechMs = 50 }
        } else {
            buf.add(frame); speechMs += 50
            if (l < silenceL) {
                silenceMs_ += 50
                if (silenceMs_ >= silenceMs && speechMs >= 300) return finish()
            } else silenceMs_ = 0

            // 最大句长强制切断
            if (speechMs >= 15000) return finish()
        }
        return null
    }

    fun flush(): Sentence? = if (inSpeech && speechMs >= 300) finish() else null

    private fun finish(): Sentence {
        inSpeech = false
        val f = buf.toTypedArray()
        buf.clear()
        val start = curStartMs
        val end = curStartMs + speechMs
        speechMs = 0; silenceMs_ = 0
        return Sentence(f, start, end)
    }

    class Sentence(val frames: Array<FloatArray>, val tStartMs: Long, val tEndMs: Long)
}
