package com.oska.loudspeak

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.SessionStore

/** 历史列表（§11.2）：时间倒序；单条删除走软删除+白名单校验（§11.4） */
class HistoryActivity : AppCompatActivity() {

    private lateinit var store: SessionStore
    private lateinit var adapter: BaseAdapter
    private var records: List<SessionStore.Record> = emptyList()

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

        val list = ListView(this)
        adapter = object : BaseAdapter() {
            override fun getCount() = records.size
            override fun getItem(p: Int) = records[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val r = records[p]
                val row = LinearLayout(this@HistoryActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, pad, 0, pad) }
                val tv = TextView(this@HistoryActivity).apply {
                    textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
                    text = buildString {
                        append(r.sessionId).append('\n')
                        append("%02d:%02d".format(r.durationMs / 60000, r.durationMs % 60000 / 1000))
                        append(" · 代词 ${r.pronounCount} · 完整 ${r.completeCount} · 不完整 ${r.incompleteCount}")
                        if (r.selfMarkedCount > 0) append(" · 自评 ${r.selfMarkedCount}")
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4f)
                }
                val del = Button(this@HistoryActivity).apply {
                    text = "删除"; textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        val err = store.softDelete(r.sessionId)
                        if (err == null) { Toast.makeText(this@HistoryActivity, "已移入回收站（保留 7 天）", Toast.LENGTH_SHORT).show(); load() }
                        else Toast.makeText(this@HistoryActivity, "删除被拒绝：$err", Toast.LENGTH_LONG).show()
                    }
                }
                row.addView(tv); row.addView(del)
                return row
            }
        }
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val back = Button(this).apply { text = "返回"; setOnClickListener { finish() } }
        root.addView(back)
        setContentView(root)
        load()
    }

    private fun load() {
        records = store.listSessions()
        adapter.notifyDataSetChanged()
    }
}
