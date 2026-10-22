package com.oska.loudspeak

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.oska.loudspeak.core.AsrEngine
import com.oska.loudspeak.core.Prefs

/**
 * 首次引导（§12.1，单页三段式）：
 * 1 权限与承诺  2 模型校验结果  3 给孩子看的三句话（点「我知道了」才结束）
 */
class FirstRunActivity : AppCompatActivity() {

    private lateinit var tvModel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 14).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        root.setBackgroundColor(0xFF101418.toInt())

        fun big(t: String, size: Float, color: Int = 0xFFFFFFFF.toInt()) =
            TextView(this).apply { text = t; textSize = size; setTextColor(color); setPadding(0, pad / 2, 0, pad / 4) }

        root.addView(big("① 这里不打分，也不会传到任何地方去。", 22f, 0xFF4ADE80.toInt()))
        root.addView(big("② 录像和录音都是你的，只有你能删。", 22f, 0xFF4ADE80.toInt()))
        root.addView(big("③ 任何时候都可以按「停止」，不想讲了我们下次再来。", 22f, 0xFF4ADE80.toInt()))

        root.addView(big("— 使用前检查 —", 16f, 0xFF9AA7B0.toInt()))

        val btnMic = Button(this).apply {
            text = "授权麦克风（四档都必须）"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@FirstRunActivity, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    it.isEnabled = false; text = "麦克风 ✓"
                } else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 300)
            }
        }
        root.addView(btnMic)

        tvModel = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) }
        root.addView(tvModel)
        val btnModel = Button(this).apply {
            text = "去设置里扫描 / 导入模型"
            setOnClickListener { startActivity(Intent(this@FirstRunActivity, SettingsActivity::class.java)) }
        }
        root.addView(btnModel)

        val btnDone = Button(this).apply {
            text = "我知道了，开始用"
            textSize = 20f
            setOnClickListener {
                Prefs.setFirstRunDone(this@FirstRunActivity)
                finish()
            }
        }
        root.addView(btnDone, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })
        refreshModel()
    }

    override fun onResume() { super.onResume(); refreshModel() }

    private fun refreshModel() {
        val (m, t) = AsrEngine(Prefs.modelsDir(this)).checkFiles()
        tvModel.text = when {
            m?.isFile == true && t?.isFile == true ->
                "模型 ✓（${m.length() / 1_000_000}MB）— 可以直接开始"
            else -> "模型未就绪：还缺 ${if (m?.isFile != true) "model.int8.onnx " else ""}${if (t?.isFile != true) "tokens.txt" else ""}\n放好后到设置里点「扫描模型」"
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 300 && results.all { it == PackageManager.PERMISSION_GRANTED }) refreshModel()
    }
}
