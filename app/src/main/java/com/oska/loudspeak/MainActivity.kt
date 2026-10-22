package com.oska.loudspeak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Size
import android.view.MotionEvent
import android.view.View
import android.view.View.LAYOUT_DIRECTION_LTR
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.oska.loudspeak.core.AsrEngine
import com.oska.loudspeak.core.AudioEngine
import com.oska.loudspeak.core.CrashReporter
import com.oska.loudspeak.core.DbMeter
import com.oska.loudspeak.core.GlowOverlay
import com.oska.loudspeak.core.LcdDbView
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.RuleEngine
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.WaveformView
import java.io.File
import java.util.concurrent.Executors

/**
 * 主界面（§6 四行布局 + §12 交互流程）。
 * 四档共用一条判定链路；区别只在「录不录、保不保存」（§6.1.1）。
 */
class MainActivity : AppCompatActivity() {

    // ---- 自绘控件宿主（XML 里以 view class= 引用，必须提供 (Context, AttributeSet) 构造器） ----
    class LcdHost(ctx: android.content.Context, attrs: android.util.AttributeSet? = null) : View(ctx, attrs) {
        val lcd = LcdDbView(this)
        override fun onDraw(c: android.graphics.Canvas) = lcd.onDraw(c)
    }
    class WaveHost(ctx: android.content.Context, attrs: android.util.AttributeSet? = null) : View(ctx, attrs) {
        val wf = WaveformView(this)
        override fun onDraw(c: android.graphics.Canvas) = wf.onDraw(c)
    }
    class GlowHost(ctx: android.content.Context, attrs: android.util.AttributeSet? = null) : FrameLayout(ctx, attrs) {
        val glow = GlowOverlay(this)
        override fun dispatchDraw(c: android.graphics.Canvas) { super.dispatchDraw(c); glow.onDraw(c) }
    }

    private lateinit var previewView: PreviewView
    private lateinit var videoView: VideoView
    private lateinit var leftPanel: FrameLayout
    private lateinit var tvModeBadge: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSentCount: TextView
    private lateinit var tvPronCount: TextView
    private lateinit var lcd: LcdHost
    private lateinit var wave: WaveHost
    private lateinit var tvText: TextView
    private lateinit var glow: GlowHost
    private lateinit var diagnosisScroll: android.widget.ScrollView
    private lateinit var diagnosisList: LinearLayout
    private lateinit var tvDiagnosisTitle: TextView
    private lateinit var btnSkipReplay: Button

    private lateinit var btnStop: Button
    private lateinit var btnPause: Button
    private lateinit var btnRec: Button
    private lateinit var btnPlay: Button
    private lateinit var btnHistory: Button
    private lateinit var btnSettings: Button
    private lateinit var btnMode: Button

    private val main = Handler(Looper.getMainLooper())
    private val asrExecutor = Executors.newSingleThreadExecutor()

    private lateinit var store: SessionStore
    private lateinit var asr: AsrEngine
    private var audio: AudioEngine? = null

    private var mode = Prefs.MODE_DISPLAY_VIDEO
    private var sentences = ArrayList<RuleEngine.Sentence>()
    private var sessionDir: File? = null
    private var sessionStartedAt = 0L
    private var take = 1
    private var prevTakePronouns = 0
    private var chaptersDrawn = 0

    // ① 档视频
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var videoRecording: androidx.camera.video.Recording? = null
    private var videoSegIndex = 0

    // 回放
    private var audioPlayer: MediaPlayer? = null
    private var replayTotalMs = 0L
    private var autoReplayEndMs = -1L

    private var state = "idle" // idle / recording / paused / replay / diag
    private var lastGlowAt = 0L
    private var prompted10Min = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 首次引导（§12.1）：只出现一次
        if (!Prefs.firstRunDone(this)) {
            startActivity(Intent(this, FirstRunActivity::class.java))
        }

