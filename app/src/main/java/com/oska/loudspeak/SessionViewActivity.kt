package com.oska.loudspeak

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var btnSpeed: Button
    private lateinit var btnVideo: Button
    private var sentences: List<com.oska.loudspeak.core.RuleEngine.Sentence> = emptyList()

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

        // ---- 中部：左画面 + 右四区 ----
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

        // 右：转写 + 波形 + 时间 + 倍速 + 七键
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 73f)
            setPadding(pad / 2, 0, 0, 0)
        }

        sentences = store.loadSentences(dir)
        val chapters = (sentences.maxOfOrNull { it.tEndMs } ?: 0L) / 60000
        val annPrefs = Prefs.annotationPrefs(this)
        val tv = TextView(this).apply {
            text = Annotator.build(sentences, chapters.toInt(), annPrefs)
            textSize = 18f; setTextColor(0xFFDCE3E8.toInt())
            setLineSpacing(8f * resources.displayMetrics.density, 1.0f)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        Annotator.onRewriteToggle = { tv.text = Annotator.build(sentences, chapters.toInt(), annPrefs) }
        Annotator.attachInteraction(tv, annPrefs)
        val sc = ScrollView(this).apply { addView(tv) }
        right.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        wave = WaveformView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.density * 56).toInt())
        })
        right.addView(wave)

        timeTv = TextView(this).apply {
            text = "00:00 / 00:00"; textSize = 13f; setTextColor(0xFF9AA7B0.toInt())
            setPadding(0, pad / 2, 0, pad / 2)
        }
        right.addView(timeTv)

        btnSpeed = Button(this).apply {
            text = "倍速 1.0x"; textSize = 12f; isEnabled = !hasVideo
            setOnClickListener { cycleSpeed() }
        }
        right.addView(btnSpeed)

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

    private var speedIdx = 1 // 0=0.75 1=1.0 2=1.25 3=1.5
    private val speeds = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f)
    private fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        controller.setSpeed(speeds[speedIdx])
        btnSpeed.text = "倍速 ${speeds[speedIdx]}x"
    }

    private fun cycleVideo() {
        if (!hasVideo) return
        videoMode = (videoMode + 1) % 3
        btnVideo.text = when (videoMode) {
            0 -> "画面·取景"; 1 -> "画面·全幅"; else -> "画面·关"
        }
        applyFraming()
    }

    /** 取景：内层按 16:9 撑到 1280px，再平移挑 417px 竖条（1:1 像素，不缩放）；全幅整幅；关则隐去画面 */
    private fun applyFraming() {
        if (!hasVideo) return
        videoFrame.post {
            when (videoMode) {
                0 -> {
                    val sx = 1280f / 417f
                    videoView.scaleX = sx; videoView.scaleY = 1f
                    val overflow = (sx - 1f) * videoFrame.width
                    videoView.translationX = -((1f - cropBias.toFloat()) / 2f) * overflow
                    videoFrame.visibility = View.VISIBLE
                }
                1 -> {
                    videoView.scaleX = 1f; videoView.scaleY = 1f; videoView.translationX = 0f
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
