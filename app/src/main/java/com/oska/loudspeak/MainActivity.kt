package com.oska.loudspeak

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Size
import android.view.MotionEvent
import android.view.View
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
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
import com.oska.loudspeak.core.Punctuator
import com.oska.loudspeak.core.Annotator
import com.oska.loudspeak.core.QwenJudge
import com.oska.loudspeak.core.RuleEngine
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.Skin
import com.oska.loudspeak.core.WaveformView
import com.oska.loudspeak.core.Ui
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * 主界面（§6 四行布局 + §12 交互流程）。
 * 四档共用一条判定链路；区别只在「录不录、保不保存」（§6.1.1）。
 */
class MainActivity : LsActivity() {

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
    private lateinit var previewBox: FramingWindow
    private lateinit var videoView: VideoView
    private lateinit var leftPanel: FrameLayout
    private lateinit var tvModeBadge: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvSentCount: TextView
    private lateinit var tvPronCount: TextView
    private lateinit var lcd: LcdHost
    private lateinit var wave: WaveHost
    private lateinit var textArea: WritingTextArea   // 第 3 行：书写式文字区（自动跟随/手拨/阻尼回收）
    private lateinit var glow: GlowHost
    /** 视频窗口范围的红晕（R11 可选：只视频一圈 / 整界面一圈 / 关闭） */
    private lateinit var glowVideo: GlowHost
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
    private val ioExecutor = Executors.newSingleThreadExecutor()   // 停止/落盘等重 IO，绝不占主线程
    private val longPressStop = Runnable {
        if (state == "recording" || state == "paused") onStopBtn(emotional = true)
    }

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
    private var asrLoading = false
    private var lastGlowAt = 0L
    private var prompted10Min = false
    /** 过热提示（每会话一次，非阻断） */
    private var promptedHot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 设计约定「无顶部状态条」：藏掉系统状态栏，四行多出约 47dp（§6.1）
        Ui.hideStatusBar(this)

        // 首次引导（§12.1）：只出现一次
        if (!Prefs.firstRunDone(this)) {
            startActivity(Intent(this, FirstRunActivity::class.java))
        }

        previewView = findViewById(R.id.previewView)
        previewBox = findViewById(R.id.previewBox)
        videoView = findViewById(R.id.videoView)
        leftPanel = findViewById(R.id.leftPanel)
        tvModeBadge = findViewById(R.id.tvModeBadge)
        tvDuration = findViewById(R.id.tvDuration)
        tvSentCount = findViewById(R.id.tvSentCount)
        tvPronCount = findViewById(R.id.tvPronCount)
        lcd = findViewById(R.id.lcdView)
        wave = findViewById(R.id.waveView)
        textArea = findViewById(R.id.textArea)
        glow = findViewById(R.id.glowView)
        glowVideo = findViewById(R.id.glowVideo)
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
        // eMMC 15–30s 后台预热（§5.5）；加载结果在下方再刷新一次空闲提示

        btnRec.setOnClickListener { onRec() }
        btnPause.setOnClickListener { onPauseBtn() }
        btnStop.setOnClickListener { onStopBtn(emotional = false) }
        btnPlay.setOnClickListener { startReplay(auto = false) }
        btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnMode.setOnClickListener { startActivity(Intent(this, ModeSelectActivity::class.java)) }

        // 取景：用 TextureView 模式 —— 取景窗口要平移 PreviewView，SurfaceView 在部分机型上
        // 平移/裁剪不生效（会漏出窗口外的东西），TextureView 没有这个毛病。
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewBox.bias = Prefs.cropBias(this, curRotation())
        if (!Prefs.hasCropBias(this, curRotation())) autoFrame()   // 这个朝向还没标定过
        setupPreviewDrag()

