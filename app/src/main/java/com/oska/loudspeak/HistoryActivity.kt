package com.oska.loudspeak

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.Ui
import org.json.JSONObject
import java.io.File

/**
 * 历史列表（§11.2 / §11.3 / §R9）。
 *
 * 能力：
 *  - 排序：时间倒序 / 不完整句最多优先（E5，优先复习讲得最差的）
 *  - 筛选：全部 / 仅星标
 *  - 每行：星标（标记重点，便于回看）+ 查看 + 长按看详情（meta 全字段）
 *  - 多选批量删除（走软删除，进回收站可恢复）
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var adapter: BaseAdapter
    private var records: List<SessionStore.Record> = emptyList()
    /** 多选删除：被选中的 sessionId 集合 */
    private val selected = LinkedHashSet<String>()

    private var order = SessionStore.ORDER_TIME
    private var onlyStar = false

    private lateinit var btnDeleteSel: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnSort: Button
    private lateinit var btnFilter: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        store.cleanTrash()

        val pad = (resources.displayMetrics.density * 10).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }

        // 顶部：标题 + 排序 + 筛选
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "历史练习"
            textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnSort = Button(this).apply {
            text = "排序·时间"; textSize = 12f
            setOnClickListener {
                order = if (order == SessionStore.ORDER_TIME) SessionStore.ORDER_INCOMPLETE
                else SessionStore.ORDER_TIME
                load()
            }
        }
        btnFilter = Button(this).apply {
            text = "全部"; textSize = 12f
            setOnClickListener {
                onlyStar = !onlyStar
                selected.clear()
                load()
            }
        }
        val btnTrash = Button(this).apply {
            text = "回收站"; textSize = 12f
            setOnClickListener { startActivity(Intent(this@HistoryActivity, TrashActivity::class.java)) }
        }
        top.addView(title); top.addView(btnSort); top.addView(btnFilter); top.addView(btnTrash)
        root.addView(top)

        val tvEmpty = TextView(this).apply {
            text = "还没有记录。①② 档练习结束后会出现在这里；③④ 档按设计什么都不保存。"
            textSize = 16f; setTextColor(0xFF9AA7B0.toInt()); visibility = View.GONE
        }
        val list = ListView(this)
        list.emptyView = tvEmpty
        adapter = object : BaseAdapter() {
            override fun getCount() = records.size
            override fun getItem(p: Int) = records[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = records[p]
                val row = LinearLayout(this@HistoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, pad, 0, pad)
                }
                val cb = CheckBox(this@HistoryActivity).apply {
                    isChecked = selected.contains(r.sessionId)
                    setOnCheckedChangeListener { _, v ->
                        if (v) selected.add(r.sessionId) else selected.remove(r.sessionId)
                        refreshSelButtons()
                    }
                }
                // 星标：自己标的重点，跟「讲得好不好」无关，纯收藏
                val star = TextView(this@HistoryActivity).apply {
                    text = if (r.star) "★" else "☆"
                    textSize = 22f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (r.star) 0xFFFFC046.toInt() else 0xFF5A6771.toInt())
                    setPadding(pad, pad / 2, pad, pad / 2)
                    setOnClickListener {
                        val now = !r.star
                        store.setStar(r.sessionId, now)
                        Toast.makeText(this@HistoryActivity,
                            if (now) "已标重点" else "已取消重点", Toast.LENGTH_SHORT).show()
                        load()
                    }
                }
                val tv = TextView(this@HistoryActivity).apply {
                    textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
                    text = buildString {
                        append(store.sessionLabel(r.sessionId))
                        append("　").append("%02d:%02d".format(r.durationMs / 60000, r.durationMs % 60000 / 1000))
                        append('\n')
                        append("代词 ").append(r.pronounCount)
                        append(" · 完整 ").append(r.completeCount)
                        append(" · 不完整 ").append(r.incompleteCount)
                        if (r.selfMarkedCount > 0) append(" · 自评 ").append(r.selfMarkedCount)
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f)
                    setPadding(pad / 2, 0, pad / 2, 0)
                }
                // 长按看详情（§11.3：meta 全字段）
                tv.setOnLongClickListener { showDetail(r); true }
                // 点整行切换选中
                tv.setOnClickListener {
                    if (selected.contains(r.sessionId)) selected.remove(r.sessionId) else selected.add(r.sessionId)
                    notifyDataSetChanged(); refreshSelButtons()
                }
                val view = Button(this@HistoryActivity).apply {
                    text = "查看"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        startActivity(Intent(this@HistoryActivity, SessionViewActivity::class.java)
                            .putExtra("sessionId", r.sessionId)
                            .putExtra("dirPath", r.dirPath))
                    }
                }
                row.addView(cb); row.addView(star); row.addView(tv); row.addView(view)
                return row
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tvEmpty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 底部操作栏
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnSelectAll = Button(this).apply {
            text = "全选"; textSize = 13f
            setOnClickListener {
                if (selected.size == records.size) selected.clear() else selected.addAll(records.map { it.sessionId })
                adapter.notifyDataSetChanged(); refreshSelButtons()
            }
        }
        btnDeleteSel = Button(this).apply {
            text = "删除选中(0)"; textSize = 13f; isEnabled = false
            setOnClickListener { deleteSelected() }
        }
        val btnBack = Button(this).apply {
            text = "返回"; textSize = 13f
            setOnClickListener { finish() }
        }
        bar.addView(btnSelectAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnDeleteSel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f))
        bar.addView(btnBack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        Ui.hideStatusBar(this)
        setContentView(root)
        load()
    }

    private fun refreshSelButtons() {
        btnDeleteSel.text = "删除选中(${selected.size})"
        btnDeleteSel.isEnabled = selected.isNotEmpty()
        btnSelectAll.text = if (selected.size == records.size && records.isNotEmpty()) "取消全选" else "全选"
        btnSort.text = if (order == SessionStore.ORDER_TIME) "排序·时间" else "排序·最差"
        btnFilter.text = if (onlyStar) "仅重点" else "全部"
        btnFilter.alpha = if (onlyStar) 1f else 0.75f
    }

    /** 批量软删除（§11.4）：逐条校验后移入回收站；按设置可选只删音视频保留文字 */
    private fun deleteSelected() {
        if (selected.isEmpty()) return
        val n = selected.size
        val keepText = Prefs.keepTextOnDelete(this)
        AlertDialog.Builder(this)
            .setTitle("删除选中的 $n 条？")
            .setMessage(
                if (keepText) "会移入回收站（保留 ${Prefs.TRASH_KEEP_DAYS} 天，可恢复）；当前设置为只删音视频、保留转写文字。"
                else "会移入回收站（保留 ${Prefs.TRASH_KEEP_DAYS} 天，可在回收站恢复）。")
            .setPositiveButton("删除") { _, _ ->
                var ok = 0
                selected.toList().forEach { if (store.softDelete(it, keepText) == null) ok++ }
                Toast.makeText(this, "已移入回收站 $ok 条", Toast.LENGTH_SHORT).show()
                selected.clear(); load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 单条详情（§11.3）：把 meta.json 里能看懂的全摆出来 */
    private fun showDetail(r: SessionStore.Record) {
        val meta = try {
            val f = File(r.dirPath, "meta.json")
            if (f.isFile) JSONObject(f.readText()) else null
        } catch (_: Exception) { null }
        val size = store.dirSizeBytes(File(r.dirPath))
        val hasVideo = File(r.dirPath).listFiles { f: File -> f.name.endsWith(".mp4") }?.isNotEmpty() == true
        val hasAudio = File(r.dirPath).listFiles { f: File -> f.name.endsWith(".wav") }?.isNotEmpty() == true
        val msg = buildString {
            append("会话：").append(r.sessionId).append('\n')
            append("开始：").append(store.sessionLabel(r.sessionId)).append('\n')
            append("时长：").append("%02d:%02d".format(r.durationMs / 60000, r.durationMs % 60000 / 1000)).append('\n')
            append("档位：").append(modeName(meta?.optString("mode"))).append('\n')
            append("退出：").append(if (r.exitKind == "emotion") "长按停止（情绪退出）" else "正常结束").append('\n')
            append("第几遍：").append(meta?.optInt("take", 1) ?: 1).append('\n')
            append("代词：").append(r.pronounCount).append(" · 完整：").append(r.completeCount)
            append(" · 不完整：").append(r.incompleteCount)
            if (r.selfMarkedCount > 0) append(" · 自评：").append(r.selfMarkedCount)
            append('\n')
            append("文件：").append(if (hasVideo) "视频 ✓ " else "").append(if (hasAudio) "音频 ✓ " else "")
            append("· %.1fMB".format(size / 1e6)).append('\n')
            append("路径：").append(r.dirPath).append('\n')
            meta?.let {
                val models = it.optJSONObject("models")
                append("模型：").append(models?.optString("asr") ?: "-")
                append(" · 降级：").append(models?.optString("degraded") ?: "-").append('\n')
                append("取景偏移：").append(it.optDouble("cropBias", 0.0)).append('\n')
            }
            append("重点：").append(if (r.star) "已标 ★" else "未标 ☆")
        }
        AlertDialog.Builder(this)
            .setTitle("会话详情")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .setNeutralButton("标重点/取消") { _, _ ->
                store.setStar(r.sessionId, !r.star); load()
            }
            .show()
    }

    private fun modeName(m: String?): String = when (m) {
        Prefs.MODE_FULL -> "① 完整录制"
        Prefs.MODE_AUDIO -> "② 仅音频"
        Prefs.MODE_DISPLAY_VIDEO -> "③ 纯显示·带画面"
        Prefs.MODE_DISPLAY_AUDIO -> "④ 纯显示·音频"
        else -> "-"
    }

    private fun load() {
        records = store.listSessions(order = order, onlyStar = onlyStar)
        adapter.notifyDataSetChanged()
        refreshSelButtons()
        if (records.isEmpty() && onlyStar) {
            Toast.makeText(this, "还没有标★的会话", Toast.LENGTH_SHORT).show()
        }
    }
}
