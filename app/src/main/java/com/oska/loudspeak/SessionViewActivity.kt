package com.oska.loudspeak

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.Annotator
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.Ui
import java.io.File

/**
 * 历史「查看」（§R9：视频/音频的调用与查看）。
 * - ① 档：播放 video_seg*.mp4（带画面带声）；② 档：只放 audio.wav。
 * - 下方用标注体系（Annotator）重新排版文字，和录音时看到的一致。
 * - 提供「删除（移入回收站）」与「返回」。
 */
class SessionViewActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private var mediaPlayer: MediaPlayer? = null
    private var sessionId: String = ""

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

        // 优先用历史列表传过来的真实目录：改过存放位置后，老记录可能还在旧目录里，
        // 只按「当前根 + sessionId」拼会找不到文件。
        val dir = intent.getStringExtra("dirPath")?.let { File(it) }?.takeIf { it.isDirectory }
            ?: File(Prefs.sessionsRoot(this), sessionId)
        val meta = try {
            org.json.JSONObject(File(dir, "meta.json").readText())
        } catch (_: Exception) { null }

        val label = store.sessionLabel(sessionId)
        val mode = meta?.optString("mode") ?: "?"
        val dur = (meta?.optLong("durationMs") ?: 0)
        val stats = meta?.optJSONObject("stats")

        val title = TextView(this).apply {
            text = "$label  ·  ${modeName(mode)}\n" +
                    "%02d:%02d · 代词 ${stats?.optInt("pronounCount") ?: 0} · 完整 ${stats?.optInt("completeCount") ?: 0} · 不完整 ${stats?.optInt("incompleteCount") ?: 0}"
                        .format(dur / 60000, dur % 60000 / 1000)
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title)

        // 视频 / 音频
        val video = dir.listFiles()?.firstOrNull { it.name.startsWith("video_seg") }
        val videoView = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.density * 220).toInt())
        }
        if (video != null) {
            root.addView(videoView)
            videoView.setVideoURI(Uri.fromFile(video))
            videoView.setOnPreparedListener { it.start() }
        } else {
            val audioFile = File(dir, "audio.wav")
            val hint = TextView(this).apply {
                text = if (audioFile.isFile) "♪ 仅音频，点击播放 / 暂停" else "（没有音视频文件）"
                textSize = 15f; setTextColor(0xFF9AA7B0.toInt()); setPadding(0, pad / 2, 0, pad / 2)
            }
            root.addView(hint)
            if (audioFile.isFile) {
                hint.setOnClickListener {
                    if (mediaPlayer?.isPlaying == true) mediaPlayer?.pause()
                    else {
                        if (mediaPlayer == null) mediaPlayer = MediaPlayer().apply { setDataSource(audioFile.absolutePath); prepare() }
                        mediaPlayer?.start()
                    }
                }
            }
        }

        // 标注文字（和录音时一致）
        val sentences = store.loadSentences(dir)
        val chapters = (sentences.maxOfOrNull { it.tEndMs } ?: 0L) / 60000
        val tv = TextView(this).apply {
            text = Annotator.build(sentences, chapters.toInt(), Prefs.annotationPrefs(this@SessionViewActivity))
            textSize = 18f; setTextColor(0xFFDCE3E8.toInt())
            setLineSpacing(8f * resources.displayMetrics.density, 1.0f)
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val sc = ScrollView(this).apply { addView(tv) }
        root.addView(sc, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 操作栏
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(Button(this).apply {
            text = "删除（移入回收站）"
            setOnClickListener { confirmDelete() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        setContentView(root)
    }

    private fun modeName(m: String) = when (m) {
        Prefs.MODE_FULL -> "① 完整录制"
        Prefs.MODE_AUDIO -> "② 仅音频"
        Prefs.MODE_DISPLAY_VIDEO -> "③ 纯显示·带画面"
        else -> "④ 纯显示·音频"
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

    override fun onDestroy() {
        super.onDestroy()
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }
}
