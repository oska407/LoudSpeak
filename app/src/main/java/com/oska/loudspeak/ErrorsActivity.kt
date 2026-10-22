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
import com.oska.loudspeak.core.CrashReporter
import com.oska.loudspeak.core.Ui
import java.io.File

/** 错误报告页（调试用）：列出 crash/error 报告，点开看详情，可清空。完全离线，只在本机。 */
class ErrorsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 10).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        root.addView(TextView(this).apply {
            text = "错误报告（本机调试用，不上传）"
            textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
        })

        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listHost) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val clear = Button(this).apply {
            text = "清空全部报告"
            setOnClickListener {
                AlertDialog.Builder(this@ErrorsActivity)
                    .setTitle("清空全部报告？")
                    .setPositiveButton("清空") { _, _ -> CrashReporter.clear(); rebuild(listHost, pad) }
                    .setNegativeButton("取消", null).show()
            }
        }
        val back = Button(this).apply { text = "返回"; setOnClickListener { finish() } }
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btns.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btns)

        // 同上：root 内部自带 ScrollView（weight=1），外层再包会让权重失效
        Ui.hideStatusBar(this)
        setContentView(root)
        rebuild(listHost, pad)
    }

    private fun rebuild(host: LinearLayout, pad: Int) {
        host.removeAllViews()
        val reports = CrashReporter.list()
        if (reports.isEmpty()) {
            host.addView(TextView(this).apply {
                text = "没有错误报告。出问题时这里会出现 crash-*.txt / error-*.txt，用于调试。"
                textSize = 15f; setTextColor(0xFF9AA7B0.toInt())
            })
            return
        }
        for (f in reports) {
            val btn = Button(this).apply {
                text = f.name + if (f.name.startsWith("crash")) " ⚠" else ""
                textSize = 14f
                setOnClickListener {
                    AlertDialog.Builder(this@ErrorsActivity)
                        .setTitle(f.name)
                        .setMessage(CrashReporter.read(f))
                        .setPositiveButton("删除该条") { _, _ -> f.delete(); rebuild(host, pad) }
                        .setNeutralButton("关闭", null)
                        .show()
                }
            }
            host.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, pad / 2, 0, 0) })
        }
    }
}
