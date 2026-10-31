package com.oska.loudspeak

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.Annotator
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.ReplayController
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.Ui
import com.oska.loudspeak.core.WaveformView
import org.json.JSONObject
import java.io.File

/**
 * 回放页（§3 / §6 / §7）。列表「回看」或录完就地回放跳转目标。
 * - ① 档：播放 video_seg*.mp4（多段自动拼接）+ 带声；② 档：只放 audio.wav。
 * - 七键：播放 / 暂停 / 停止 / 上句 / 下句 / 画面 / 返回。
 * - 画面三态：取景 → 全幅 → 关（只留音频）。
 * - 进场（用户选 A）：进页面即暂停，playhead 定位到首条问题句开头（①）/ 0（②），不自动出声。
 *
 * 2026-10 改版（回应真机反馈）：
 *  - 文字显示区放大（字号 20sp + 占满纵向剩余空间）。
 *  - 加一条**可拖动**的播放进度条（解决「不能调整播放位置」）。
 *  - 倍速改成**可拖动的小滑杆**（0.5x–2.0x，紧凑），且视频档也能调（VideoView.setPlaybackSpeed）。
 *  - 音波条保留（回放概览 + 播放头 + 句分隔）。
 *  - 视频画面**保持宽高比铺满（cover）**，不再被拉伸变形。
 */
class SessionViewActivity : LsActivity() {

    private lateinit var store: SessionStore
    private lateinit var controller: ReplayController
    private var sessionId = ""
    private lateinit var dir: File
    private var hasVideo = false
    private var cropBias = 0.0
    private var videoMode = 0 // 0 取景 / 1 全幅 / 2 关

    private lateinit var titleTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var wave: WaveformView
    private lateinit var videoView: VideoView
    private lateinit var videoFrame: FrameLayout
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var seekPos: SeekBar
    private lateinit var seekSpeed: SeekBar
    private lateinit var speedTv: TextView
    private var sentences: List<com.oska.loudspeak.core.RuleEngine.Sentence> = emptyList()
    /** 进度条拖动中：onPosition 回调别来回抢 */
    private var seekingPos = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.hideStatusBar(this)
        sessionId = intent.getStringExtra("sessionId") ?: run { finish(); return }
        store = SessionStore(this)