        // 长按停止 2 秒 = 情绪退出（§12.5）
        // ⚠️ 必须用专用 Runnable 取消：早先用的 removeCallbacksAndMessages(null)
        // 会连眼皮条形驱动 tickPlayhead、回放结束延时一并抹掉，导致回放卡住不再结束。
        btnStop.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    main.removeCallbacks(longPressStop)
                    main.postDelayed(longPressStop, 2000)
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPressStop); false
                }
                else -> false
            }
        }

        // 红晕消退改为在 dispatchTouchEvent 里顺带处理（§7.3）。
        // ⚠️ 绝不能给红晕层挂点击监听：它整屏盖在内容之上，一旦 clickable 就会吞掉
        // ACTION_DOWN，底部七键全部按不动（真机「点录音没反应」即此）。

        wave.wf.onSeek = { p -> seekPlayback(p) }
        btnSkipReplay.setOnClickListener { endReplay(showDiagnosis = true) }
        findViewById<Button>(R.id.btnRetake).setOnClickListener { onRetake() }
        // 关闭诊断必须回到 idle：否则 state 停在 diag，录音键会一直灰着没路出来
        findViewById<Button>(R.id.btnCloseDiagnosis).setOnClickListener {
            diagnosisScroll.visibility = View.GONE
            if (state == "diag") { state = "idle"; updateButtons(); idleHint() }
        }

        ioExecutor.execute { store.cleanTrash() }   // 7 天过期回收目录清理：文件 IO，不占主线程
        updateModeBadge()
        updateButtons()
        idleHint()
        ensureAsrLoaded()
    }

    /**
     * 有模型文件才加载；已经加载过就跳过。
     * 关键点：装完模型回到主界面时必须能自己重新加载 —— 早先只在 onCreate 加载一次，
     * 结果设置页显示两个文件都在 ✓，主界面却一直挂着「缺模型文件」的旧状态。
     */
    private fun ensureAsrLoaded() {
        if (asr.available || asrLoading) return
        val (m, t) = asr.checkFiles()
        if (m?.isFile != true || t?.isFile != true) return
        asrLoading = true
        idleHint()
        asrExecutor.execute {
            asr.load()
            asrLoading = false
            main.post { if (state == "idle" && sentences.isEmpty()) idleHint() }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置回来：皮肤 / 深色浅色 / 导航栏可能改了。本界面没被重建，得自己重着色一次
        Skin.applyTo(this, findViewById(android.R.id.content))
        if (::textArea.isInitialized) textArea.applySkin()   // 文字区自绘，单独刷
        if (state == "idle") {
            mode = Prefs.getMode(this); updateModeBadge()
            // 从「设置」装完模型回来：自动补加载 + 刷新提示
            if (sentences.isEmpty()) { ensureAsrLoaded(); idleHint() }
        }
    }

    /** 空白文字区的提示：模型没就绪时直接说清缺什么，而不是一闪而过的 Toast */
    private fun idleHint() {
        if (sentences.isNotEmpty()) return
        textArea.setContent(
            when {
                asr.available -> "（点「录音」开始练习）"
                asrLoading -> "正在加载语音模型…（首次约 15–30 秒，加载完这里会变）"
                asr.status == "未加载" -> "还不能开始：还没找到模型文件\n（点底部「设置」→ 扫描并安装）"
                else -> "还不能开始：${asr.status}\n（点底部「设置」→ 扫描并安装）"
            }
        )
    }

    /** 点击屏幕任意处立即消退红晕（§7.3）——只看不改事件流向，七键照常收得到点击 */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && ::glow.isInitialized) glow.glow.clearNow()
        return super.dispatchTouchEvent(ev)
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
            // Toast 一闪而过；文字区常驻提示，避免「点了没反应」的错觉
            idleHint()
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
        promptedHot = false
        take = 1
        prevTakePronouns = 0
        sentences.clear()
        textArea.reset()
        wave.wf.reset()
        clearGlow()

        // 热降频缓解（§13.2 措施 1）：亮度锁 50%、刷新率锁 60Hz；停止时恢复
        // preferredRefreshRate 是 API 30 才有的字段，低版本设备访问会 NoSuchFieldError
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.attributes = window.attributes.apply {
                screenBrightness = 0.5f
                preferredRefreshRate = 60f
            }
        } else {
            window.attributes = window.attributes.apply { screenBrightness = 0.5f }
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
                        // §13.2 措施 5（前 4 条见 startRecording 的亮度/刷新率锁与 ASR 线程数 2）：
                        // 温度角标已经取消，温度就随这次提示带出来。
                        val t = batteryTempC()
                        val tail = if (t != null) " · 现在 $t℃" else ""
                        AlertDialog.Builder(this)
                            .setMessage("已录 10 分钟$tail，要不要先停一下？")
                            .setPositiveButton("继续录", null)
                            .setNeutralButton("先停下") { _, _ -> onStopBtn(emotional = false) }
                            .show()
                    }
                    // 太热了单独弹一次（非阻断，每会话一次）：热降频会把出字从 1–2s 拉长到 3–5s，
                    // 那时候「文字跟不上说话」，练的体验就崩了（§16 R10）。
                    if (!promptedHot && (audio?.elapsedMs() ?: 0) >= 3 * 60_000L) {
                        val t = batteryTempC()
                        if (t != null && t >= hotC) {
                            promptedHot = true
                            AlertDialog.Builder(this)
                                .setMessage("机身有点热了（$t℃）。要不要先歇一下？\n继续也行，讲完这次就停。")
                                .setPositiveButton("继续录", null)
                                .setNeutralButton("先停下") { _, _ -> onStopBtn(emotional = false) }
                                .show()
                        }
                    }
                }
            },
            onSentence = { pcm, t0, t1 ->
                asrExecutor.execute {
                    val text = asr.transcribe(pcm)
                    if (text == null) CrashReporter.report("error", "ASR 单句转写失败/为空 t0=$t0")
                    main.post { onAsrResult(text, t0, t1) }
                }
            },
            // ③④ 档不落盘 → 不攒全程 PCM（10 分钟省 ~38MB，见 AudioEngine 注释）
            keepPcm = (mode == Prefs.MODE_FULL || mode == Prefs.MODE_AUDIO)
        )
        audio?.start()
        state = "recording"
        updateButtons()
    }

    // ================= 取景（人脸居中） =================

    private var dragStartX = 0f
    private var dragStartBias = 0f
    private var biasHintShown = false

    /** 当前屏幕朝向（Surface.ROTATION_*），取景与录制朝向都按它存/取 */
    private fun curRotation(): Int = Prefs.displayRotation(this)

    private val resetBias = Runnable {
        Prefs.setCropBias(this, curRotation(), 0f, manual = false)
        previewBox.bias = 0f
        autoFrame()
        Toast.makeText(this, "取景已恢复自动", Toast.LENGTH_SHORT).show()
    }

    /**
     * 画面可以左右拖：拖到哪就记住哪（下次进来还是这个位置）。
     * 长按 0.9 秒彻底复位回「自动标定」。
     *
     * ⚠️ ACTION_DOWN 必须返回 true，否则系统认为没人要这个手势，后面的 MOVE 根本收不到；
     * 也正因为吞了事件，长按不能用 setOnLongClickListener（它要求 View 自己处理 touch），
     * 只能在这里用定时器自己做。
     */
    private fun setupPreviewDrag() {
        previewBox.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = e.rawX
                    dragStartBias = previewBox.bias
                    main.removeCallbacks(resetBias)
                    main.postDelayed(resetBias, 900)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val half = previewBox.slack() / 2f
                    if (abs(e.rawX - dragStartX) > 12f) main.removeCallbacks(resetBias)
                    if (half > 1f) {
                        // 手指往右 → 画面跟着往右 → 露出更左边的一段
                        previewBox.bias = dragStartBias + (e.rawX - dragStartX) / half
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(resetBias)
                    if (abs(previewBox.bias - dragStartBias) > 0.02f) {
                        Prefs.setCropBias(this, curRotation(), previewBox.bias, manual = true)
                        if (!biasHintShown) {
                            biasHintShown = true
                            Toast.makeText(this, "取景位置已记住（长按画面恢复自动）", Toast.LENGTH_SHORT).show()
                        }
                    }
                    true
                }
                else -> true
            }
        }
    }

    /**
     * 手机转向（横屏正 ↔ 反）。
     *
     * ⚠️ Manifest 里给 MainActivity 声明了 configChanges，所以这里**不会重建 Activity**。
     * 为什么必须自己接住：默认行为是销毁重建，录到一半有人转下手机，音频采集、
     * 累计的整句、这场会话的计时统统归零 —— 孩子白录一场。这个代价不能接受。
     * 代价是布局得自己扛：好在布局全是权重流式的（左右 26/73、四行 142/128/306/144），
     * 正反横屏尺寸一样（都是 1604×720），也没有横竖两套资源，重建不出尺寸问题。
     *
     * 转向后要做两件事：
     * 1. 取景套用**这个朝向**标定过的值；没标定过（第一次转过来）就等画面转完了重新标定。
     * 2. 告诉 VideoCapture 朝向变了 —— 否则 mp4 里的画面是歪的。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Ui.hideStatusBar(this)
        val rot = curRotation()
        previewBox.bias = Prefs.cropBias(this, rot)
        if (!Prefs.hasCropBias(this, rot) && previewView.visibility == View.VISIBLE) {
            previewView.postDelayed({ autoFrame() }, 900L)   // 等 Preview 把画面回转过来
        }
        try {
            // 正在录的时候改 targetRotation 可能抛异常 —— 吞掉，最多这一段 mp4 朝向不对，
            // 音频（审批走 AudioRecord）完全不受影响。
            videoCapture?.targetRotation = rot
        } catch (e: Exception) {
            CrashReporter.report("warn", "转向后未能更新录制朝向 rot=$rot", e)
        }
    }

    /**
     * 自动标定取景偏移：抓一帧，按肤色质心找脸的位置，把取景窗口平移过去让脸居中。
     * 连做 3 次（每次间隔递增）收敛一下，仍判不出来就保持不动 —— 不猜。
     */
    private fun autoFrame(attempt: Int = 0) {
        if (Prefs.cropBiasManual(this, curRotation())) return
        main.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val bmp = try { previewView.bitmap } catch (e: Exception) { null }
            val fx = bmp?.let { FaceFramer.skinCenterX(it) }
            if (fx != null && previewBox.slack() > 8) {
                val tw = previewBox.childWidth().toFloat()
                val cw = previewBox.width.toFloat()
                val sl = previewBox.slack().toFloat()
                // 让质心落在窗口正中
                val x0 = (fx * tw - cw / 2f).coerceIn(0f, sl)
                val b = (1f - 2f * x0 / sl).coerceIn(-1f, 1f)
                previewBox.bias = b
                Prefs.setCropBias(this, curRotation(), b)
            }
            if (attempt < 2) autoFrame(attempt + 1)
        }, 900L + attempt * 500L)
    }

    private fun bindCamera(record: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()
                bindOnce(provider, record)
                if (record) startVideoSegment()
                autoFrame()      // 相机起来后自动把脸摆到中间
            } catch (e: Exception) {
                CrashReporter.report("error", "相机绑定失败 mode=$mode", e)
                Toast.makeText(this, "相机启动失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 绑定一次相机。
     *
     * ⚠️ 这里**不再用 ViewPort**。原来的 ViewPort(3:4) 会把画面裁到只剩一条竖条，
     * 之后 PreviewView 再填满窄窄的左栏，可左右挪的余量只剩 ±60px（≈±14%），
     * 根本不够把偏到一边的脸拉回中间。现在预览按 16:9 全幅给出，
     * 由 FramingWindow 在一个比外框宽一倍多的画幅里平移挑窗口 ——
     * 可挪余量 ±430px（≈±100%），够用了。录制也随之为 1280×720 全幅。
     */
    private fun bindOnce(provider: ProcessCameraProvider, record: Boolean) {
        // 预览固定 16:9 / 1280×720：FramingWindow 的内层按 16:9 撑开，
        // 尺寸对不上就会上下把头顶裁掉。
        // §13.2 措施 2：预览帧率压到 10fps（录制仍按设备自己的帧率编码，预览降帧不影响成片），
        // 省的是相机管线与总线带宽，间接降低整机压力。
        // setTargetFrameRate 是 API 33 才有的；机型不支持这个区间会抛，吞掉 —— 不能让它把相机搞挂。
        val previewBuilder = Preview.Builder()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                previewBuilder.setTargetFrameRate(android.util.Range(10, 10))
            } catch (e: Exception) {
                CrashReporter.report("warn", "预览降帧未生效（按默认帧率跑）：${e.message?.take(80)}")
            }
        }
        @Suppress("DEPRECATION")
        val preview = previewBuilder.setTargetResolution(Size(1280, 720)).build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val group = UseCaseGroup.Builder().addUseCase(preview)
        if (record) {
            // ⚠️ 绝对不能用 VideoCapture.Builder.setTargetResolution()：
            // 它是 @RestrictTo(LIBRARY_GROUP) 的死 API，调用即抛
            // UnsupportedOperationException("setTargetResolution is not supported.")。
            // 录制分辨率只能通过 Recorder 的 QualitySelector 指定。
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)   // 前置不支持 HD 时兜底
                    )
                )
                .build()
            val vc = VideoCapture.Builder(recorder).build()
            videoCapture = vc
            group.addUseCase(vc)
        }
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, group.build())
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
        // 语法分句（R11）：补句末标点、把碎片并回上一句；**不改字**，可关
        Punctuator.optimize(sentences, Prefs.punctMode(this))
        renderAll()
        updateCounters()

        // 红晕（§7.1）：命中代词 + 防抖 + 录制中 + 范围没关
        if (state == "recording" && s.pronouns.isNotEmpty() && Prefs.glowScope(this) != 2) {
            val now = System.currentTimeMillis()
            if (now - lastGlowAt >= Prefs.glowDebounceSec(this) * 1000L) {
                lastGlowAt = now
                fireGlow()
            }
        }
    }

    /**
     * 触发红晕。范围（R11）：
     *  0 = 只视频窗口四周一圈（默认，右栏文字区不受影响）
     *  1 = 整个界面四周一圈
     *  2 = 关闭（调用点已挡掉，这里再兜一次）
     */
    private fun fireGlow() {
        val times = Prefs.glowTimes(this)
        val strength = Prefs.glowStrength(this)
        when (Prefs.glowScope(this)) {
            // ②④ 档没有视频栏（左栏是隐藏的），这时以视频为范围就什么也看不见 → 退回整屏
            0 -> if (::glowVideo.isInitialized && ::leftPanel.isInitialized &&
                leftPanel.visibility == View.VISIBLE) glowVideo.glow.fire(times, strength)
            else glow.glow.fire(times, strength)
            1 -> glow.glow.fire(times, strength)
            else -> {}
        }
    }

    /** 两层红晕一起消掉（点屏 / 换会话时都要） */
    private fun clearGlow() {
        if (::glow.isInitialized) glow.glow.clearNow()
        if (::glowVideo.isInitialized) glowVideo.glow.clearNow()
    }

    private fun renderAll() {
        // 标注体系（§R9）：色彩 + 符号，全部走 Annotator，开关来自设置
        textArea.setContent(Annotator.build(sentences, chaptersDrawn, Prefs.annotationPrefs(this)))
    }

    /** 「整句」卡显示 说得完整的句数 / 总句数，后面那半用小一号灰字 */
    private fun updateCounters() {
        // 分母只数「独立句子」：被语法分句并回上一句的碎片不单独算一句（R11）
        val ok = sentences.count { it.counted && !it.uncertain }
        val total = sentences.count { it.counted }
        val sb = SpannableStringBuilder()
        sb.append(ok.toString())
        if (total > 0) {
            val st = sb.length
            sb.append("/").append(total.toString())
            sb.setSpan(RelativeSizeSpan(0.58f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(0xFF9AA7B0.toInt()), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tvSentCount.text = sb
        tvPronCount.text = sentences.sumOf { it.pronouns.size }.toString()
    }

    // ================= 暂停 / 停止（§12.2 步 6–7） =================

    private fun onPauseBtn() {
        when (state) {
            "recording" -> { audio?.pause(); videoRecording?.pause(); state = "paused" }
            "paused" -> {
                audio?.resume()
                videoRecording?.resume()
                state = "recording"
                if (mode == Prefs.MODE_FULL) {
                    // 暂停→继续要起新分段；旧录制还处于 pause 状态，必须先收尾再 start，
                    // 否则 prepareRecording 会抛 IllegalStateException。
                    try { videoRecording?.stop() } catch (_: Exception) {}
                    videoRecording = null
                    startVideoSegment()
                }
            }
        }
        updateButtons()
    }

    private fun onStopBtn(emotional: Boolean) {
        if (state != "recording" && state != "paused") return
        val wasMode = mode
        val dir = sessionDir
        val engine = audio
        val elapsed = engine?.elapsedMs() ?: 0
        try { videoRecording?.stop() } catch (_: Exception) {}
        videoRecording = null
        audio = null
        state = "idle"
        clearGlow()
        // 恢复屏幕亮度与刷新率（§13.2）
        window.attributes = window.attributes.apply {
            screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            if (android.os.Build.VERSION.SDK_INT >= 30) preferredRefreshRate = 0f
        }

        updateButtons()

        // 停止采集（join 最长 800ms）+ 转成 WAV（10 分钟 ≈ 19MB 字节）都是重活，
        // 全部放后台：原来在主线程做，长会话按「停止」会明显卡顿甚至 ANR。
        ioExecutor.execute {
            val pcm = engine?.stop()
            // ①② 档落盘 audio.wav；③④ 档一个字节都不写（§10.1）
            if (dir != null && pcm != null && (wasMode == Prefs.MODE_FULL || wasMode == Prefs.MODE_AUDIO)) {
                try {
                    val wav = AudioEngine(vadSilenceMs = 800, onFrame = { _, _ -> }, onSentence = { _, _, _ -> })
                        .toWav(pcm)
                    File(dir, "audio.wav").writeBytes(wav)
                } catch (e: Exception) {
                    CrashReporter.report("error", "audio.wav 写入失败 dir=$dir", e)
                }
            }
            main.post {
                if (emotional) {
                    // 情绪退出：跳过回放与判定，不产任何评价（§12.5）
                    if (dir != null) saveSession(elapsed, exitKind = "emotional")
                    AlertDialog.Builder(this).setMessage("没事，我们下次再来。")
                        .setPositiveButton("好", null).show()
                } else {
                    runJudgmentAndDiagnosis(elapsed)
                }
            }
        }
    }

    private fun saveSession(elapsedMs: Long, exitKind: String) {
        val dir = sessionDir ?: return
        val complete = sentences.count { it.judgeable && !it.selfMarked && it.flags.isEmpty() && it.pronouns.isEmpty() }
        val incomplete = sentences.count { it.isProblem }
        val selfMarked = sentences.count { it.selfMarked }
        store.writeTranscript(dir, sentences)
        store.writeMeta(dir, mode, sessionStartedAt, System.currentTimeMillis(), elapsedMs, take,
            sentences.sumOf { it.pronouns.size }, complete, incomplete, selfMarked, exitKind,
            modelsNote = if (asr.available) "纯规则模式" else asr.status,
            cropBias = previewBox.bias)   // 回放按「录的时候」的取景显示
        store.indexSession(SessionStore.Record(dir.name, sessionStartedAt, elapsedMs,
            sentences.sumOf { it.pronouns.size }, complete, incomplete, selfMarked, exitKind,
            dir.absolutePath))
    }

    // ================= 整句判断 → 回放 → 诊断（§12.3 / §12.4） =================

    /** 最近一次判定的总评语（诊断标题区展示用，§R9） */
    private var lastJudgeOverall: String? = null

    private fun runJudgmentAndDiagnosis(elapsedMs: Long) {
        val count = Prefs.incSessionCount(this)   // ③④ 档同样计数（§12.0）
        // 分级：自动按「第 N 次会话」递进，设置页可手动锁定（§8.6）
        val levels = RuleEngine.levels(count, Prefs.levelOverride(this))
        asrExecutor.execute {
            RuleEngine.evaluate(sentences, levels, Prefs.muzzyEnabled(this))
            // 离线自动判定（§R9）：规则后端默认工作；开了本地 Qwen 且 GGUF 就位则尝试升级，失败回退规则
            val res = QwenJudge.judge(
                sentences,
                useLlm = Prefs.llmEnabled(this),
                ggufPath = Prefs.newestLlm(this)?.absolutePath
            )
            lastJudgeOverall = res.overall
            for (s in sentences) {
                res.perSentence[s.index]?.let { if (it.isNotEmpty()) s.judge = it }
            }
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
                        // 回放取景 = 录这段时的偏移（不是「现在」的偏移）：
                        // mp4 是全幅，偏移只能别的途径带上，落在 meta.json 里。
                        replaySavedBias = previewBox.bias
                        previewBox.bias = readSessionBias(dir) ?: previewBox.bias
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

    /** 回放前临时换成了会话取景，回放完要还原成「当前」的（否则下一句 preview 会偏） */
    private var replaySavedBias: Float? = null

    private fun readSessionBias(dir: File): Float? = try {
        val o = org.json.JSONObject(File(dir, "meta.json").readText())
        if (o.has("cropBias")) o.optDouble("cropBias").toFloat() else null
    } catch (_: Exception) { null }

    private fun endReplay(showDiagnosis: Boolean) {
        replaySavedBias?.let { previewBox.bias = it }
        replaySavedBias = null
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
            tvDiagnosisTitle.text = lastJudgeOverall ?: "这段里没有发现要改的句子，说得挺好。"
        } else {
            // §R9：先给出整体评语（模型/规则生成），再列具体句子
            val overall = lastJudgeOverall ?: Annotator.summary(sentences)
            tvDiagnosisTitle.text = "$overall\n有 ${problems.size} 句可以更好："
            for (s in problems.take(6)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, 6, 0, 6)
                }
                val tv = TextView(this).apply {
                    textSize = 17f; setTextColor(0xFFFFFFFF.toInt())
                    text = "「${if (s.uncertain) "?" else s.text}」\n${s.judge ?: s.followUp}"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                }
                val btn = Button(this).apply {
                    text = "这句我没说清楚"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f)
                    setOnClickListener {
                        s.selfMarked = true     // §9.1：只做标注，从「不完整」计数移除
                        row.visibility = View.GONE
                        updateCounters()
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
        textArea.reset()
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
            },
            keepPcm = (mode == Prefs.MODE_FULL || mode == Prefs.MODE_AUDIO)
        )
        audio?.start()
        state = "recording"
        updateButtons()
    }

    // ================= 工具 =================

    /** 过热提醒阈值（电池温度 ℃）。电池温度比 SoC 结温温和些，所以卡在 42 而不是更低。
     *  ⚠️ const val 只能写在顶层 / object / companion，写在 Activity 里会编译不过。 */
    private val hotC = 42

    /**
     * 电池温度（℃）。用 ACTION_BATTERY_CHANGED 的 sticky 广播拿，**不需要任何权限**；
     * 个别机型不报这项，读不到就返回 null（提示照出，只是不带温度）。
     */
    private fun batteryTempC(): Int? {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val t = i?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (t == Int.MIN_VALUE) null else t / 10
    }

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
        try { audio?.stop() } catch (_: Exception) {}
        asr.release()
        asrExecutor.shutdown()
        ioExecutor.shutdown()
    }
}
