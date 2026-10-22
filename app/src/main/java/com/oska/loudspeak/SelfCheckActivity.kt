package com.oska.loudspeak

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.BufferedReader
import java.io.FileReader

/**
 * B0 内存自检页：读出本机实测参数，对照设计规格（§1.3 / §13）给出参考值。
 * 仅用于真机验证，不影响任何业务采集逻辑。
 */
class SelfCheckActivity : AppCompatActivity() {

    private lateinit var reportContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_self_check)
        reportContainer = findViewById(R.id.reportContainer)

        findViewById<View>(R.id.btnRefresh).setOnClickListener { runReport() }
        findViewById<View>(R.id.btnGoCapture).setOnClickListener {
            startActivity(Intent(this, CaptureVerifyActivity::class.java))
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        runReport()
    }

    private fun runReport() {
        reportContainer.removeAllViews()
        section("设备")
        kv("机型", "${Build.MANUFACTURER} ${Build.MODEL}")
        kv("设备代号", Build.DEVICE)
        kv("系统", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        kv("期望机型", "OPPO A3x 5G (CPH2681) / Android 14")

        section("SoC")
        val soc = readSoc()
        kv("硬件标识", soc)
        kv("期望 SoC", "联发科 天玑 6300（无 NPU，仅 2×A76）")

        section("屏幕")
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val ppi = if (dm.xdpi > 0 && dm.ydpi > 0) {
            ((dm.xdpi + dm.ydpi) / 2f).toInt()
        } else dm.densityDpi
        kv("实测分辨率", "${w}×${h}（${if (w >= h) "横屏" else "竖屏"}）")
        kv("期望分辨率", "1604×720（横屏）")
        kv("像素密度", "${dm.densityDpi} dpi")
        kv("期望 ppi", "约 264 ppi")
        kv("换算 ppi(实测)", "$ppi ppi")

        section("内存（系统）")
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalGb = mi.totalMem / 1024f / 1024f / 1024f
        val availGb = mi.availMem / 1024f / 1024f / 1024f
        val thresholdMb = mi.threshold / 1024f / 1024f
        kv("总内存", "%.2f GB".format(totalGb))
        kv("期望总内存", "6.00 GB (LPDDR4X)")
        kv("当前可用", "%.2f GB".format(availGb))
        kv("LMK 阈值", "%.0f MB".format(thresholdMb))
        kv("系统低内存?", if (mi.lowMemory) "⚠️ 是" else "否")

        section("内存（本进程）")
        val pid = android.os.Process.myPid()
        val pmi = am.getProcessMemoryInfo(intArrayOf(pid))[0]
        val pssMb = pmi.totalPss / 1024f
        val dalvikMb = pmi.dalvikPss / 1024f
        val nativeMb = pmi.nativePss / 1024f
        kv("进程 PSS", "%.1f MB".format(pssMb))
        kv("├ Java 堆 PSS", "%.1f MB".format(dalvikMb))
        kv("├ Native PSS", "%.1f MB".format(nativeMb))
        kv("期望安全线", "2.0 GB（设计上限，§13.0）")
        kv("余量判断", if (pssMb < 400) "✅ 空闲水位，正常" else "⚠️ 偏高")

        section("Java 堆")
        val rt = Runtime.getRuntime()
        val maxMb = rt.maxMemory() / 1024f / 1024f
        val totalMb = rt.totalMemory() / 1024f / 1024f
        val freeMb = rt.freeMemory() / 1024f / 1024f
        kv("堆上限", "%.0f MB".format(maxMb))
        kv("已分配", "%.0f MB".format(totalMb))
        kv("空闲", "%.0f MB".format(freeMb))

        section("备注")
        note("如需对照 P0 验证项 5/8，请录一段时间后用 adb shell dumpsys meminfo $packageName 取 PSS 曲线。")
    }

    private fun section(title: String) {
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 16f
        tv.setTextColor(ContextCompat.getColor(this, R.color.accent))
        tv.setPadding(0, 16, 0, 4)
        reportContainer.addView(tv)
    }

    private fun kv(label: String, value: String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 2, 0, 2) }

        val l = TextView(this)
        l.text = label
        l.textSize = 15f
        l.setTextColor(ContextCompat.getColor(this, R.color.label))
        l.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        val v = TextView(this)
        v.text = value
        v.textSize = 15f
        v.setTextColor(ContextCompat.getColor(this, R.color.white))
        v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f)

        row.addView(l)
        row.addView(v)
        reportContainer.addView(row)
    }

    private fun note(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14f
        tv.setTextColor(ContextCompat.getColor(this, R.color.label))
        tv.setPadding(0, 4, 0, 4)
        reportContainer.addView(tv)
    }

    private fun readSoc(): String {
        return try {
            val txt = BufferedReader(FileReader("/proc/cpuinfo")).use { it.readText() }
            val hw = Regex("Hardware\\s*:\\s*(.+)").find(txt)?.groupValues?.get(1)?.trim()
            val proc = Regex("model name\\s*:\\s*(.+)").find(txt)?.groupValues?.get(1)?.trim()
            hw ?: proc ?: "未知"
        } catch (e: Exception) {
            "读取失败"
        }
    }
}
