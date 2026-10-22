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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.AsrEngine
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.Ui
import java.io.File

/**
 * 设置页（§6.4 / §5.4 / §6.3.3）：分贝阈值、断句时长、红晕呈现、模型导入与校验。
 * 录制中不可打开（主界面已禁用）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvModelStatus: TextView
    private lateinit var tvPerm: TextView
    private lateinit var tvStorage: TextView
    private val asrExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun hasAllFiles(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    private fun refreshPerm() {
        if (!::tvPerm.isInitialized) return
        tvPerm.text = if (hasAllFiles())
            "全盘读取：已授予 ✓（能扫 Download / Documents 里的模型）"
        else
            "全盘读取：未授予（扫不到 Download。可点下面按钮授权，或把模型拷到 App 目录 / 用 SAF 选目录）"
    }

    override fun onResume() {
        super.onResume()
        refreshPerm()
        if (::tvModelStatus.isInitialized) refreshModelStatus()
        refreshStorage()
    }

    // ================= 存储与清理（§11.5） =================

    /** 扫整个会话目录是文件 IO，放后台线程：设置页每次回来刷一次，卡主线程会让界面顿一下 */
    private fun refreshStorage() {
        if (!::tvStorage.isInitialized) return
        tvStorage.text = "统计中…"
        asrExecutor.execute {
            val st = com.oska.loudspeak.core.SessionStore(this@SettingsActivity)
            val trash = st.trashSizeBytes()
            val sessions = (st.sessionsSizeBytes() - trash).coerceAtLeast(0)
            val free = (File(Prefs.modelsDir(this@SettingsActivity)).parentFile ?: filesDir).usableSpace
            val oldest = st.oldestSessions(1).firstOrNull()
            val ageDays = oldest?.let { (System.currentTimeMillis() - it.startedAt) / 86_400_000 } ?: 0
            val warn = StringBuilder()
            // §11.5：超过 10GB 或保留超过 30 天时**只提示，不自动删**
            if (sessions > 10L * 1_000_000_000) warn.append("⚠ 会话已超过 10GB，建议回头看看最旧的几条\n")
            if (ageDays > 30) warn.append("⚠ 最旧的一条已经 ${ageDays} 天（默认保留 30 天，只提示不自动删）\n")
            val txt = "${warn}会话：%.2fGB · 回收站：%.2fGB · 可用：%.2fGB".format(
                sessions / 1e9, trash / 1e9, free / 1e9)
            runOnUiThread { tvStorage.text = txt }
        }
    }

    /**
     * 清理建议：列出最旧的 10 条让用户自己勾（§11.5「而不是替用户决定删哪些」）。
     * 勾选后只做软删除（进回收站），回收站里还能逐条恢复。
     */
    private fun showCleanup() {
        val st = com.oska.loudspeak.core.SessionStore(this)
        val olds = st.oldestSessions(10)
        if (olds.isEmpty()) {
            Toast.makeText(this, "还没有会话记录", Toast.LENGTH_SHORT).show(); return
        }
        val labels = olds.map { r ->
            val d = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(r.startedAt))
            "   %s · %d分钟 · %.0fMB".format(d, r.durationMs / 60000, st.dirSizeBytes(File(r.dirPath)) / 1e6)
        }.toTypedArray()
        val checked = BooleanArray(olds.size)
        AlertDialog.Builder(this)
            .setTitle("清理建议：最旧的 ${olds.size} 条（勾选后移入回收站，还能恢复）")
            .setMultiChoiceItems(labels, checked) { _, w, v -> checked[w] = v }
            .setNegativeButton("先不删", null)
            .setPositiveButton("移入回收站") { _, _ ->
                var n = 0
                olds.forEachIndexed { i, r -> if (checked[i] && st.softDelete(r.sessionId) == null) n++ }
                Toast.makeText(this, "已移入回收站 $n 条", Toast.LENGTH_SHORT).show()
                refreshStorage()
            }
            .show()
    }

    /** 清空回收站：永久删除，所以必须先确认（§11.5 绝不静默删除） */
    private fun confirmEmptyTrash() {
        val st = com.oska.loudspeak.core.SessionStore(this)
        if (st.listTrash().isEmpty()) {
            Toast.makeText(this, "回收站是空的", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("清空回收站")
            .setMessage("回收站里的会话会被永久删除，不可恢复。要继续吗？")
            .setPositiveButton("永久删除") { _, _ ->
                val n = st.emptyTrash()
                Toast.makeText(this, "已永久删除 $n 条", Toast.LENGTH_SHORT).show()
                refreshStorage()
            }
            .setNegativeButton("取消", null)
            .show()
    }

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

        // 识别标注体系（§R9）：色彩 + 符号，逐项可关；附图例
        root.addView(label("识别标注（自动，不用提示词；关掉则只显示纯文字）"))
        root.addView(android.widget.Switch(this).apply {
            isChecked = Prefs.annotEnabled(this@SettingsActivity)
            text = "  开启标注"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, v -> Prefs.setAnnotEnabled(this@SettingsActivity, v) }
        })
        root.addView(android.widget.Switch(this).apply {
            isChecked = Prefs.annotPronouns(this@SettingsActivity)
            text = "  代词标红 + 下划线"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, v -> Prefs.setAnnotPronouns(this@SettingsActivity, v) }
        })
        root.addView(android.widget.Switch(this).apply {
            isChecked = Prefs.annotFillers(this@SettingsActivity)
            text = "  口头禅淡化 + 删除线"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, v -> Prefs.setAnnotFillers(this@SettingsActivity, v) }
        })
        root.addView(android.widget.Switch(this).apply {
            isChecked = Prefs.annotIssues(this@SettingsActivity)
            text = "  句式问题橙色提示"; textSize = 16f; setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, v -> Prefs.setAnnotIssues(this@SettingsActivity, v) }
        })
        root.addView(Button(this).apply {
            text = "标注图例"
            setOnClickListener {
                AlertDialog.Builder(this@SettingsActivity).setTitle("识别标注图例")
                    .setMessage(com.oska.loudspeak.core.Annotator.legend())
                    .setPositiveButton("知道了", null).show()
            }
        })

        // PC 服务端两个地址（§3.3 双通道）：识别 8010 + 判定 11434（Qwen）
        // 现阶段只显示/可改 IP；真正的收发在 PC-3 批次接入
        root.addView(label("PC 服务端地址（手机与 PC 连同一路由器；PC 迁移只改这里）"))
        val tvPc = TextView(this).apply {
            textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding(0, pad / 3, 0, pad / 4)
        }
        fun refreshPc() {
            val ip = Prefs.pcIp(this@SettingsActivity)
            tvPc.text = "语音识别 ASR：http://$ip:${Prefs.pcAsrPort(this@SettingsActivity)}\n" +
                    "判定 Qwen：http://$ip:${Prefs.pcLlmPort(this@SettingsActivity)}\n" +
                    "（端口与 token 在 PC 的 config.yaml；通道收发待 PC-3 接入）"
        }
        refreshPc()
        root.addView(tvPc)
        val btnPcIp = Button(this).apply {
            text = "改 PC 的 IP"
            setOnClickListener {
                val input = android.widget.EditText(this@SettingsActivity).apply {
                    setText(Prefs.pcIp(this@SettingsActivity))
                    setSelection(text.length)
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("PC 的局域网 IP")
                    .setMessage("在 PC 上跑 start-server.bat 或 ipconfig 查 IPv4，形如 192.168.3.15")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        val ip = input.text.toString().trim()
                        if (ip.isNotEmpty()) { Prefs.setPcIp(this@SettingsActivity, ip); refreshPc() }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
        root.addView(btnPcIp)

        // 模型（§5.4）：ASR 必装 + Qwen 可选（默认关，见 §5.7）
        tvModelStatus = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setLineSpacing(0f, 1.2f)
        }
        root.addView(label("模型状态（文件放好后点「扫描并安装」）"))
        root.addView(tvModelStatus)

        // 全盘读取权限：没有它 Android 11+ 读不到 /sdcard/Download，扫描必然「漏文件」
        tvPerm = TextView(this).apply {
            textSize = 14f; setTextColor(0xFF9AA7B0.toInt()); setPadding(0, pad / 3, 0, pad / 4)
        }
        root.addView(tvPerm)
        root.addView(Button(this).apply {
            text = "授予「所有文件访问」权限（扫 Download 用）"
            setOnClickListener {
                if (hasAllFiles()) {
                    Toast.makeText(this@SettingsActivity, "已经授予了", Toast.LENGTH_SHORT).show()
                } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                    try {
                        startActivity(
                            Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, "打不开系统页：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        refreshPerm()
        root.addView(Button(this).apply {
            text = "扫描并安装（ASR + Qwen 一起找）"
            setOnClickListener { scanModels() }
        })
        root.addView(Button(this).apply {
            text = "从文件管理器选择目录 (SAF，会搜子目录)"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 200) }
        })
        root.addView(Button(this).apply {
            text = "校验 ASR（真加载一次，约 15–30 秒）"
            setOnClickListener { checkModel() }
        })

        // Qwen 开关（§5.7：默认关、录后按需加载、判完释放）
        root.addView(label("本地 Qwen（可选，默认关闭）"))
        root.addView(android.widget.Switch(this).apply {
            isChecked = Prefs.llmEnabled(this@SettingsActivity)
            text = "  启用本地 Qwen 判定"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setOnCheckedChangeListener { _, v ->
                Prefs.setLlmEnabled(this@SettingsActivity, v)
                refreshModelStatus()
            }
        })

        // ---- 存储与空间清理（§11.5：只提示不自动删；删除只进回收站）----
        root.addView(label("存储与空间（删除只会进回收站，绝不静默删）"))
        tvStorage = TextView(this).apply {
            textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setLineSpacing(0f, 1.25f)
        }
        root.addView(tvStorage)
        root.addView(Button(this).apply {
            text = "清理建议（列出最旧的 10 条，自己勾）"
            setOnClickListener { showCleanup() }
        })
        root.addView(Button(this).apply {
            text = "回收站（逐条恢复 / 永久删除）"
            setOnClickListener { startActivity(Intent(this@SettingsActivity, TrashActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "清空回收站（会永久删除，不可恢复）"
            setOnClickListener { confirmEmptyTrash() }
        })

        // 其它入口
        val btnErr = Button(this).apply { text = "错误报告（调试用）" }
        btnErr.setOnClickListener { startActivity(Intent(this, ErrorsActivity::class.java)) }
        val btnSelf = Button(this).apply { text = "系统自检 (B0)" }
        btnSelf.setOnClickListener { startActivity(Intent(this, SelfCheckActivity::class.java)) }
        val btnCapture = Button(this).apply { text = "采集验证 (B1)" }
        btnCapture.setOnClickListener { startActivity(Intent(this, CaptureVerifyActivity::class.java)) }
        root.addView(btnErr); root.addView(btnSelf); root.addView(btnCapture)

        val btnBack = Button(this).apply { text = "返回" }
        btnBack.setOnClickListener { finish() }
        root.addView(btnBack)

        Ui.hideStatusBar(this)
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
        refreshModelStatus()
    }

    private fun refreshModelStatus() {
        val (m, t) = AsrEngine(Prefs.modelsDir(this)).checkFiles()
        val llm = newestGguf()
        tvModelStatus.text = buildString {
            append("目录：").append(Prefs.modelsDir(this@SettingsActivity)).append('\n')
            append("ASR  onnx：").append(
                if (m?.isFile == true) "${m.length() / 1_000_000}MB ✓" else "✗ 缺").append('\n')
            append("ASR  tokens：").append(if (t?.isFile == true) "✓" else "✗ 缺").append('\n')
            append("Qwen 模型：").append(
                if (llm != null) "${llm.name}（${llm.length() / 1_000_000}MB）✓"
                else "✗ 缺（可选，放 .gguf 到 " + Prefs.llmDir(this@SettingsActivity) + "）").append('\n')
            append("Qwen 开关：").append(if (Prefs.llmEnabled(this@SettingsActivity)) "开" else "关").append('\n')
            append("注：本地 Qwen 已接「离线判定」管线——开启后用规则后端一定能判；\n")
            append("    若拷入了 Qwen GGUF 且真机装有推理库，会自动升级为模型评语，否则回退规则。\n")
            append(storageUsage())
        }
    }

    /** <models>/llm 下最新的那个 .gguf */
    private fun newestGguf(): File? = try {
        File(Prefs.llmDir(this)).listFiles { f: File -> f.isFile && f.name.endsWith(".gguf", true) }
            ?.maxByOrNull { it.lastModified() }
    } catch (_: Exception) { null }

    private fun storageUsage(): String {
        return try {
            // 早先用 sessionsRoot.usableSpace：那个目录没建过时返回 0，于是显示「剩余 0.00GB ⚠不足2GB」
            val root = File(Prefs.modelsDir(this)).parentFile ?: return ""
            root.mkdirs()
            val sessions = File(Prefs.sessionsRoot(this)).apply { mkdirs() }
            val used = sessions.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            val free = root.usableSpace
            val warn = if (free in 1..2_000_000_000L) " ⚠剩余不足2GB" else ""
            "会话占用：%.2fGB · 可用空间：%.2fGB%s".format(used / 1e9, free / 1e9, warn)
        } catch (_: Exception) { "" }
    }

    /** 扫描常见位置：ASR（onnx+tokens）与 Qwen（gguf）一起列，选一个装一个 */
    private fun scanModels() {
        tvModelStatus.text = "扫描中…（App 目录 / Download / Documents，最多 6 层，几千个文件要点时间）"
        asrExecutor.execute {
            val found = com.oska.loudspeak.core.ModelScanner.scan(this)
            runOnUiThread {
                if (found.isEmpty()) {
                    tvModelStatus.text = buildString {
                        append("没扫到模型。两个可靠办法：\n")
                        append("① 数据线/MTP 把文件拷到这个目录（能建子目录）：\n")
                        append(Prefs.modelsDir(this@SettingsActivity)).append('\n')
                        append("   ASR 放 asr/model.int8.onnx + asr/tokens.txt\n")
                        append("   Qwen 放 llm/*.gguf\n")
                        append("② 点下面「从文件管理器选择目录」，直接选放模型的那个文件夹（会搜子目录）\n")
                        append("为什么扫不到：Android 13+ 默认不允许读 Download，属系统限制，不是文件没放好。")
                    }
                    return@runOnUiThread
                }
                val labels = found.map { it.label() }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("找到 ${found.size} 个候选，选一个安装")
                    .setItems(labels) { _, which -> installFound(found[which]) }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun installFound(f: com.oska.loudspeak.core.ModelScanner.Found) {
        tvModelStatus.text = "安装中…（${f.sizeMb}MB，请勿离开）"
        asrExecutor.execute {
            val ok = com.oska.loudspeak.core.ModelScanner.install(f, Prefs.modelsDir(this))
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "已装好：" + (if (f.kind == com.oska.loudspeak.core.ModelScanner.Kind.ASR)
                        "语音识别（回主界面会自动加载）" else "Qwen 模型") else "安装失败",
                    Toast.LENGTH_LONG
                ).show()
                refreshModelStatus()
            }
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

    /**
     * SAF：把所选目录（含子目录）里的 ASR / Qwen 文件分别装到对应位置。
     * 早先只查一层、只认两个死文件名，所以「选了目录却什么也没导入」。
     */
    private fun importModels(treeUri: Uri) {
        tvModelStatus.text = "导入中…（扫描所选目录含子目录，请勿离开）"
        asrExecutor.execute {
            try {
                val cr = contentResolver
                val rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val onnx = ArrayList<Pair<String, Uri>>()
                val tokens = ArrayList<Pair<String, Uri>>()
                val gguf = ArrayList<Pair<String, Uri>>()
                walkTree(cr, treeUri, rootId, onnx, tokens, gguf, 0)

                var asrOk = false
                var llmCount = 0
                if (onnx.isNotEmpty() && tokens.isNotEmpty()) {
                    val destAsr = File(Prefs.modelsDir(this), "asr").apply { mkdirs() }
                    val best = onnx.maxByOrNull { scoreName(it.first) }!!
                    copyFrom(cr, best.second, File(destAsr, "model.int8.onnx"))
                    copyFrom(cr, tokens.first().second, File(destAsr, "tokens.txt"))
                    asrOk = File(destAsr, "model.int8.onnx").isFile && File(destAsr, "tokens.txt").isFile
                }
                if (gguf.isNotEmpty()) {
                    val destLlm = File(Prefs.llmDir(this)).apply { mkdirs() }
                    for ((name, uri) in gguf) if (copyFrom(cr, uri, File(destLlm, name))) llmCount++
                }
                runOnUiThread {
                    tvModelStatus.text = buildString {
                        append("导入结果：ASR ")
                        append(if (asrOk) "✓" else if (onnx.isEmpty() && tokens.isEmpty()) "未选到" else "✗（缺 onnx 或 tokens）")
                        append(" · Qwen ").append(if (llmCount > 0) "✓ $llmCount 个" else "未选到")
                        append('\n')
                    }
                    refreshModelStatus()
                }
            } catch (e: Exception) {
                runOnUiThread { tvModelStatus.text = "导入失败：${e.message?.take(120)}" }
            }
        }
    }

    private fun walkTree(
        cr: android.content.ContentResolver, treeUri: Uri, dirId: String,
        onnx: MutableList<Pair<String, Uri>>, tokens: MutableList<Pair<String, Uri>>,
        gguf: MutableList<Pair<String, Uri>>, depth: Int
    ) {
        if (depth > 4) return
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        cr.query(
            children,
            arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2)
                if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                    walkTree(cr, treeUri, id, onnx, tokens, gguf, depth + 1); continue
                }
                val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                when {
                    name.equals("tokens.txt", true) -> tokens.add(name to uri)
                    name.endsWith(".onnx", true) -> onnx.add(name to uri)
                    name.endsWith(".gguf", true) -> gguf.add(name to uri)
                }
            }
        }
    }

    private fun scoreName(n: String): Int {
        val s = n.lowercase()
        var v = 0
        if (s.contains("sense")) v += 6
        if (s.contains("model")) v += 3
        if (s.contains("int8")) v += 2
        return v
    }

    private fun copyFrom(cr: android.content.ContentResolver, uri: Uri, dst: File): Boolean = try {
        cr.openInputStream(uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        } != null
    } catch (_: Exception) { false }
}
