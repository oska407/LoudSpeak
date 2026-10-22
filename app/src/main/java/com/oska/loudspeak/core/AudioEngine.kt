package com.oska.loudspeak.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 音频采集（§6.5 / §12.2 步 2-3）：
 * - AudioRecord 16kHz 单声道 PCM16，后台线程每 50ms 一帧
 * - 每帧：算 RMS → 分贝器 → VAD；句末回调整句 PCM（送 ASR）
 * - 全程 PCM 累积（①② 档停止后写 audio.wav；③④ 档停止即丢弃）
 * - pause/resume：暂停时停采集，继续时新起分段
 *
 * keepPcm=false 用于 ③④ 档：一个字节都不落盘，就不必攒全程 PCM
 * （10 分钟 ≈ 9.6M float ≈ 38MB，P0 验证项「③④ 档内存稳定性」要求这部分省掉）。
 */
class AudioEngine(
    private val vadSilenceMs: Int = 800,
    private val onFrame: (rawL: Float, amplitude: Float) -> Unit,
    private val onSentence: (pcm: FloatArray, tStartMs: Long, tEndMs: Long) -> Unit,
    private val keepPcm: Boolean = true
) {
    companion object { const val SR = 16000 }

    @SuppressLint("MissingPermission")
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var paused = false

    val meter = DbMeter()
    private var vad = VadSegmenter()
    private val allPcm = ArrayList<FloatArray>()     // 全程（①② 档用）
    private var sessionStartReal = 0L
    private var pausedAccumMs = 0L
    private var pauseStartedAt = 0L

    var amplitudeScale = 6f   // 波形可视幅度放大

    fun setVadSilenceMs(ms: Int) { vad = VadSegmenter(ms) }

    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        allPcm.clear()
        vad = VadSegmenter(vadSilenceMs)
        pausedAccumMs = 0L
        sessionStartReal = System.currentTimeMillis()
        running = true; paused = false
        thread = Thread {
            record?.startRecording()
            val frameN = SR * 50 / 1000   // 50ms = 800 样本
            val shortBuf = ShortArray(frameN)
            while (running) {
                if (paused) { Thread.sleep(30); continue }
                val n = record?.read(shortBuf, 0, frameN) ?: -1
                if (n <= 0) continue
                val f = FloatArray(n)
                var sumSq = 0.0
                for (i in 0 until n) { f[i] = shortBuf[i] / 32768f; sumSq += f[i] * f[i] }
                val rms = Math.sqrt(sumSq / n).toFloat()
                val rawL = meter.raw(rms)
                val elapsed = elapsedMs()
                if (keepPcm) synchronized(allPcm) { allPcm.add(f) }
                meter.frame(rawL, elapsed)
                onFrame(rawL, (rms * amplitudeScale).coerceIn(0f, 1f))
                val sent = vad.feed(f, rawL, elapsed)
                if (sent != null) {
                    val pcm = sent.frames.toList().flatMap { it.asSequence() }.toFloatArray()
                    onSentence(pcm, sent.tStartMs, sent.tEndMs)
                }
            }
        }.apply { name = "audio-engine"; start() }
    }

    fun elapsedMs(): Long = (System.currentTimeMillis() - sessionStartReal - pausedAccumMs).coerceAtLeast(0)

    fun pause() { if (running && !paused) { paused = true; pauseStartedAt = System.currentTimeMillis() } }
    fun resume() { if (running && paused) { pausedAccumMs += System.currentTimeMillis() - pauseStartedAt; paused = false } }

    /** 停止并返回全程 PCM（③④ 档调用后直接丢弃返回值） */
    fun stop(): FloatArray {
        running = false
        thread?.join(800); thread = null
        record?.run { try { stop(); release() } catch (_: Exception) {} }
        record = null
        val tail = vad.flush()
        // ③④ 档（keepPcm=false）不返回任何 PCM：内容本来也不允许落盘
        if (!keepPcm) { allPcm.clear(); return FloatArray(0) }
        val out = ArrayList<FloatArray>(allPcm)
        if (tail != null) out.add(tail.frames.toList().flatMap { it.asSequence() }.toFloatArray())
        synchronized(allPcm) { allPcm.clear() }
        return out.toList().flatMap { it.asSequence() }.toFloatArray()
    }

    /** 16kHz 单声道 PCM → WAV 字节（§10.1 audio.wav，约 1.9MB/分钟） */
    fun toWav(pcm: FloatArray): ByteArray {
        val dataLen = pcm.size * 2
        val total = 44 + dataLen
        val b = java.io.ByteArrayOutputStream(total)
        val w = { s: String -> b.write(s.toByteArray(Charsets.US_ASCII)) }
        val i32 = { v: Int -> b.write(v and 0xFF); b.write(v shr 8 and 0xFF); b.write(v shr 16 and 0xFF); b.write(v shr 24 and 0xFF) }
        val i16 = { v: Int -> b.write(v and 0xFF); b.write(v shr 8 and 0xFF) }
        w("RIFF"); i32(36 + dataLen); w("WAVE"); w("fmt "); i32(16); i16(1); i16(1)
        i32(SR); i32(SR * 2); i16(2); i16(16); w("data"); i32(dataLen)
        for (s in pcm) { val v = (s * 32767).toInt().coerceIn(-32768, 32767); i16(v) }
        return b.toByteArray()
    }
}
