package com.oska.loudspeak

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.oska.loudspeak.core.ModelScanner
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
class FirstRunActivity : LsActivity() {

    private var step = 1
    private lateinit var host: LinearLayout
    private var pad = 14
    private val REQ_PICK = 400   // 手动选模型文件的 SAF 请求码

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
            host.addView(hint("装模型有两条路，自动找不到就用手动（Android 13+ 默认看不了 Download 目录，这是系统限制）："))
            // ① 自动：扫常见位置
            host.addView(Button(this).apply {
                text = "① 自动查找已拷入的模型"
                setOnClickListener { autoScanModel() }
            })
            // ② 手动：系统文件选择器，自己点到放模型的文件夹去挑
            host.addView(Button(this).apply {
                text = "② 手动选择模型文件（.onnx + tokens.txt 一起选）"
                setOnClickListener { pickModelFiles() }
            })
            host.addView(hint("手动选择的用法：在弹出的文件管理器里进入放着模型的文件夹，\n" +
                    "把 model.int8.onnx 和 tokens.txt 两个一起勾上 → 打开/完成。"))
        }
        host.addView(Button(this).apply {
            text = "打开完整设置（选整个文件夹 / 全盘授权）"
            setOnClickListener { startActivity(Intent(this@FirstRunActivity, SettingsActivity::class.java)) }
        })
        host.addView(backBtn())
        host.addView(nextBtn("下一步") { step = 3; render() })
    }

    // ---- 模型：自动扫描 / 手动选文件 ----

    private fun autoScanModel() {
        Toast.makeText(this, "正在查找模型…", Toast.LENGTH_SHORT).show()
        Thread {
            val found = try { ModelScanner.scan(this).filter { it.kind == ModelScanner.Kind.ASR } } catch (e: Exception) { emptyList() }
            runOnUiThread {
                when {
                    found.isEmpty() -> androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("没自动找到模型")
                        .setMessage("可以点「② 手动选择模型文件」，自己进到放模型的文件夹里挑；\n" +
                                "也可以点下面的「打开完整设置」授权全盘读取后再扫一次。")
                        .setPositiveButton("知道了", null)
                        .show()
                    found.size == 1 -> installFound(found[0])
                    else -> androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("找到 ${found.size} 个，选一个安装")
                        .setItems(found.map { it.label() }.toTypedArray()) { _, which -> installFound(found[which]) }
                        .setNegativeButton("取消", null)
                        .show()
                }
            }
        }.start()
    }

    private fun installFound(f: ModelScanner.Found) {
        Toast.makeText(this, "正在装入（${f.sizeMb}MB）…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try { ModelScanner.install(f, Prefs.modelsDir(this)) } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) "装好了" else "安装失败，换「手动选择」再试", Toast.LENGTH_LONG).show()
                render()
            }
        }.start()
    }

    private fun pickModelFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, REQ_PICK) } catch (e: Exception) {
            Toast.makeText(this, "打不开文件选择器：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != REQ_PICK || res != Activity.RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i)?.uri?.let { uris.add(it) } }
        data.data?.let { uris.add(it) }
        if (uris.isEmpty()) return
        Toast.makeText(this, "正在导入 ${uris.size} 个文件…", Toast.LENGTH_SHORT).show()
        Thread {
            val msg = try { ModelScanner.importUris(this, uris, Prefs.modelsDir(this)) } catch (e: Exception) { "导入失败：${e.message?.take(80)}" }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); render() }
        }.start()
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
