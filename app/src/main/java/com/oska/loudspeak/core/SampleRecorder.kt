package com.oska.loudspeak.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 一次性录音：16k 单声道 PCM16，录固定秒数，返回 float PCM。
 * 阻塞调用，必须放后台线程。用于「登记声纹」时采一段样音。
 */
object SampleRecorder {

    @SuppressLint("MissingPermission")
    fun record(seconds: Int, onTick: ((Int) -> Unit)? = null): FloatArray {
        val sr = AudioEngine.SR
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr * 2)
        )
        val total = sr * seconds
        val out = FloatArray(total)
        var off = 0
        try {
            rec.startRecording()
            val buf = ShortArray(1600)
            val endAt = System.currentTimeMillis() + seconds * 1000L
            while (off < total && System.currentTimeMillis() < endAt) {
                val n = rec.read(buf, 0, minOf(buf.size, total - off))
                if (n <= 0) continue
                for (i in 0 until n) out[off + i] = buf[i] / 32768f
                off += n
                onTick?.invoke(((endAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0))
            }
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
        return if (off >= total) out else out.copyOf(off)
    }
}
