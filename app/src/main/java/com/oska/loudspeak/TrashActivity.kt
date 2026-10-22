package com.oska.loudspeak

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.Ui
import java.io.File

/** 回收站（§11.4）：软删除的会话在这里，保留 7 天；可逐条恢复，清空需确认 */
class TrashActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var host: LinearLayout
    private val padDp = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        val pad = (resources.displayMetrics.density * padDp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        root.addView(TextView(this).apply {
            text = "回收站（保留 7 天，到期自动清）"
            textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
        })
        host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(host) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(Button(this).apply {
            text = "清空回收站"
            setOnClickListener {
                AlertDialog.Builder(this@TrashActivity)
                    .setTitle("永久删除回收站里全部内容？")
                    .setMessage("此操作不可恢复")
                    .setPositiveButton("永久删除") { _, _ ->
                        store.emptyTrash(); rebuild(); Toast.makeText(context, "已清空", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("取消", null).show()
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(Button(this).apply {
            text = "返回"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // ⚠️ 不能再包一层 ScrollView：外层给 root 的是 UNSPECIFIED 高度，
        // 里面 ScrollView 的 layout_weight=1 会失效 → 底部按钮被列表顶出屏幕。
        Ui.hideStatusBar(this)
        setContentView(root)
        rebuild()
    }

    private fun rebuild() {
        host.removeAllViews()
        val items = store.listTrash()
        if (items.isEmpty()) {
            host.addView(TextView(this).apply {
                text = "回收站是空的"; textSize = 15f; setTextColor(0xFF9AA7B0.toInt())
            })
            return
        }
        val pad = (resources.displayMetrics.density * padDp).toInt()
        for (t in items) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad, 0, pad) }
            row.addView(TextView(this).apply {
                textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
                text = "${t.dir.name}\n删除于 ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(t.deletedAt))}"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
            })
            row.addView(Button(this).apply {
                text = "恢复"; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    val err = store.restoreFromTrash(t)
                    Toast.makeText(this@TrashActivity, err ?: "已恢复到历史列表", Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            })
            row.addView(Button(this).apply {
                text = "永久删"; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    AlertDialog.Builder(this@TrashActivity)
                        .setTitle("永久删除这条记录？").setMessage("不可恢复")
                        .setPositiveButton("永久删除") { _, _ -> store.purgeTrashItem(t); rebuild() }
                        .setNegativeButton("取消", null).show()
                }
            })
            host.addView(row)
        }
    }
}
