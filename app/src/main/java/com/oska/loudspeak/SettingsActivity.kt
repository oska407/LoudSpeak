package com.oska.loudspeak

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.AsrEngine
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.RuleEngine
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.Ui
import java.io.File

/**
 * 设置页（§6.4 / §5.4 / §11.1 / §11.5）—— **竖版**。
 *
 * 为什么这块竖着：主界面是四行平铺的工作台，必须横屏；设置是「一页读下来」的清单，
 * 竖屏一列更好翻、更好点，也不会把 `分贝阈值 + 说明 + 滑杆` 挤成一行。
 * 界面结构：分组卡片（练习反馈 / 识别标注 / 难度分级 / 模型 / 存储 / 其它 / 关于），
 * 每行「标题 + 说明 + 右侧控件」，说明压在标题下面一行，不再所有字一样白一样大。
 *
 * 录制中不可打开（主界面已禁用「设置」键）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvVersion: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvPerm: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvDirs: TextView
    private lateinit var tvLevelInfo: TextView
    private lateinit var tvLevelDetail: TextView
    private lateinit var tvDbValue: TextView
    private lateinit var tvVadValue: TextView
    private lateinit var tvGlowValue: TextView

    private lateinit var seekDb: SeekBar
    private lateinit var seekVad: SeekBar
    private lateinit var seekGlow: SeekBar
    private lateinit var rgGlow: RadioGroup
    private lateinit var swAnnot: Switch
    private lateinit var swPron: Switch
    private lateinit var swFiller: Switch
    private lateinit var swMuzzy: Switch
    private lateinit var swIssue: Switch
    private lateinit var swLlm: Switch
    private lateinit var swKeepText: Switch
    private lateinit var spLevel: Spinner
    private lateinit var spHoldDays: Spinner
    private lateinit var spHoldGb: Spinner

    private val asrExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun hasAllFiles(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    // ================= 生命周期 =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        bindViews()
        initValues()
        wireActions()
        refreshPerm()
        refreshModelStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPerm()
        if (::tvModelStatus.isInitialized) refreshModelStatus()
        refreshStorage()
        refreshLevel()
    }

    private fun bindViews() {
        tvVersion = findViewById(R.id.tvVersion)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvPerm = findViewById(R.id.tvPerm)
        tvStorage = findViewById(R.id.tvStorage)
        tvDirs = findViewById(R.id.tvDirs)
        tvLevelInfo = findViewById(R.id.tvLevelInfo)
        tvLevelDetail = findViewById(R.id.tvLevelDetail)
        tvDbValue = findViewById(R.id.tvDbValue)
        tvVadValue = findViewById(R.id.tvVadValue)
        tvGlowValue = findViewById(R.id.tvGlowValue)
        seekDb = findViewById(R.id.seekDb)
        seekVad = findViewById(R.id.seekVad)
        seekGlow = findViewById(R.id.seekGlow)
        rgGlow = findViewById(R.id.rgGlow)
        swAnnot = findViewById(R.id.swAnnot)
        swPron = findViewById(R.id.swPron)
        swFiller = findViewById(R.id.swFiller)
        swMuzzy = findViewById(R.id.swMuzzy)
        swIssue = findViewById(R.id.swIssue)
        swLlm = findViewById(R.id.swLlm)
        swKeepText = findViewById(R.id.swKeepText)
        spLevel = findViewById(R.id.spLevel)
        spHoldDays = findViewById(R.id.spHoldDays)
        spHoldGb = findViewById(R.id.spHoldGb)
    }

    // ================= 初始化取值 =================

    private fun initValues() {
        tvVersion.text = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "" }

        // 分贝阈值 10–50（F1）
        seekDb.max = 40
        seekDb.progress = Prefs.dbThreshold(this) - 10
        tvDbValue.text = Prefs.dbThreshold(this).toString()

        // 句末停顿 400–1200（B2）
        seekVad.max = 8
        seekVad.progress = ((Prefs.vadSilenceMs(this) - 400) / 100).coerceIn(0, 8)
        tvVadValue.text = "${Prefs.vadSilenceMs(this)}ms"

        // 红晕呈现（§7.3 三选一）
        when (Prefs.glowMode(this)) {
            1 -> rgGlow.check(R.id.rbGlow1)
            2 -> rgGlow.check(R.id.rbGlow2)
            else -> rgGlow.check(R.id.rbGlow0)
        }
        // 红晕最短间隔 1–10s（D4）
        seekGlow.max = 9
        seekGlow.progress = (Prefs.glowDebounceSec(this) - 1).coerceIn(0, 9)
        tvGlowValue.text = "${Prefs.glowDebounceSec(this)}s"

        // 标注开关
        swAnnot.isChecked = Prefs.annotEnabled(this)
        swPron.isChecked = Prefs.annotPronouns(this)
        swFiller.isChecked = Prefs.annotFillers(this)
        swMuzzy.isChecked = Prefs.annotMuzzy(this)
        swIssue.isChecked = Prefs.annotIssues(this)
        applyAnnotEnabled()

        // 分级（§8.6）
        spLevel.adapter = simpleAdapter(
            listOf("自动（按第几次会话递进）", "1 · L1 指代词", "2 · ＋L3 口头禅",
                "3 · ＋L2 模糊词", "4 · ＋L4-a 缺主体", "5 · 全开")
        )
        spLevel.setSelection(Prefs.levelOverride(this).coerceIn(0, 5), false)
        spLevel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setLevelOverride(this@SettingsActivity, pos)
                refreshLevel()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        refreshLevel()

        // 模型
        swLlm.isChecked = Prefs.llmEnabled(this)

        // 存储
        swKeepText.isChecked = Prefs.keepTextOnDelete(this)
        val dayOpts = listOf(7, 14, 30, 60, 90, 180)
        spHoldDays.adapter = simpleAdapter(dayOpts.map { "$it 天" })
        spHoldDays.setSelection(dayOpts.indexOf(Prefs.retentionDays(this)).let { if (it < 0) 2 else it }, false)
        spHoldDays.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setRetentionDays(this@SettingsActivity, dayOpts[pos]); refreshStorage()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        val gbOpts = listOf(2, 5, 10, 20, 50)
        spHoldGb.adapter = simpleAdapter(gbOpts.map { "$it GB" })
        spHoldGb.setSelection(gbOpts.indexOf(Prefs.retentionGb(this)).let { if (it < 0) 2 else it }, false)
        spHoldGb.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setRetentionGb(this@SettingsActivity, gbOpts[pos]); refreshStorage()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<TextView>(R.id.tvAbout).text = buildString {
            append("大声说 —— 帮她少说「这个那个」，把话说完整。\n")
            append("完全离线：不联网、不申请网络权限，录音录像只存在这台手机里。\n")
            append("谁的记录谁能删：她可以删自己的会话（进回收站 7 天，可恢复）。\n")
            append("不给分、不给星、不排名 —— 只让她看见自己说的话。")
        }
    }

    private fun simpleAdapter(items: List<String>): android.widget.ArrayAdapter<String> =
        android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // ================= 交互 =================

    private fun wireActions() {
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }

        seekDb.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = p + 10
                Prefs.setDbThreshold(this@SettingsActivity, v)
                tvDbValue.text = v.toString()
            }
        })
        seekVad.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = 400 + p * 100
                Prefs.setVadSilenceMs(this@SettingsActivity, v)
                tvVadValue.text = "${v}ms"
            }
        })
        seekGlow.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = p + 1
                Prefs.setGlowDebounceSec(this@SettingsActivity, v)
                tvGlowValue.text = "${v}s"
            }
        })
        rgGlow.setOnCheckedChangeListener { _, id ->
            Prefs.setGlowMode(this, when (id) {
                R.id.rbGlow1 -> 1
                R.id.rbGlow2 -> 2
                else -> 0
            })
        }

        swAnnot.setOnCheckedChangeListener { _, v ->
            Prefs.setAnnotEnabled(this, v); applyAnnotEnabled()
        }
        swPron.setOnCheckedChangeListener { _, v -> Prefs.setAnnotPronouns(this, v) }
        swFiller.setOnCheckedChangeListener { _, v -> Prefs.setAnnotFillers(this, v) }
        swMuzzy.setOnCheckedChangeListener { _, v -> Prefs.setAnnotMuzzy(this, v) }
        swIssue.setOnCheckedChangeListener { _, v -> Prefs.setAnnotIssues(this, v) }
        findViewById<Button>(R.id.btnLegend).setOnClickListener { showLegend() }

        swLlm.setOnCheckedChangeListener { _, v ->
            Prefs.setLlmEnabled(this, v); refreshModelStatus()
        }
        swKeepText.setOnCheckedChangeListener { _, v -> Prefs.setKeepTextOnDelete(this, v) }

        findViewById<Button>(R.id.btnScan).setOnClickListener { scanModels() }
        findViewById<Button>(R.id.btnSaf).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 200)
        }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkModel() }
        findViewById<Button>(R.id.btnPerm).setOnClickListener {
            if (hasAllFiles()) {
                Toast.makeText(this, "已经授予了", Toast.LENGTH_SHORT).show()
            } else if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Toast.makeText(this, "打不开系统页：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }

        findViewById<Button>(R.id.btnChangeDir).setOnClickListener { changeDirDialog() }
        findViewById<Button>(R.id.btnMigrate).setOnClickListener { migrateDialog() }
        findViewById<Button>(R.id.btnResetDir).setOnClickListener { resetDir() }
        findViewById<Button>(R.id.btnCleanup).setOnClickListener { showCleanup() }
        findViewById<Button>(R.id.btnTrash).setOnClickListener {
            startActivity(Intent(this, TrashActivity::class.java))
        }
        findViewById<Button>(R.id.btnEmptyTrash).setOnClickListener { confirmEmptyTrash() }

        findViewById<Button>(R.id.btnSelf).setOnClickListener {
            startActivity(Intent(this, SelfCheckActivity::class.java))
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            startActivity(Intent(this, CaptureVerifyActivity::class.java))
        }
        findViewById<Button>(R.id.btnErr).setOnClickListener {
            startActivity(Intent(this, ErrorsActivity::class.java))
        }

        Ui.hideStatusBar(this)
    }

    /** SeekBar 只要「值变了」这一件事，三个滑杆共用一个空实现壳子 */
    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        abstract fun onChange(p: Int)
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    /** 主开关关掉时，四个子项灰掉（看得见但不能按，不能直接消失） */
    private fun applyAnnotEnabled() {
        val on = swAnnot.isChecked
        for (v in listOf<android.view.View>(swPron, swFiller, swMuzzy, swIssue)) {
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.4f
        }
    }

    private fun showLegend() {
        val msg = buildString {
            append(com.oska.loudspeak.core.Annotator.legend())
            append("\n\n（这些都能在上面逐项关掉）")
        }
        AlertDialog.Builder(this)
            .setTitle("识别标注图例")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    // ================= 难度分级（§8.6） =================

    private fun refreshLevel() {
        val n = Prefs.sessionCount(this)
        val override = Prefs.levelOverride(this)
        val lv = RuleEngine.levels(n, override)
        val auto = RuleEngine.autoLevelIndex(n)
        tvLevelInfo.text = buildString {
            append("这是第 ").append(n).append(" 次会话")
            if (override == 0) {
                append(" · 自动到第 ").append(auto).append(" 级")
            } else {
                append(" · 已手动锁定")
            }
            append('\n').append("当前在抓：").append(lv.name)
        }
        tvLevelDetail.text = buildString {
            append("节奏是「她能自己听出来」的先开：L1 代词 → L3 口头禅 → L2 模糊词 → L4-a 缺主体 → L4 半截句。\n")
            append("自动挡：第 5 次开 L3，第 9 次开 L2，第 13 次开 L4-a，第 20 次全开。\n")
            if (lv.l2) append("· 模糊词正在抓（东西/玩意儿 → 换成名字）\n")
            if (lv.l4a) append("· 缺主体正在抓（谁在做这件事）\n")
            if (lv.l4bc) append("· 半截句 / 要素缺失正在抓\n")
            append("一次只加一级：连续 3 次不增加才该进下一级；变差了就退回上一级。")
        }
    }

    // ================= 存储与清理（§11.5） =================

    /** 扫整个会话目录是文件 IO，放后台线程；设置页每次回来刷一次 */
    private fun refreshStorage() {
        if (!::tvStorage.isInitialized) return
        tvStorage.text = "统计中…"
        asrExecutor.execute {
            val st = SessionStore(this@SettingsActivity)
            val trash = st.trashSizeBytes()
            val all = st.sessionsSizeBytes()
            val sessions = (all - trash).coerceAtLeast(0)
            val root = File(Prefs.modelsDir(this@SettingsActivity)).parentFile ?: filesDir
            val free = root.usableSpace
            val oldest = st.oldestSessions(1).firstOrNull()
            val ageDays = oldest?.let { (System.currentTimeMillis() - it.startedAt) / 86_400_000 } ?: 0
            val limitGb = Prefs.retentionGb(this@SettingsActivity)
            val limitDays = Prefs.retentionDays(this@SettingsActivity)

            val warn = StringBuilder()
            // §11.5：超阈值**只提示，不自动删**
            if (sessions > limitGb * 1_000_000_000L) warn.append("⚠ 会话已超过 ${limitGb}GB，可以看看最旧的几条\n")
            if (ageDays > limitDays) warn.append("⚠ 最旧的一条已经 ${ageDays} 天（超过 ${limitDays} 天，只提示不自动删）\n")
            if (free in 1..2_000_000_000L) warn.append("⚠ 剩余空间不足 2GB\n")

            val txt = "${warn}会话 %s · 回收站 %s · 可用 %s".format(
                gb(sessions), gb(trash), gb(free))
            val dirs = buildString {
                val custom = Prefs.sessionsRootCustom(this@SettingsActivity)
                append("模型：").append(Prefs.modelsDir(this@SettingsActivity)).append('\n')
                append("录制：").append(Prefs.sessionsRoot(this@SettingsActivity))
                append(if (custom.isNotBlank()) "（自定义）" else "（默认）")
            }
            runOnUiThread {
                tvStorage.text = txt
                tvDirs.text = dirs
            }
        }
    }

    private fun gb(b: Long): String = "%.2fGB".format(b / 1e9)

    /**
     * 清理建议：列出最旧的 10 条让用户自己勾（§11.5「而不是替用户决定删哪些」）。
     * 勾选后只做软删除（进回收站），回收站里还能逐条恢复。
     */
    private fun showCleanup() {
        val st = SessionStore(this)
        val olds = st.oldestSessions(10)
        if (olds.isEmpty()) {
            Toast.makeText(this, "还没有会话记录", Toast.LENGTH_SHORT).show(); return
        }
        val labels = olds.map { r ->
            val d = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                .format(java.util.Date(r.startedAt))
            "   %s · %d分钟 · %.0fMB".format(d, r.durationMs / 60000, st.dirSizeBytes(File(r.dirPath)) / 1e6)
        }.toTypedArray()
        val checked = BooleanArray(olds.size)
        val keepText = Prefs.keepTextOnDelete(this)
        AlertDialog.Builder(this)
            .setTitle("清理建议：最旧的 ${olds.size} 条")
            .setMessage(if (keepText) "会移入回收站（可恢复）；按当前设置只删音视频、保留转写文字。"
            else "会移入回收站（保留 ${Prefs.TRASH_KEEP_DAYS} 天，可恢复）。")
            .setMultiChoiceItems(labels, checked) { _, w, v -> checked[w] = v }
            .setNegativeButton("先不删", null)
            .setPositiveButton("移入回收站") { _, _ ->
                var n = 0
                olds.forEachIndexed { i, r -> if (checked[i] && st.softDelete(r.sessionId, keepText) == null) n++ }
                Toast.makeText(this, "已移入回收站 $n 条", Toast.LENGTH_SHORT).show()
                refreshStorage()
            }
            .show()
    }

    /** 清空回收站：永久删除，所以必须先确认（§11.5 绝不静默删除） */
    private fun confirmEmptyTrash() {
        val st = SessionStore(this)
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

    // ================= 存放位置（§11.1） =================

    /**
     * 改录制存放目录。§14.1 权限路线是「SAF 为主、手填绝对路径为辅」，
     * 但 mp4 只能写真实文件路径（MediaRecorder 不认 content:// 流），
     * 所以这里走**手填绝对路径 + 常用位置**，改完先试写再落设置。
     */
    private fun changeDirDialog() {
        val input = EditText(this).apply {
            setText(if (Prefs.sessionsRootCustom(this@SettingsActivity).isNotBlank())
                Prefs.sessionsRootCustom(this@SettingsActivity)
            else Prefs.sessionsRoot(this@SettingsActivity))
            setSelection(text.length)
            hint = "/storage/emulated/0/SpeakClear/sessions"
        }
        val quick = arrayOf(
            "App 目录（默认，免权限）→ ${Prefs.defaultSessionsRoot(this)}",
            "公共目录 /storage/emulated/0/SpeakClear/sessions（需「所有文件访问」）"
        )
        AlertDialog.Builder(this)
            .setTitle("录制存放目录")
            .setMessage("改完只影响新会话。老记录不会自己搬走 —— 要搬请点「迁移已有记录」。")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isBlank()) return@setPositiveButton
                val err = SessionStore(this).canWrite(path)
                if (err != null) {
                    Toast.makeText(this, "这个目录写不进去：$err", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                askMigrateThen(path)
            }
            .setNeutralButton("用快捷位置") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("常用位置")
                    .setItems(quick) { _, which ->
                        val path = if (which == 0) Prefs.defaultSessionsRoot(this)
                        else "/storage/emulated/0/SpeakClear/sessions"
                        val err = SessionStore(this).canWrite(path)
                        if (err != null && which == 1) {
                            Toast.makeText(this, "写不进去（先授予「所有文件访问」）：$err", Toast.LENGTH_LONG).show()
                        } else askMigrateThen(path)
                    }
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 切目录前问一句要不要把老记录一起搬（§11.1「用户主动触发才搬移」） */
    private fun askMigrateThen(path: String) {
        AlertDialog.Builder(this)
            .setTitle("把已有记录一起搬过去吗？")
            .setMessage("搬移过程可能持续几十秒，别中途退出。\n不搬也可以：老记录留在原处，只是新的写到这里。")
            .setPositiveButton("一起搬") { _, _ -> doMigrate(path) }
            .setNegativeButton("只改设置") { _, _ ->
                SessionStore(this).switchRoot(path)
                Toast.makeText(this, "已切换（只影响新会话）", Toast.LENGTH_SHORT).show()
                refreshStorage()
            }
            .show()
    }

    private fun doMigrate(targetPath: String?) {
        tvStorage.text = "迁移中…请勿离开"
        asrExecutor.execute {
            val st = SessionStore(this)
            val (n, err) = if (targetPath != null) st.migrateTo(targetPath) else st.migrateFrom(Prefs.defaultSessionsRoot(this))
            runOnUiThread {
                val msg = buildString {
                    append("已搬 ").append(n).append(" 项")
                    if (err != null) append("；").append(err)
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshStorage()
            }
        }
    }

    /** 「迁移已有记录」按钮：把默认目录里的老记录搬进当前（自定义）目录 */
    private fun migrateDialog() {
        val custom = Prefs.sessionsRootCustom(this)
        if (custom.isBlank()) {
            Toast.makeText(this, "现在就是默认目录，没有要搬的", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("迁移已有记录")
            .setMessage("把默认目录里的老记录搬到当前目录：\n$custom\n\n已有的同名会话不会覆盖。")
            .setPositiveButton("开始搬") { _, _ -> doMigrate(null) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun resetDir() {
        if (Prefs.sessionsRootCustom(this).isBlank()) {
            Toast.makeText(this, "现在就是默认目录", Toast.LENGTH_SHORT).show(); return
        }
        AlertDialog.Builder(this)
            .setTitle("恢复默认目录？")
            .setMessage("只会把「之后录的」写回默认位置，已有的记录仍留在当前目录里，不会丢。")
            .setPositiveButton("恢复默认") { _, _ ->
                Prefs.setSessionsRootCustom(this, "")
                Toast.makeText(this, "已恢复默认目录", Toast.LENGTH_SHORT).show()
                refreshStorage()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 权限 / 模型 =================

    private fun refreshPerm() {
        if (!::tvPerm.isInitialized) return
        tvPerm.text = if (hasAllFiles())
            "全盘读取：已授予 ✓（能扫 Download / Documents 里的模型）"
        else
            "全盘读取：未授予 —— 扫不到 Download 是这个原因，不是文件没放好。"
    }

    private fun refreshModelStatus() {
        if (!::tvModelStatus.isInitialized) return
        val (m, t) = AsrEngine(Prefs.modelsDir(this)).checkFiles()
        val llm = newestGguf()
        tvModelStatus.text = buildString {
            append("ASR 模型　").append(if (m?.isFile == true) "✓ ${m.length() / 1_000_000}MB" else "✗ 缺").append('\n')
            append("ASR 词表　").append(if (t?.isFile == true) "✓" else "✗ 缺").append('\n')
            append("Qwen 模型　").append(
                if (llm != null) "✓ ${llm.name}（${llm.length() / 1_000_000}MB）" else "✗ 缺（可选）").append('\n')
            append("Qwen 开关　").append(if (Prefs.llmEnabled(this@SettingsActivity)) "开" else "关").append("\n\n")
            append("判定一定会有结果：开着用规则后端判；拷了 GGUF 且真机带推理库时自动升级为模型评语，否则回退规则，不会卡住。")
        }
    }

    /** <models>/llm 下最新的那个 .gguf */
    private fun newestGguf(): File? = try {
        File(Prefs.llmDir(this)).listFiles { f: File -> f.isFile && f.name.endsWith(".gguf", true) }
            ?.maxByOrNull { it.lastModified() }
    } catch (_: Exception) { null }

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
                        append("② 点「从文件管理器选目录」，直接选放模型的那个文件夹（会搜子目录）\n")
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
                Toast.makeText(this,
                    if (ok) "已装好：" + (if (f.kind == com.oska.loudspeak.core.ModelScanner.Kind.ASR)
                        "语音识别（回主界面会自动加载）" else "Qwen 模型") else "安装失败",
                    Toast.LENGTH_LONG).show()
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

    /** SAF：把所选目录下的模型文件拷入 app 私有模型目录（§5.4.2 方式 A） */
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
                    Toast.makeText(this, buildString {
                        append("导入：ASR ")
                        append(if (asrOk) "✓" else if (onnx.isEmpty() && tokens.isEmpty()) "未选到" else "✗（缺 onnx 或 tokens）")
                        append(" · Qwen ").append(if (llmCount > 0) "✓ $llmCount 个" else "未选到")
                    }, Toast.LENGTH_LONG).show()
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
        cr.query(children, arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
        ), null, null, null)?.use { c ->
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
