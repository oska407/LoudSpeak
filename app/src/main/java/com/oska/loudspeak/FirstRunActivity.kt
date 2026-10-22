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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.oska.loudspeak.core.AsrEngine
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.Ui

/**
 * 首次引导（§12.1 五步，这里按三页做，顺序不能跳）：
 * 1 权限页（麦克风 + 摄像头，明说不会联网不上传）
 * 2 模型校验结果（缺件照样能进去，但先告诉她暂时出不了文字）
 * 3 **给孩子看的一页**：三句承诺大字，她点「我知道了」才结束
 *
 * 为什么是三页而不是一页堆下去：第 4 步那三句是**首屏承诺过的东西**
 * （不打分 / 只有你能删 / 随时能停），必须真的让孩子看到才算数，
 * 混在一堆按钮里在她那儿等于没说。
 */
class FirstRunActivity : AppCompatActivity() {

    private var step = 1
    private lateinit var host: LinearLayout
    private var pad = 14

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pad = (resources.displayMetrics.density * 14).toInt()
        host = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        Ui.hideStatusBar(this)
        setContentView(ScrollView(this).apply { addView(host) })
        render()
    }

    override fun onResume() {
        super.onResume()
        if (step == 2) render()   // 从设置页扫完模型回来，状态要刷新
    }

    private fun render() {
        host.removeAllViews()
        when (step) {
            1 -> stepPermission()
            2 -> stepModel()
            else -> stepPromises()
        }
    }

    // ---- 局部小工具 ----

    private fun big(t: String, size: Float, color: Int = 0xFFFFFFFF.toInt()): TextView =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            setLineSpacing(0f, 1.25f)
            setPadding(0, pad / 2, 0, pad / 4)
        }

    private fun hint(t: String): TextView =
        TextView(this).apply { text = t; textSize = 15f; setTextColor(0xFF9AA7B0.toInt()); setPadding(0, pad / 3, 0, pad / 3) }

    private fun nextBtn(t: String, enabled: Boolean = true, go: () -> Unit): Button =
        Button(this).apply {
            text = t; textSize = 18f; isEnabled = enabled
            setOnClickListener { go() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun backBtn(): Button = Button(this).apply {
        text = "上一页"
        setOnClickListener { step--; render() }
    }

    private fun pageTitle(): String = "第 $step / 3 步"

    // ---- 第 1 步：权限 ----

    private fun stepPermission() {
        host.addView(hint(pageTitle()))
        host.addView(big("先要两个权限", 24f))
        host.addView(hint("麦克风：四档都必须，不出声就没法练。\n" +
                "摄像头：① 完整录制、③ 带画面这两档要用；② ④ 档用不到，不给也能练。\n" +
                "这个 App 完全离线，不会联网，也不会把任何东西传到别的地方去。"))

        val granted = { p: String -> ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED }
        val btn = Button(this).apply {
            val okMic = granted(Manifest.permission.RECORD_AUDIO)
            val okCam = granted(Manifest.permission.CAMERA)
            text = if (okMic && okCam) "麦克风 ✓  摄像头 ✓" else "授权麦克风 + 摄像头"
            isEnabled = !(okMic && okCam)
            setOnClickListener {
                requestPermissions(
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA), 300)
            }
        }
        host.addView(btn)
        host.addView(nextBtn("下一步", enabled = granted(Manifest.permission.RECORD_AUDIO)) {
            step = 2; render()
        })
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            host.addView(hint("没给麦克风就不能练，所以这一步必须先过。"))
        }
    }

    // ---- 第 2 步：模型 ----

    private fun stepModel() {
        host.addView(hint(pageTitle()))
        host.addView(big("看看模型在不在", 24f))

        val (m, t) = AsrEngine(Prefs.modelsDir(this)).checkFiles()
        val ready = m?.isFile == true && t?.isFile == true
        if (ready) {
            host.addView(big("语音识别模型 ✓（${m!!.length() / 1_000_000}MB）", 18f, 0xFF4ADE80.toInt()))
            host.addView(hint("可以直接开始用了。"))
        } else {
            host.addView(big("模型还没就位", 18f, 0xFFEF9A9A.toInt()))
            host.addView(hint("缺：${if (m?.isFile != true) "model.int8.onnx " else ""}${if (t?.isFile != true) "tokens.txt" else ""}\n" +
                    "没有模型也能进去，但暂时出不了文字（分贝、波形、画面照常用）。"))
            host.addView(Button(this).apply {
                text = "去扫描 / 导入模型"
                setOnClickListener { startActivity(Intent(this@FirstRunActivity, SettingsActivity::class.java)) }
            })
        }
        host.addView(backBtn())
        host.addView(nextBtn("下一步") { step = 3; render() })
    }

    // ---- 第 3 步：给孩子看的三句话 ----

    private fun stepPromises() {
        host.addView(hint(pageTitle()))
        host.addView(hint("这三句念给她听，她点过「我知道了」才开始。"))
        host.addView(big("① 这里不打分，也不会传到任何地方去。", 22f, 0xFF4ADE80.toInt()))
        host.addView(big("② 录像和录音都是你的，只有你能删。", 22f, 0xFF4ADE80.toInt()))
        host.addView(big("③ 任何时候都可以按「停止」，不想讲了我们下次再来。", 22f, 0xFF4ADE80.toInt()))
        host.addView(backBtn())
        host.addView(nextBtn("我知道了，开始用") {
            Prefs.setFirstRunDone(this)
            Toast.makeText(this, "想换档位点底部的「模式」", Toast.LENGTH_SHORT).show()
            finish()
        })
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 300) render()
    }
}