        val pad = (resources.displayMetrics.density * 10).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }

        dir = intent.getStringExtra("dirPath")?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(Prefs.sessionsRoot(this), sessionId)
        val meta = try {
            JSONObject(File(dir, "meta.json").readText())
        } catch (_: Exception) { null }

        val mode = meta?.optString("mode") ?: "?"
        cropBias = meta?.optDouble("cropBias", 0.0) ?: 0.0
        val dur = meta?.optLong("durationMs") ?: 0
        hasVideo = File(dir, "audio.wav").exists() &&
                (dir.listFiles { f -> f.name.startsWith("video_seg") && f.name.endsWith(".mp4") }?.isNotEmpty() == true)
        if (!hasVideo) videoMode = 2

        val label = store.readTitle(dir) ?: store.sessionLabel(sessionId)

        // ---- 顶部：备注名 + 类型 + 时长 + 改名 + 删除 ----
        titleTv = TextView(this).apply {
            text = "$label  ·  ${modeName(mode)}  ·  ${fmt(dur)}"
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnRename = Button(this).apply {
            text = "改名"; textSize = 12f
            setOnClickListener { doRename() }
        }
        val btnDelete = Button(this).apply {
            text = "删除"; textSize = 12f
            setOnClickListener { confirmDelete() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleTv); addView(btnRename); addView(btnDelete)
        }
        root.addView(top)

        // ---- 中部：左画面 + 右栏 ----
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // 左：画面
        videoFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 26f)
        }
        videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        if (hasVideo) videoFrame.addView(videoView) else videoFrame.visibility = View.GONE
        body.addView(videoFrame)

        // 右：转写 + 波形 + 进度 + 倍速 + 七键
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 74f)
            setPadding(pad / 2, 0, 0, 0)
        }

        sentences = store.loadSentences(dir)
        val chapters = (sentences.maxOfOrNull { it.tEndMs } ?: 0L) / 60000
        val annPrefs = Prefs.annotationPrefs(this)
        val tv = TextView(this).apply {
            text = Annotator.build(sentences, chapters.toInt(), annPrefs)
            textSize = 20f; setTextColor(0xFFDCE3E8.toInt())
            setLineSpacing(8f * resources.displayMetrics.density, 1.0f)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        Annotator.onRewriteToggle = { tv.text = Annotator.build(sentences, chapters.toInt(), annPrefs) }
        Annotator.attachInteraction(tv, annPrefs)
        val sc = ScrollView(this).apply { addView(tv) }
        right.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 音波条（回放概览 + 播放头 + 句分隔）
        val waveHost = object : View(this) {
            val wf = WaveformView(this)
            override fun onDraw(c: android.graphics.Canvas) = wf.onDraw(c)
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 60).toInt())
        }
        wave = waveHost.wf
        right.addView(waveHost)

        // 进度条（可拖动，调整播放位置）
        val posRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 2, 0, pad / 4)
        }
        seekPos = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            max = 1000; progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val total = controller.getTotalMs()
                        timeTv.text = "${fmt((p * total / 1000f).toLong())} / ${fmt(total)}"
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { seekingPos = true }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val total = controller.getTotalMs()
                    controller.seek((s?.progress ?: 0) * total / 1000)
                    seekingPos = false
                }
            })
        }
        timeTv = TextView(this).apply {
            text = "00:00 / 00:00"; textSize = 13f; setTextColor(0xFF9AA7B0.toInt())
            setPadding((pad * 0.6).toInt(), 0, 0, 0)
        }
        posRow.addView(seekPos); posRow.addView(timeTv)
        right.addView(posRow)

        // 倍速（紧凑滑杆，0.5x–2.0x，视频档也能调）
        val spdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 4, 0, pad / 2)
        }
        val spdLabel = TextView(this).apply { text = "倍速"; textSize = 13f; setTextColor(0xFF9AA7B0.toInt()) }
        seekSpeed = SeekBar(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            max = 6; progress = 2   // 默认 1.0x
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) applySpeedFromSeek()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { applySpeedFromSeek() }
            })
        }
        speedTv = TextView(this).apply {
            text = "1.0x"; textSize = 13f; setTextColor(0xFF9AA7B0.toInt())
            setPadding((pad * 0.6).toInt(), 0, 0, 0)
        }
        spdRow.addView(spdLabel); spdRow.addView(seekSpeed); spdRow.addView(speedTv)
        right.addView(spdRow)

        // 七键
        btnPlay = mkBtn("播放") { controller.play() }
        btnPause = mkBtn("暂停") { controller.pause() }
        val btnStop = mkBtn("停止") { controller.stop() }
        val btnPrev = mkBtn("上句") { gotoSentence(-1) }
        val btnNext = mkBtn("下句") { gotoSentence(1) }
        btnVideo = mkBtn(if (hasVideo) "画面·取景" else "画面·关") { cycleVideo() }.apply {
            if (!hasVideo) isEnabled = false
        }
        val btnBack = mkBtn("返回") { finish() }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnPlay); addView(btnPause); addView(btnStop)
            addView(btnPrev); addView(btnNext); addView(btnVideo); addView(btnBack)
        }
        right.addView(bar)

        body.addView(right)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // ---- 内核 ----
        controller = ReplayController(dir, hasVideo, sentences,
            onState = { updateButtons(it) },
            onPosition = { pos, total -> runOnUiThread {
                wave.playhead = if (total > 0) pos.toFloat() / total else 0f
                timeTv.text = "${fmt(pos)} / ${fmt(total)}"
                if (!seekingPos && total > 0) seekPos.progress = (pos * 1000 / total).toInt()
                wave.invalidate()
            } })
        wave.onSeek = { frac -> controller.seek((frac * controller.getTotalMs()).toLong()) }
        wave.onStartSeek = { frac -> wave.startFrac = frac }
        wave.onDoubleTap = { frac -> controller.seek((frac * controller.getTotalMs()).toLong()); controller.play() }
        wave.timeFormat = { frac -> "${fmt((frac * controller.getTotalMs()).toLong())} / ${fmt(controller.getTotalMs())}" }
        wave.sentenceFracs = sentences.map { if (controller.getTotalMs() > 0) it.tStartMs.toFloat() / controller.getTotalMs() else 0f }

        if (hasVideo) controller.attachVideoView(videoView) else controller.attachAudio()

        // 进场 A：定位到首问题句开头（①）/ 0（②），不自动播
        val firstProblem = sentences.firstOrNull { it.flags.isNotEmpty() }?.tStartMs ?: 0L
        controller.seek(firstProblem)

        videoFrame.post { applyFraming() }
        updateButtons(controller.currentState())
    }

    private fun applySpeedFromSeek() {
        val speed = 0.5f + (seekSpeed.progress * 0.25f)   // 0.5 / 0.75 / 1.0 / 1.25 / 1.5 / 1.75 / 2.0
        controller.setSpeed(speed)
        speedTv.text = "%.2fx".format(speed)
    }

    private fun mkBtn(text: String, fn: () -> Unit): Button =
        Button(this).apply {
            this.text = text; textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { fn() }
        }

    private fun updateButtons(s: ReplayController.State) {
        btnPlay.isEnabled = (s != ReplayController.State.PLAYING)
        btnPause.isEnabled = (s == ReplayController.State.PLAYING)
    }

    private fun gotoSentence(dir: Int) {
        val cur = controller.positionMs()
        val idx = if (dir < 0) controller.prevSentence(cur) else controller.nextSentence(cur)
        if (idx != null) controller.seekSentence(idx)
    }

    private var btnVideo: Button? = null

    private fun cycleVideo() {
        if (!hasVideo) return
        videoMode = (videoMode + 1) % 3
        btnVideo?.text = when (videoMode) {
            0 -> "画面·取景"; 1 -> "画面·全幅"; else -> "画面·关"
        }
        applyFraming()
    }

    /**
     * 取景：内层按 16:9 撑到 1280px，再平移挑 417px 竖条（1:1 像素，不缩放）；全幅整幅；关则隐去画面。
     * 关键修复：无论哪种模式都**保持宽高比铺满（cover）**，不再把视频拉伸到 FrameLayout 的异形比例 → 变形。
     */
    private fun applyFraming() {
        if (!hasVideo) return
        videoFrame.post {
            val fw = videoFrame.width.toFloat(); val fh = videoFrame.height.toFloat()
            // 录制原视频 1280×720（16:9），cover 铺满：取统一放大系数，居中，溢出部分裁掉（不变形）
            val cov = maxOf(fw / 1280f, fh / 720f)
            videoView.scaleX = cov; videoView.scaleY = cov
            val dw = 1280f * cov; val dh = 720f * cov
            val cx = (fw - dw) / 2f; val cy = (fh - dh) / 2f
            when (videoMode) {
                0 -> { // 取景：在 cover 基础上按 cropBias 横向平移，挑出脸部竖条（只平移、不缩放、不变形）
                    val shift = -cropBias.toFloat() * (dw - fw) / 2f
                    videoView.translationX = cx + shift
                    videoView.translationY = cy
                    videoFrame.visibility = View.VISIBLE
                }
                1 -> { // 全幅：纯 cover 居中
                    videoView.translationX = cx; videoView.translationY = cy
                    videoFrame.visibility = View.VISIBLE
                }
                else -> videoFrame.visibility = View.INVISIBLE
            }
        }
    }

    private fun doRename() {
        val cur = store.readTitle(dir) ?: store.sessionLabel(sessionId)
        val input = android.widget.EditText(this).apply { setText(cur); textSize = 16f }
        AlertDialog.Builder(this)
            .setTitle("改备注名（不改目录）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val newName = input.text.toString().trim()
                store.setTitle(dir, newName)
                store.setTitleInDb(sessionId, newName)
                val mode = try { JSONObject(File(dir, "meta.json").readText()).optString("mode") } catch (_: Exception) { "?" }
                val dur = try { JSONObject(File(dir, "meta.json").readText()).optLong("durationMs") } catch (_: Exception) { 0L }
                titleTv.text = "$newName  ·  ${modeName(mode)}  ·  ${fmt(dur)}"
                Toast.makeText(this, "已保存备注名", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除这条记录？")
            .setMessage("会移入回收站（保留 7 天，可在回收站恢复）。")
            .setPositiveButton("删除") { _, _ ->
                val err = store.softDelete(sessionId)
                if (err == null) { Toast.makeText(this, "已移入回收站", Toast.LENGTH_SHORT).show(); finish() }
                else Toast.makeText(this, "删除被拒绝：$err", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun modeName(m: String) = when (m) {
        Prefs.MODE_FULL -> "① 完整录制"
        Prefs.MODE_AUDIO -> "② 仅音频"
        Prefs.MODE_DISPLAY_VIDEO -> "③ 纯显示·带画面"
        else -> "④ 纯显示·音频"
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }

    override fun onPause() {
        super.onPause()
        if (controller.currentState() == ReplayController.State.PLAYING) controller.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.release()
    }
}
