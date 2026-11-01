package com.oska.loudspeak

import android.graphics.Typeface
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
import org.json.JSONObject
import java.io.File

/**
 * 回放页（§3 / §6 / §7）。列表「回看」或录完就地回放跳转目标。
 * - ① 档：播放 video_seg*.mp4（多段自动拼接）+ 带声；② 档：只放 audio.wav。
 * - 七键：播放 / 暂停 / 停止 / 上句 / 下句 / 画面 / 返回。
 * - 画面三态：取景 → 全幅 → 关（只留音频）。
 *
 * 2026-10 改版（回应真机反馈）：
 *  - 文字显示区放大（字号 20sp + 占满纵向剩余空间）。
 *  - 可拖动的播放进度条。
 *  - 倍速改成**按键**（0.5 / 1.0 / 1.5 / 2.0x）放在左下角。
 *  - 去掉音波条。
 *  - 改名 / 删除移到左下角。
 *  - 画面「关」时左画面 GONE，右栏自动占满整屏（音频档同理：视频本就不存在，右栏直接占满）。
 *  - 全幅改为 contain（整幅显示，不裁切，整个视频都看得见）。
 */
class SessionViewActivity : LsActivity() {

    private lateinit var store: SessionStore
    private lateinit var controller: ReplayController
    private var sessionId = ""
    private lateinit var dir: File
    private var hasVideo = false
    private var videoMode = 0 // 0 取景 / 1 全幅 / 2 关

    private lateinit var titleTv: TextView
    private lateinit var timeTv: TextView
    private lateinit var videoView: VideoView
    private lateinit var videoFrame: FrameLayout
    private lateinit var btnPlay: Button
    private lateinit var btnPause: Button
    private lateinit var seekPos: SeekBar
    private var sentences: List<com.oska.loudspeak.core.RuleEngine.Sentence> = emptyList()
    /** 进度条拖动中：onPosition 回调别来回抢 */
    private var seekingPos = false
    private var currentSpeed = 1.0f
    private val speedButtons = mutableListOf<Button>()
    private var btnVideo: Button? = null

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
        val dur = meta?.optLong("durationMs") ?: 0
        hasVideo = File(dir, "audio.wav").exists() &&
                (dir.listFiles { f -> f.name.startsWith("video_seg") && f.name.endsWith(".mp4") }?.isNotEmpty() == true)
        if (!hasVideo) videoMode = 2

        val label = store.readTitle(dir) ?: store.sessionLabel(sessionId)

        // ---- 顶部：只放备注名 + 类型 + 时长（改名/删除已移到左下角）----
        titleTv = TextView(this).apply {
            text = "$label  ·  ${modeName(mode)}  ·  ${fmt(dur)}"
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(titleTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- 中部：左画面 + 右栏 ----
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // 左：画面（仅视频档；音频档与「关画面」时权重归零 + GONE，右栏自动占满全屏）
        videoFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, if (hasVideo) 26f else 0f)
        }
        videoView = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        if (hasVideo) videoFrame.addView(videoView) else videoFrame.visibility = View.GONE
        body.addView(videoFrame)

        // 右：转写 + 进度（去掉了音波条）
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

        body.addView(right)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底部：左下角控制（改名 / 删除 / 倍速按键）+ 七键 ----
        // 左下角一行：改名、删除、倍速按键
        val leftRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, pad / 2, 0, pad / 4)
        }
        leftRow.addView(mkBtn("改名") { doRename() })
        leftRow.addView(mkBtn("删除") { confirmDelete() })
        leftRow.addView(TextView(this).apply {
            text = "倍速"; textSize = 13f; setTextColor(0xFF9AA7B0.toInt())
            setPadding(pad, 0, (pad / 2), 0)
        })
        for (sp in listOf(0.5f, 1.0f, 1.5f, 2.0f)) {
            val b = mkBtn("${sp}x") {
                currentSpeed = sp
                controller.setSpeed(sp)
                highlightSpeed()
            }
            speedButtons.add(b)
            leftRow.addView(b)
        }
        root.addView(leftRow)

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
            setPadding(0, pad / 4, 0, 0)
            addView(btnPlay); addView(btnPause); addView(btnStop)
            addView(btnPrev); addView(btnNext); addView(btnVideo); addView(btnBack)
        }
        root.addView(bar)

        setContentView(root)

        // ---- 内核 ----
        controller = ReplayController(dir, hasVideo, sentences,
            onState = { updateButtons(it) },
            onPosition = { pos, total -> runOnUiThread {
                timeTv.text = "${fmt(pos)} / ${fmt(total)}"
                if (!seekingPos && total > 0) seekPos.progress = (pos * 1000 / total).toInt()
            } })
        if (hasVideo) controller.attachVideoView(videoView) else controller.attachAudio()

        // 进场 A：定位到首问题句开头（①）/ 0（②），不自动播
        val firstProblem = sentences.firstOrNull { it.flags.isNotEmpty() }?.tStartMs ?: 0L
        controller.seek(firstProblem)

        applyFraming()
        highlightSpeed()
        updateButtons(controller.currentState())
    }

    /** 高亮当前生效的倍速按键 */
    private fun highlightSpeed() {
        for (b in speedButtons) {
            val v = b.text.toString().removeSuffix("x").toFloatOrNull() ?: 1.0f
            b.setTypeface(null, if (v == currentSpeed) Typeface.BOLD else Typeface.NORMAL)
        }
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

    private fun cycleVideo() {
        if (!hasVideo) return
        videoMode = (videoMode + 1) % 3
        btnVideo?.text = when (videoMode) {
            0 -> "画面·取景"; 1 -> "画面·全幅"; else -> "画面·关"
        }
        applyFraming()
    }

    /**
     * 画面三态（交给系统 VideoScalingMode 处理，**不做手动 scale/translate** ——
     * 之前手动平移正是「全幅时约 85% 画面跑出视野」的根因）：
     *  0 取景 = 铺满裁切（cover，SCALE_TO_FIT_WITH_CROPPING）
     *  1 全幅 = 整幅显示（contain，SCALE_TO_FIT，整个视频都看得见）
     *  2 关   = 画面 GONE + 权重归零，右栏自动占满全屏（音频档同理）。
     */
    private fun applyFraming() {
        if (!hasVideo) return
        when (videoMode) {
            0 -> {
                videoFrame.visibility = View.VISIBLE
                videoFrame.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 26f)
                controller.setVideoScaling(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            }
            1 -> {
                videoFrame.visibility = View.VISIBLE
                videoFrame.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 26f)
                controller.setVideoScaling(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            }
            else -> {
                videoFrame.visibility = View.GONE
                videoFrame.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0f)
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
                val m = try { JSONObject(File(dir, "meta.json").readText()).optString("mode") } catch (_: Exception) { "?" }
                val d = try { JSONObject(File(dir, "meta.json").readText()).optLong("durationMs") } catch (_: Exception) { 0L }
                titleTv.text = "$newName  ·  ${modeName(m)}  ·  ${fmt(d)}"
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
