package com.oska.loudspeak.core

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 回放内核（§3 / §4 / §6）。主界面就地回放与回放页共用同一套。
 *
 * 修 P2：录制中按暂停会生成 video_seg001/002/…，旧代码只用 firstOrNull，
 * 后面的段全丢。这里把它们按文件名排序拼成一条全局时间轴，seek 用二分定位到段内偏移，
 * 一段播完 OnCompletion 自动接下一段，到末段结尾才算真正播完。
 *
 * 倍速：仅「关画面」（走 MediaPlayer(audio.wav)）时可用；开画面走 VideoView，
 * 拿不到内部 MediaPlayer 设不了 PlaybackParams，按设计置灰（§5.3）。
 */
class ReplayController(
    private val dir: File,
    private val hasVideo: Boolean,
    private val sentences: List<RuleEngine.Sentence>,
    private val onState: (State) -> Unit,
    private val onPosition: (posMs: Long, totalMs: Long) -> Unit
) {
    enum class State { IDLE, PLAYING, PAUSED, ENDED }

    private val audioFile = File(dir, "audio.wav")
    private val videoSegs: List<File> = if (hasVideo)
        (dir.listFiles { f -> f.name.startsWith("video_seg") && f.name.endsWith(".mp4") }
            ?.sortedBy { it.name } ?: emptyList())
    else emptyList()

    private val useVideo = hasVideo && videoSegs.isNotEmpty()

    /** 每段在全局时间轴上的起点（ms）与时长（ms） */
    private val segStart = ArrayList<Long>()
    private val segDur = ArrayList<Long>()
    private var totalMs = 0L

    private var audioMp: MediaPlayer? = null
    private var videoView: VideoView? = null
    private var state = State.IDLE
    private var curSeg = 0
    private var speed = 1.0f
    private var pendingSeekMs = -1L
    private var audioReady = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (state == State.PLAYING) {
                onPosition(positionMs(), totalMs)
                ticker.postDelayed(this, 50)
            }
        }
    }

    init { buildVideoTimeline() }

    // ---------- 时间轴 ----------

    private fun buildVideoTimeline() {
        if (!useVideo) return
        val retr = MediaMetadataRetriever()
        for (seg in videoSegs) {
            segStart.add(totalMs)
            val d = try {
                retr.setDataSource(seg.absolutePath)
                retr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (_: Exception) { 0L }
            segDur.add(d); totalMs += d
        }
        retr.release()
    }

    // ---------- 绑定播放器 ----------

    /** ① 档：绑定 VideoView（音画都来自它） */
    fun attachVideoView(vv: VideoView) {
        if (!useVideo) return
        videoView = vv
        vv.setOnPreparedListener {
            if (pendingSeekMs >= 0) {
                it.seekTo((pendingSeekMs - segStart.getOrElse(curSeg) { 0L }).toInt())
                pendingSeekMs = -1
            }
            if (state == State.PLAYING) vv.start()
        }
        vv.setOnCompletionListener { onSegDone() }
        vv.setVideoURI(Uri.fromFile(videoSegs.first()))
        vv.seekTo(0)
    }

    /** ② 档（仅音频）：内部建 MediaPlayer(audio.wav) */
    fun attachAudio() {
        if (useVideo || audioMp != null) return
        try {
            audioMp = MediaPlayer().apply { setDataSource(audioFile.absolutePath) }
            audioMp?.setOnCompletionListener { onAudioDone() }
            audioMp?.prepare()
            audioReady = true
            totalMs = (audioMp?.duration ?: 0).toLong().coerceAtLeast(0)
            applySpeed()
            onPosition(0, totalMs)
        } catch (_: Exception) { audioReady = false }
    }

    // ---------- 控制 ----------

    fun play() {
        if (state == State.PLAYING) return
        if (useVideo) {
            val vv = videoView ?: return
            if (curSeg >= videoSegs.size) curSeg = 0
            if (vv.isPlaying.not()) {
                vv.setVideoURI(Uri.fromFile(videoSegs[curSeg]))
                vv.start()
            }
        } else {
            if (!audioReady) attachAudio()
            audioMp?.start()
            applySpeed()
        }
        state = State.PLAYING; onState(state); ticker.post(tick)
    }

    fun pause() {
        if (state != State.PLAYING) return
        if (useVideo) videoView?.pause() else audioMp?.pause()
        state = State.PAUSED; onState(state); ticker.removeCallbacks(tick)
    }

    /** 停止：暂停并回到最开头 */
    fun stop() {
        pause()
        seek(0)
    }

    fun seek(ms: Long) {
        if (totalMs == 0L) return
        val p = ms.coerceIn(0, totalMs)
        if (useVideo) {
            val seg = segIndexFor(p)
            if (seg != curSeg) {
                curSeg = seg
                videoView?.setVideoURI(Uri.fromFile(videoSegs[seg]))
            }
            pendingSeekMs = p // 未就绪时记住，onPrepared 再落；已就绪时下面 seekTo 立即生效
            videoView?.seekTo((p - segStart.getOrElse(seg) { 0L }).toInt())
        } else {
            audioMp?.seekTo(p.toInt())
        }
        onPosition(p, totalMs)
    }

    fun setSpeed(s: Float) { speed = s; applySpeed() }
    private fun applySpeed() {
        if (!useVideo) try { audioMp?.playbackParams = audioMp!!.playbackParams.setSpeed(speed) } catch (_: Exception) {}
    }

    // ---------- 句导航 ----------

    fun seekSentence(i: Int) { if (i in sentences.indices) seek(sentences[i].tStartMs) }
    fun nextSentence(fromMs: Long): Int? {
        for (i in sentences.indices) if (sentences[i].tStartMs > fromMs + 1) return i
        return null
    }
    fun prevSentence(fromMs: Long): Int? {
        var r: Int? = null
        for (i in sentences.indices) if (sentences[i].tEndMs <= fromMs) r = i else break
        return r
    }
    fun sentenceAt(ms: Long): Int? {
        for (i in sentences.indices) if (ms in sentences[i].tStartMs..sentences[i].tEndMs) return i
        return null
    }

    // ---------- 查询 ----------

    fun isVideo() = useVideo
    fun getTotalMs() = totalMs
    fun currentState() = state
    fun positionMs(): Long = if (useVideo)
        segStart.getOrElse(curSeg) { 0L } + (videoView?.currentPosition ?: 0)
    else (audioMp?.currentPosition?.toLong() ?: 0L)

    // ---------- 内部 ----------

    private fun segIndexFor(ms: Long): Int {
        if (segStart.isEmpty()) return 0
        var lo = 0; var hi = segStart.size - 1; var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (segStart[mid] <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    private fun onSegDone() {
        if (curSeg + 1 < videoSegs.size) {
            curSeg++
            videoView?.setVideoURI(Uri.fromFile(videoSegs[curSeg]))
            videoView?.start()
        } else finish()
    }
    private fun onAudioDone() = finish()
    private fun finish() {
        state = State.ENDED; onState(state); ticker.removeCallbacks(tick); onPosition(totalMs, totalMs)
    }

    fun release() {
        try { audioMp?.release() } catch (_: Exception) {}
        ticker.removeCallbacks(tick)
        videoView = null; audioMp = null
    }
}
