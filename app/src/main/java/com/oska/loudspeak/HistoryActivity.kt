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

/** 历史列表（§11.2 / §R9）：时间倒序；支持「查看」与多选批量删除（移入回收站） */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var adapter: BaseAdapter
    private var records: List<SessionStore.Record> = emptyList()
    /** 多选删除：被选中的 sessionId 集合 */
    private val selected = LinkedHashSet<String>()

    private lateinit var btnDeleteSel: Button
    private lateinit var btnSelectAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SessionStore(this)
        store.cleanTrash()

        val pad = (resources.displayMetrics.density * 10).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.setBackgroundColor(0xFF101418.toInt())

        val title = TextView(this).apply {
            text = "历史练习（仅 ①② 档；③④ 档不留档）"
            textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
        }
        root.addView(title)

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
                    setPadding(0, pad, 0, pad)
                }
                val cb = CheckBox(this@HistoryActivity).apply {
                    isChecked = selected.contains(r.sessionId)
                    setOnCheckedChangeListener { _, v ->
                        if (v) selected.add(r.sessionId) else selected.remove(r.sessionId)
                        refreshSelButtons()
                    }
                }
                val tv = TextView(this@HistoryActivity).apply {
                    textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
                    text = buildString {
                        append(store.sessionLabel(r.sessionId)).append('\n')
                        append("%02d:%02d".format(r.durationMs / 60000, r.durationMs % 60000 / 1000))
                        append(" · 代词 ${r.pronounCount} · 完整 ${r.completeCount} · 不完整 ${r.incompleteCount}")
                        if (r.selfMarkedCount > 0) append(" · 自评 ${r.selfMarkedCount}")
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f)
                }
                val view = Button(this@HistoryActivity).apply {
                    text = "查看"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        startActivity(Intent(this@HistoryActivity, SessionViewActivity::class.java)
                            .putExtra("sessionId", r.sessionId))
                    }
                }
                // 点整行（除按钮外）切换选中
                tv.setOnClickListener {
                    if (selected.contains(r.sessionId)) selected.remove(r.sessionId) else selected.add(r.sessionId)
                    notifyDataSetChanged(); refreshSelButtons()
                }
                row.addView(cb); row.addView(tv); row.addView(view)
                return row
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(tvEmpty, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 底部操作栏：全选 / 删除选中 / 回收站 / 返回
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
        val btnTrash = Button(this).apply {
            text = "回收站"; textSize = 13f
            setOnClickListener { startActivity(Intent(this@HistoryActivity, TrashActivity::class.java)) }
        }
        val btnBack = Button(this).apply {
            text = "返回"; textSize = 13f
            setOnClickListener { finish() }
        }
        bar.addView(btnSelectAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnDeleteSel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(btnTrash, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
    }

    /** 批量软删除（§11.4）：逐条校验后移入回收站 */
    private fun deleteSelected() {
        if (selected.isEmpty()) return
        val n = selected.size
        AlertDialog.Builder(this)
            .setTitle("删除选中的 $n 条？")
            .setMessage("会移入回收站（保留 7 天，可在回收站恢复）。")
            .setPositiveButton("删除") { _, _ ->
                var ok = 0
                selected.toList().forEach { if (store.softDelete(it) == null) ok++ }
                Toast.makeText(this, "已移入回收站 $ok 条", Toast.LENGTH_SHORT).show()
                selected.clear(); load()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun load() {
        records = store.listSessions()
        adapter.notifyDataSetChanged()
        refreshSelButtons()
    }
}
