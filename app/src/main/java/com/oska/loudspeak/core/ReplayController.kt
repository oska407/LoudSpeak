package com.oska.loudspeak.core

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
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
    companion object {
        /** ①档（mp4 音轨，录制端无增益可设、无兜底）→ +12dB */
        const val VIDEO_GAIN_MB = 1200
        /** ②档（audio.wav 写盘时已归一化到 -6dBFS）→ 再补 +6dB 刚好贴住 0dBFS */
        const val AUDIO_GAIN_MB = 600
    }
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
    /** VideoView 内部的 MediaPlayer：视频倍速只能通过它设（VideoView.setPlaybackSpeed 是 @hide） */
    private var videoMp: MediaPlayer? = null
    /** 回放响度增强器（挂在当前播放器上，换源时重建） */
    private var loudness: LoudnessEnhancer? = null
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
            videoMp = it
            // 每次（重新）准备都会把倍速重置回 1.0，这里重新落一遍当前倍速
            applyVideoSpeed()
            attachLoudness(it)
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
            attachLoudness(audioMp)
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
        else applyVideoSpeed()
    }

    /**
     * 视频倍速：走 VideoView 内部 MediaPlayer 的 `playbackParams`（API 23+ 公开接口）。
     *
     * ⚠️ 不要用 `VideoView.setPlaybackSpeed(float)`：它在 AOSP 里是 **@hide**，
     * SDK 桩里没有这个符号，直接写会编译失败（0.2.8 就是这么挂的：Unresolved reference）。
     * MediaPlayer.setPlaybackParams 同时作用于音频与视频渲染，效果一样且是公开 API。
     * 个别机型/片源不支持变速会抛，吞掉即可（保持 1.0x，不影响播放）。
     */
    private fun applyVideoSpeed() {
        val mp = videoMp ?: return
        if (android.os.Build.VERSION.SDK_INT < 23) return
        try { mp.playbackParams = mp.playbackParams.setSpeed(speed) } catch (_: Exception) {}
    }

    // ---------- 回放音量 ----------

    /**
     * 给当前播放器挂一个响度增强（①档 +12dB / ②档 +6dB，见 companion 常量）。
     *
     * 为什么必须在**播放**这一侧做：
     * - 录音端 `AudioEngine` 走 `AudioSource.MIC` 直采，没有 AGC，孩子离手机一米说话
     *   电平常在 -30dBFS 以下；②档的 audio.wav 已经在写盘时做了峰值归一化兜底。
     * - ①档的 mp4 音轨是 CameraX `withAudioEnabled()` 用 MediaRecorder 录的，
     *   录制端根本没有增益可设，只能在播放时补，且没有任何兜底 → 给足 +12dB。
     * - 而 `MediaPlayer.setVolume` 上限就是 1.0（只能减不能加），放大只有
     *   `LoudnessEnhancer`(API 19+) 这一条公开路子。
     *
     * 挂载时机：`getAudioSessionId()` 要等 prepare 之后才有值，所以两处都在 prepare 完
     * （视频是 onPrepared 回调）再挂；每次换源都重建，因为 sessionId 可能变。
     *
     * 兜底：个别国内 ROM 把 AudioEffect 裁了，构造或设置会抛 / 返回非 SUCCESS。
     * 这里一律静默吞掉 —— ②档还有 wav 归一化保底，①档退化成原始音量，
     * 总比让播放直接崩掉强。
     */
    private fun attachLoudness(mp: MediaPlayer?) {
        releaseLoudness()
        val id = try { mp?.audioSessionId ?: 0 } catch (_: Throwable) { 0 }
        if (id <= 0) return
        val gain = if (useVideo) VIDEO_GAIN_MB else AUDIO_GAIN_MB
        // 先建对象、再设参数：setTargetGain 抛异常时不能把已构造的句柄漏掉（AudioEffect 是系统资源）
        val le = try { LoudnessEnhancer(id) } catch (_: Throwable) { null } ?: return
        val ok = try {
            le.setTargetGain(gain)
            le.enabled = true
            true
        } catch (_: Throwable) { false }
        if (ok) loudness = le else { try { le.release() } catch (_: Throwable) {} }
    }

    private fun releaseLoudness() {
        try { loudness?.release() } catch (_: Throwable) {}
        loudness = null
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
        releaseLoudness()
        try { audioMp?.release() } catch (_: Exception) {}
        ticker.removeCallbacks(tick)
        videoView = null; audioMp = null; videoMp = null
    }
}
