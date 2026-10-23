package com.oska.loudspeak

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.Ui

/** 四档选择（§6.4 模式键）：每档一句人话说明；开始前选定，会中不可切 */
class ModeSelectActivity : LsActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        val title = TextView(this).apply {
            text = "选择档位（会话开始后不可切换）"
            textSize = 22f; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title)

        val options = listOf(
            Triple(Prefs.MODE_FULL, "① 完整录制", "录视频+录音频，停止后可回看（占存储约 7MB/分钟）"),
            Triple(Prefs.MODE_AUDIO, "② 仅音频", "只录声音，停止后可回看"),
            Triple(Prefs.MODE_DISPLAY_VIDEO, "③ 纯显示·带画面（推荐默认）", "有画面有文字有判定，什么都不保存"),
            Triple(Prefs.MODE_DISPLAY_AUDIO, "④ 纯显示·音频", "只有声音和文字，什么都不保存")
        )
        for ((modeId, name, desc) in options) {
            val b = Button(this).apply {
                text = "$name\n$desc"
                textSize = 17f
                setOnClickListener {
                    Prefs.setMode(this@ModeSelectActivity, modeId)
                    finish()
                }
            }
            root.addView(b, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, pad / 2, 0, 0) })
        }
        Ui.hideStatusBar(this)
        setContentView(root)
    }
}