        previewView = findViewById(R.id.previewView)
        videoView = findViewById(R.id.videoView)
        leftPanel = findViewById(R.id.leftPanel)
        tvModeBadge = findViewById(R.id.tvModeBadge)
        tvDuration = findViewById(R.id.tvDuration)
        tvSentCount = findViewById(R.id.tvSentCount)
        tvPronCount = findViewById(R.id.tvPronCount)
        lcd = findViewById(R.id.lcdView)
        wave = findViewById(R.id.waveView)
        tvText = findViewById(R.id.tvText)
        glow = findViewById(R.id.glowView)
        diagnosisScroll = findViewById(R.id.diagnosisPanel)
        diagnosisList = findViewById(R.id.diagnosisList)
        tvDiagnosisTitle = findViewById(R.id.tvDiagnosisTitle)
        btnSkipReplay = findViewById(R.id.btnSkipReplay)
        btnStop = findViewById(R.id.btnStop)
        btnPause = findViewById(R.id.btnPause)
        btnRec = findViewById(R.id.btnRec)
        btnPlay = findViewById(R.id.btnPlay)
        btnHistory = findViewById(R.id.btnHistory)
        btnSettings = findViewById(R.id.btnSettings)
        btnMode = findViewById(R.id.btnMode)

        store = SessionStore(this)
        asr = AsrEngine(Prefs.modelsDir(this))
        asrExecutor.execute { asr.load() }   // eMMC 15–30s，后台预热（§5.5）

        btnRec.setOnClickListener { onRec() }
        btnPause.setOnClickListener { onPauseBtn() }
        btnStop.setOnClickListener { onStopBtn(emotional = false) }
        btnPlay.setOnClickListener { startReplay(auto = false) }
        btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnMode.setOnClickListener { startActivity(Intent(this, ModeSelectActivity::class.java)) }

