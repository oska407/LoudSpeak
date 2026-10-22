package com.oska.loudspeak

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.AsrEngine
import com.oska.loudspeak.core.Prefs
import java.io.File

/**
 * 设置页（§6.4 / §5.4 / §6.3.3）：分贝阈值、断句时长、红晕呈现、模型导入与校验。
 * 录制中不可打开（主界面已禁用）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private val asrExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFF101418.toInt())
        }
        fun label(t: String): TextView = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(0xFF9AA7B0.toInt())
        }

        // 分贝阈值（F1：10–50，默认 30）
        root.addView(label("分贝阈值（低于变红，只影响颜色不影响判定）"))
        val tvTh = TextView(this).apply {
            textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            text = Prefs.dbThreshold(this@SettingsActivity).toString()
        }
        val sbTh = SeekBar(this).apply { max = 40; progress = Prefs.dbThreshold(this@SettingsActivity) - 10 }
        sbTh.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = p + 10; Prefs.setDbThreshold(this@SettingsActivity, v); tvTh.text = v.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(tvTh); root.addView(sbTh)

        // 断句静音时长（B2：400–1200ms，默认 800）
        root.addView(label("句末静音判定（毫秒，越小说句切得越碎）"))
        val tvVad = TextView(this).apply {
            textSize = 20f; setTextColor(0xFFFFFFFF.toInt())
            text = Prefs.vadSilenceMs(this@SettingsActivity).toString()
        }
        val sbVad = SeekBar(this).apply { max = 8; progress = (Prefs.vadSilenceMs(this@SettingsActivity) - 400) / 100 }
        sbVad.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 400 + p * 100; Prefs.setVadSilenceMs(this@SettingsActivity, v); tvVad.text = v.toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        root.addView(tvVad); root.addView(sbVad)

        // 红晕呈现（§7.3 三选一）
        root.addView(label("红晕呈现"))
        val glowModes = arrayOf("泛红提醒", "仅计数不泛红", "完全静默")
        root.addView(android.widget.Spinner(this).let { sp ->
            sp.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, glowModes)
            sp.setSelection(Prefs.glowMode(this@SettingsActivity))
            sp.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    Prefs.setGlowMode(this@SettingsActivity, pos)
                }
                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
            sp
        })

        // 模型（§5.4 SAF 导入 → 拷到 app 私有目录供 ONNX 直接读路径）
        tvModelStatus = TextView(this).apply { textSize = 15f; setTextColor(0xFFFFFFFF.toInt()) }
        root.addView(label("模型（SenseVoice int8，必装约 233MB）"))
        root.addView(tvModelStatus)
        val btnImport = Button(this).apply { text = "从文件管理器选择模型目录 (SAF)" }
        btnImport.setOnClickListener {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 200)
        }
        val btnCheck = Button(this).apply { text = "校验模型" }
        btnCheck.setOnClickListener { checkModel() }
        root.addView(btnImport); root.addView(btnCheck)

        // 其它入口
        val btnSelf = Button(this).apply { text = "系统自检 (B0)" }
        btnSelf.setOnClickListener { startActivity(Intent(this, SelfCheckActivity::class.java)) }
        val btnCapture = Button(this).apply { text = "采集验证 (B1)" }
        btnCapture.setOnClickListener { startActivity(Intent(this, CaptureVerifyActivity::class.java)) }
        root.addView(btnSelf); root.addView(btnCapture)

        val btnBack = Button(this).apply { text = "返回" }
        btnBack.setOnClickListener { finish() }
        root.addView(btnBack)

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val (m, t) = AsrEngine(Prefs.modelsDir(this)).checkFiles()
        tvModelStatus.text = buildString {
            append("目录：").append(Prefs.modelsDir(this@SettingsActivity)).append('\n')
            append("asr/model.int8.onnx：").append(
                if (m?.isFile == true) "${m.length() / 1_000_000}MB ✓" else "✗ 缺失").append('\n')
            append("asr/tokens.txt：").append(if (t?.isFile == true) "✓" else "✗ 缺失")
        }
    }

    private fun checkModel() {
        tvModelStatus.text = "校验中（首次加载约 15–30 秒，eMMC）…"
        asrExecutor.execute {
            val e = AsrEngine(Prefs.modelsDir(this))
            val ok = e.load()
            runOnUiThread {
                tvModelStatus.text = if (ok) "✓ 模型加载成功，识别可用" else "✗ ${e.status}"
                e.release()
            }
        }
    }

    /** SAF：把所选目录下的 asr/ 两个文件拷入 app 私有模型目录（§5.4.2 方式 A） */
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 200 && res == Activity.RESULT_OK && data?.data != null) {
            importModels(data.data!!)
        }
    }

    private fun importModels(treeUri: Uri) {
        tvModelStatus.text = "导入中…（233MB，请勿离开）"
        asrExecutor.execute {
            try {
                val cr = contentResolver
                val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val destDir = File(Prefs.modelsDir(this), "asr").apply { mkdirs() }
                cr.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0)
                        val name = c.getString(1) ?: continue
                        if (name != "model.int8.onnx" && name != "tokens.txt") continue
                        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        cr.openInputStream(fileUri)?.use { input ->
                            File(destDir, name).outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "导入完成", Toast.LENGTH_SHORT).show()
                    refreshModelStatus()
                }
            } catch (e: Exception) {
                runOnUiThread { tvModelStatus.text = "导入失败：${e.message?.take(120)}" }
            }
        }
    }
}