        // 长按停止 2 秒 = 情绪退出（§12.5）
        btnStop.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    main.postDelayed({
                        if (state == "recording" || state == "paused") onStopBtn(emotional = true)
                    }, 2000)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacksAndMessages(null); false
                }
                else -> false
            }
        }

        // 点击屏幕任意处立即消退红晕（§7.3）
        glow.setOnClickListener { glow.glow.clearNow() }

        wave.wf.onSeek = { p -> seekPlayback(p) }
        btnSkipReplay.setOnClickListener { endReplay(showDiagnosis = true) }
        findViewById<Button>(R.id.btnRetake).setOnClickListener { onRetake() }
        findViewById<Button>(R.id.btnCloseDiagnosis).setOnClickListener { diagnosisScroll.visibility = View.GONE }

        store.cleanTrash()
        updateModeBadge()
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        if (state == "idle") { mode = Prefs.getMode(this); updateModeBadge() }
    }

    // ================= 状态机（§6.4） =================

    private fun updateButtons() {
        btnRec.isEnabled = state == "idle"
        btnPause.isEnabled = state == "recording" || state == "paused"
        btnPause.text = if (state == "paused") "继续" else "暂停"
        btnStop.isEnabled = state == "recording" || state == "paused"
        btnPlay.isEnabled = (state == "idle" || state == "diag") && sessionDir != null &&
                mode != Prefs.MODE_DISPLAY_VIDEO && mode != Prefs.MODE_DISPLAY_AUDIO
        btnMode.isEnabled = state == "idle"
        btnSettings.isEnabled = state != "recording"
        btnHistory.isEnabled = state != "recording"
    }

    private fun updateModeBadge() {
        val name = when (mode) {
            Prefs.MODE_FULL -> "① 完整录制"
            Prefs.MODE_AUDIO -> "② 仅音频"
            Prefs.MODE_DISPLAY_VIDEO -> "③ 纯显示·带画面 ·不保存"
            else -> "④ 纯显示·音频 ·不保存"
        }
        tvModeBadge.text = name
        leftPanel.visibility =
            if (mode == Prefs.MODE_AUDIO || mode == Prefs.MODE_DISPLAY_AUDIO) View.GONE else View.VISIBLE
    }

    // ================= 录音（§12.2 步 1–5） =================

    private fun onRec() {
        if (state != "idle") return
        if (!asr.available) {
            Toast.makeText(this, "ASR 未就绪：${asr.status}", Toast.LENGTH_LONG).show()
            if (asr.status.contains("缺模型")) startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        // 权限（§5.4.1）：RECORD_AUDIO 四档必需；CAMERA 仅 ①③
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (mode == Prefs.MODE_FULL || mode == Prefs.MODE_DISPLAY_VIDEO) perms.add(Manifest.permission.CAMERA)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
            return
        }
        startRecording()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 100 && results.all { it == PackageManager.PERMISSION_GRANTED }) startRecording()
        else Toast.makeText(this, "权限被拒，无法录音", Toast.LENGTH_SHORT).show()
    }

    private fun startRecording() {
        sessionStartedAt = System.currentTimeMillis()
        chaptersDrawn = 0
        prompted10Min = false
        take = 1
        prevTakePronouns = 0
        sentences.clear()
        tvText.text = ""
        wave.wf.reset()
        glow.glow.clearNow()

        // 热降频缓解（§13.2 措施 1）：亮度锁 50%、刷新率 60Hz；停止时恢复
        window.attributes = window.attributes.apply {
            screenBrightness = 0.5f
            preferredRefreshRate = 60f
        }

        // ①② 档建会话目录；③④ 档一个字节都不写（§10.1）
        sessionDir = if (mode == Prefs.MODE_FULL || mode == Prefs.MODE_AUDIO)
            store.newSessionDir(sessionStartedAt) else null

        val withCamera = mode == Prefs.MODE_FULL || mode == Prefs.MODE_DISPLAY_VIDEO
        if (withCamera) bindCamera(record = mode == Prefs.MODE_FULL)
        else unbindCamera()

        audio = AudioEngine(
            vadSilenceMs = Prefs.vadSilenceMs(this),
            onFrame = { rawL, amp ->
                main.post {
                    // 变色用平滑后的 ema（§6.3.3：瞬时值换气会红绿闪）
                    lcd.lcd.update((audio?.meter?.ema ?: rawL).coerceIn(0f, 60f), Prefs.dbThreshold(this))
                    wave.wf.pushLive(amp)
                    tvDuration.text = fmtMs(audio?.elapsedMs() ?: 0)
                    // 章节标记每 60 秒（§12.2 步 5）
                    val chapter = ((audio?.elapsedMs() ?: 0) / 60000).toInt()
                    if (chapter > chaptersDrawn) { chaptersDrawn = chapter; renderAll() }
                    // 连续录满 10 分钟：非阻断提示，不自动停（§13.2 措施 4）
                    if (!prompted10Min && (audio?.elapsedMs() ?: 0) >= 10 * 60_000L) {
                        prompted10Min = true
                        AlertDialog.Builder(this)
                            .setMessage("已录 10 分钟，要不要先停一下？")
                            .setPositiveButton("继续录", null)
                            .setNeutralButton("先停下") { _, _ -> onStopBtn(emotional = false) }
                            .show()
                    }
                }
            },
            onSentence = { pcm, t0, t1 ->
                asrExecutor.execute {
                    val text = asr.transcribe(pcm)
                    if (text == null) CrashReporter.report("error", "ASR 单句转写失败/为空 t0=$t0")
                    main.post { onAsrResult(text, t0, t1) }
                }
            }
        )
        audio?.start()
        state = "recording"
        updateButtons()
    }

    private fun bindCamera(record: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val preview = Preview.Builder().setTargetResolution(Size(1280, 720)).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val groupBuilder = UseCaseGroup.Builder()
                    .setViewPort(ViewPort.Builder(androidx.camera.core.Rational(3, 4), LAYOUT_DIRECTION_LTR).build())
                    .addUseCase(preview)
                if (record) {
                    val recorder = Recorder.Builder().build()
                    val vc = VideoCapture.Builder(recorder).setTargetResolution(Size(544, 720)).build()
                    videoCapture = vc
                    groupBuilder.addUseCase(vc)
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, groupBuilder.build())
                if (record) startVideoSegment()
            } catch (e: Exception) {
                CrashReporter.report("error", "相机绑定失败 mode=$mode", e)
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        videoRecording = null
    }

    private fun startVideoSegment() {
        val dir = sessionDir ?: return
        val vc = videoCapture ?: return
        videoSegIndex++
        val f = File(dir, "video_seg%03d.mp4".format(videoSegIndex))
        try {
            videoRecording = vc.output
                .prepareRecording(this, FileOutputOptions.Builder(f).build())
                .withAudioEnabled()
                .start(ContextCompat.getMainExecutor(this)) { }
        } catch (e: Exception) {
            CrashReporter.report("error", "录像启动失败 seg=$videoSegIndex", e)
            Toast.makeText(this, "录像启动失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ================= ASR 结果 → 显示（§12.2 步 3–4） =================

    private fun onAsrResult(text: String?, t0: Long, t1: Long) {
        val s = RuleEngine.Sentence(
            index = sentences.size, tStartMs = t0, tEndMs = t1,
            text = text ?: "?", uncertain = text == null,
            take = take
        )
        RuleEngine.evaluate(listOf(s), l4Enabled = false)   // 实时只做 L1（§8.6 起点只开 L1）
        sentences.add(s)
        renderAll()
        tvSentCount.text = sentences.count { !it.uncertain }.toString()
        tvPronCount.text = sentences.sumOf { it.pronouns.size }.toString()

        // 红晕（§7.1）：命中代词 + 防抖 + 录制中 + 未静默
        if (state == "recording" && s.pronouns.isNotEmpty() && Prefs.glowMode(this) == 0) {
            val now = System.currentTimeMillis()
            if (now - lastGlowAt >= Prefs.glowDebounceSec(this) * 1000L) {
                lastGlowAt = now
                glow.glow.fire()
            }
        }
    }

    private fun renderAll() {
        val sb = SpannableStringBuilder()
        var lastChapter = 0
        for (s in sentences) {
            while (lastChapter < chaptersDrawn && lastChapter * 60000 < s.tEndMs) {
                lastChapter++
                appendSep(sb, lastChapter)
            }
            if (s.uncertain) {
                val st = sb.length
                sb.append("?")
                sb.setSpan(ForegroundColorSpan(0xFF6E7B84.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                var idx = 0
                for ((w, pos) in s.pronouns.sortedBy { it.second }) {
                    if (pos > idx) sb.append(s.text.substring(idx, pos))
                    val rs = sb.length
                    sb.append(w)
                    sb.setSpan(ForegroundColorSpan(0xFFFF5252.toInt()), rs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    idx = pos + w.length
                }
                if (idx < s.text.length) sb.append(s.text.substring(idx))
            }
            if (s.selfMarked) {
                val ps = sb.length
                sb.append("〔已自评〕")
                sb.setSpan(ForegroundColorSpan(0xFF6E7B84.toInt()), ps, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (s.flags.isNotEmpty()) {
                val ps = sb.length
                sb.append("〔${s.flags.joinToString("·")}〕")
                sb.setSpan(ForegroundColorSpan(0xFFFFB74D.toInt()), ps, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb.append("\n")
        }
        if (sb.isEmpty()) sb.append("（点「录音」开始练习）")
        tvText.text = sb
    }

    private fun appendSep(sb: SpannableStringBuilder, chapter: Int) {
        val st = sb.length
        sb.append("\n──── ").append(chapter).append(":00 ────\n")
        sb.setSpan(ForegroundColorSpan(0xFF37474F.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // ================= 暂停 / 停止（§12.2 步 6–7） =================

    private fun onPauseBtn() {
        when (state) {
            "recording" -> { audio?.pause(); videoRecording?.pause(); state = "paused" }
            "paused" -> {
                audio?.resume(); videoRecording?.resume()
                state = "recording"
                if (mode == Prefs.MODE_FULL) startVideoSegment()   // 暂停产生新分段
            }
        }
        updateButtons()
    }

    private fun onStopBtn(emotional: Boolean) {
        if (state != "recording" && state != "paused") return
        val wasMode = mode
        val dir = sessionDir
        val elapsed = audio?.elapsedMs() ?: 0
        try { videoRecording?.stop() } catch (_: Exception) {}
        videoRecording = null
        val pcm = audio?.stop()
        audio = null
        state = "idle"
        glow.glow.clearNow()
        // 恢复屏幕亮度与刷新率（§13.2）
        window.attributes = window.attributes.apply {
            screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            preferredRefreshRate = 0f
        }
        updateButtons()

        // ①② 档落盘 audio.wav；③④ 档一个字节都不写（§10.1）
        if (dir != null && pcm != null && (wasMode == Prefs.MODE_FULL || wasMode == Prefs.MODE_AUDIO)) {
            val wav = AudioEngine(vadSilenceMs = 800).toWav(pcm)
            asrExecutor.execute {
                try { File(dir, "audio.wav").writeBytes(wav) } catch (_: Exception) {}
            }
        }

        if (emotional) {
            // 情绪退出：跳过回放与判定，不产任何评价（§12.5）
            if (dir != null) saveSession(elapsed, exitKind = "emotional")
            AlertDialog.Builder(this).setMessage("没事，我们下次再来。")
                .setPositiveButton("好", null).show()
            return
        }
        runJudgmentAndDiagnosis(elapsed)
    }

    private fun saveSession(elapsedMs: Long, exitKind: String) {
        val dir = sessionDir ?: return
        val complete = sentences.count { it.judgeable && !it.selfMarked && it.flags.isEmpty() && it.pronouns.isEmpty() }
        val incomplete = sentences.count { it.isProblem }
        val selfMarked = sentences.count { it.selfMarked }
        store.writeTranscript(dir, sentences)
        store.writeMeta(dir, mode, sessionStartedAt, System.currentTimeMillis(), elapsedMs, take,
            sentences.sumOf { it.pronouns.size }, complete, incomplete, selfMarked, exitKind,
            modelsNote = if (asr.available) "纯规则模式" else asr.status)
        store.indexSession(SessionStore.Record(dir.name, sessionStartedAt, elapsedMs,
            sentences.sumOf { it.pronouns.size }, complete, incomplete, selfMarked, exitKind,
            dir.absolutePath))
    }

    // ================= 整句判断 → 回放 → 诊断（§12.3 / §12.4） =================

    private fun runJudgmentAndDiagnosis(elapsedMs: Long) {
        val count = Prefs.incSessionCount(this)   // ③④ 档同样计数（§12.0）
        asrExecutor.execute {
            RuleEngine.evaluate(sentences, l4Enabled = RuleEngine.l4Enabled(count))
            main.post {
                if (sessionDir != null) saveSession(elapsedMs, exitKind = "normal")
                val hasProblem = sentences.any { it.isProblem }
                if (hasProblem && sessionDir != null) startReplay(auto = true)
                else showDiagnosis()
            }
        }
    }

    /** 回放（仅 ①② 档）：自动播最有价值的一句前 0.3s 起，约 8 秒；明显可跳过（§12.4） */
    private fun startReplay(auto: Boolean) {
        val dir = sessionDir ?: return
        state = "replay"
        updateButtons()

        val audioFile = File(dir, "audio.wav")
        if (!audioFile.isFile) { showDiagnosis(); return }

        asrExecutor.execute {
            val amps = decodeWavAmplitudes(audioFile, 6000)
            main.post {
                wave.wf.setOverview(amps, 6000L)
                btnSkipReplay.visibility = View.VISIBLE

                var startMs = 0L
                var playLen = 8000L
                if (auto) {
                    val best = sentences.firstOrNull { it.isProblem && it.pronouns.isNotEmpty() }
                        ?: sentences.firstOrNull { it.isProblem }
                    if (best != null) {
                        startMs = (best.tStartMs - 300).coerceAtLeast(0)
                        playLen = (best.tEndMs + 500 - startMs).coerceAtLeast(1000)
                    }
                }

                if (mode == Prefs.MODE_FULL) {
                    val video = dir.listFiles()?.firstOrNull { it.name.startsWith("video_seg") }
                    if (video != null) {
                        videoView.visibility = View.VISIBLE
                        previewView.visibility = View.GONE
                        videoView.setVideoPath(video.absolutePath)
                        videoView.setOnPreparedListener { mp ->
                            replayTotalMs = mp.duration.toLong()
                            mp.seekTo(startMs.toInt()); mp.start()
                        }
                        videoView.start()
                    }
                } else {
                    try {
                        audioPlayer = MediaPlayer().apply {
                            setDataSource(audioFile.absolutePath)
                            prepare()
                            replayTotalMs = duration.toLong()
                            seekTo(startMs.toInt())
                            start()
                        }
                    } catch (_: Exception) {}
                }
                main.postDelayed({ if (state == "replay") endReplay(showDiagnosis = true) }, playLen)
                tickPlayhead()
            }
        }
    }

    private fun tickPlayhead() {
        if (state != "replay") return
        val pos = if (videoView.visibility == View.VISIBLE) videoView.currentPosition.toLong()
        else audioPlayer?.currentPosition?.toLong() ?: 0L
        if (replayTotalMs > 0) wave.wf.playhead = (pos.toFloat() / replayTotalMs).coerceIn(0f, 1f)
        wave.invalidate()
        main.postDelayed({ tickPlayhead() }, 100)
    }

    private fun seekPlayback(p: Float) {
        val target = (p * replayTotalMs).toInt()
        if (videoView.visibility == View.VISIBLE) videoView.seekTo(target)
        audioPlayer?.seekTo(target)
    }

    private fun endReplay(showDiagnosis: Boolean) {
        btnSkipReplay.visibility = View.GONE
        try { videoView.stopPlayback() } catch (_: Exception) {}
        try { audioPlayer?.release() } catch (_: Exception) {}
        audioPlayer = null
        videoView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        state = "diag"
        updateButtons()
        if (showDiagnosis) showDiagnosis()
    }

    /** 诊断（追问卡 §9.2）：问题句 + 追问语 + 自评按钮；主按钮「再讲一次这道题」 */
    private fun showDiagnosis() {
        diagnosisList.removeAllViews()
        val problems = sentences.filter { it.isProblem }
        if (take >= 2) {
            // §9.2 步 3：只呈现改善的部分，不说「还剩什么」
            val improved = (prevTakePronouns - sentences.sumOf { it.pronouns.size }).coerceAtLeast(0)
            tvDiagnosisTitle.text = "第 $take 遍，你把 $improved 处「这个」说出了名字。"
        } else if (problems.isEmpty()) {
            tvDiagnosisTitle.text = "这段里没有发现要改的句子，说得挺好。"
        } else {
            tvDiagnosisTitle.text = "有 ${problems.size} 句可以更好："
            for (s in problems.take(6)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6)
                }
                val tv = TextView(this).apply {
                    textSize = 17f; setTextColor(0xFFFFFFFF.toInt())
                    text = "「${if (s.uncertain) "?" else s.text}」\n${s.followUp}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                }
                val btn = Button(this).apply {
                    text = "这句我没说清楚"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                    setOnClickListener {
                        s.selfMarked = true     // §9.1：只做标注，从「不完整」计数移除
                        row.visibility = View.GONE
                        tvSentCount.text = sentences.count { !it.uncertain }.toString()
                        tvPronCount.text = sentences.sumOf { it.pronouns.size }.toString()
                        renderAll()
                    }
                }
                row.addView(tv); row.addView(btn)
                diagnosisList.addView(row)
            }
        }
        diagnosisScroll.visibility = View.VISIBLE
        state = "diag"
        updateButtons()
    }

    /** 再讲一次（§9.2）：追加录制 take+1，同一条链路不放宽；结束时只呈现改善的部分 */
    private fun onRetake() {
        if (state != "diag") return
        diagnosisScroll.visibility = View.GONE
        prevTakePronouns = sentences.sumOf { it.pronouns.size }
        sentences.clear()
        tvText.text = ""
        wave.wf.reset()
        videoSegIndex = 0
        sessionStartedAt = System.currentTimeMillis()
        take += 1

        val withCamera = mode == Prefs.MODE_FULL || mode == Prefs.MODE_DISPLAY_VIDEO
        if (withCamera) bindCamera(record = mode == Prefs.MODE_FULL)

        audio = AudioEngine(
            vadSilenceMs = Prefs.vadSilenceMs(this),
            onFrame = { rawL, amp ->
                main.post {
                    lcd.lcd.update((audio?.meter?.ema ?: rawL).coerceIn(0f, 60f), Prefs.dbThreshold(this))
                    wave.wf.pushLive(amp)
                    tvDuration.text = fmtMs(audio?.elapsedMs() ?: 0)
                }
            },
            onSentence = { pcm, t0, t1 ->
                asrExecutor.execute {
                    val text = asr.transcribe(pcm)
                    main.post { onAsrResult(text, t0, t1) }
                }
            }
        )
        audio?.start()
        state = "recording"
        updateButtons()
    }

    // ================= 工具 =================

    private fun fmtMs(ms: Long): String = "%02d:%02d".format(ms / 60000, ms % 60000 / 1000)

    private fun decodeWavAmplitudes(file: File, target: Int): List<Float> {
        return try {
            val bytes = file.readBytes()
            val n = (bytes.size - 44) / 2
            val step = (n / target).coerceAtLeast(1)
            val out = ArrayList<Float>(target)
            var i = 44
            while (i + 1 < bytes.size) {
                var m = 0
                var j = 0
                while (j < step && i + 1 < bytes.size) {
                    val v = ((bytes[i].toInt() and 0xFF) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                    m = maxOf(m, Math.abs(v))
                    i += 2; j++
                }
                out.add((m / 32768f * 6f).coerceIn(0f, 1f))
            }
            out
        } catch (e: Exception) { List(100) { 0.1f } }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { videoRecording?.stop() } catch (_: Exception) {}
        try { audioPlayer?.release() } catch (_: Exception) {}
        audio?.stop()
        asr.release()
        asrExecutor.shutdown()
    }
}
