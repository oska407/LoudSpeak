package com.oska.loudspeak

import android.Manifest
import android.app.Activity
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.oska.loudspeak.core.AsrEngine
import com.oska.loudspeak.core.ModelScanner
import com.oska.loudspeak.core.NetChannel
import com.oska.loudspeak.core.PcJudge
import com.oska.loudspeak.core.Prefs
import com.oska.loudspeak.core.RuleEngine
import com.oska.loudspeak.core.SampleRecorder
import com.oska.loudspeak.core.SessionStore
import com.oska.loudspeak.core.SpeakerEngine
import com.oska.loudspeak.core.SpeakerProfile
import com.oska.loudspeak.core.SpeakerProfileStore
import com.oska.loudspeak.core.Skin
import com.oska.loudspeak.core.Ui
import java.io.File

/**
 * 设置页（竖版，R11 起分标签）。
 *
 * 为什么分标签：原来一页从头滑到尾（练习/标注/模型/存储全挤在一列），
 * 想随手试一下换皮肤，要滑过二十行才找得到。现在分成五页：
 * 练习 · 标注 · 外观 · 存储 · 模型，进设置先看要改哪一类。
 *
 * 标签条是自建的（5 个等宽 TextView + 下方一道 2dp 强调色）：
 * Material 的 TabLayout 自带主题色，跟「运行时重着色」的皮肤体系打架。
 *
 * 换皮肤 / 换深色浅色后直接 `recreate()`：一次性把整套界面按新调色板重画，
 * 比在内存里逐个改控件安全得多（也顺手把系统栏颜色一起刷了）。
 */
class SettingsActivity : LsActivity() {

    private val TAB_TITLES = listOf("练习", "标注", "外观", "存储", "模型")
    private lateinit var tabBar: LinearLayout
    private lateinit var tabContent: FrameLayout
    private val tabViews = ArrayList<LinearLayout>()
    private val tabUnderlines = ArrayList<View>()
    private var pages: List<View> = emptyList()

    // 练习页
    private lateinit var tvDbValue: TextView
    private lateinit var tvVadValue: TextView
    private lateinit var tvGlowTimes: TextView
    private lateinit var tvGlowValue: TextView
    private lateinit var seekDb: SeekBar
    private lateinit var seekVad: SeekBar
    private lateinit var seekGlowTimes: SeekBar
    private lateinit var seekGlow: SeekBar
    private lateinit var rgPunct: RadioGroup
    private lateinit var rgGlowScope: RadioGroup
    private lateinit var rgGlowStrength: RadioGroup
    private lateinit var spLevel: Spinner
    private lateinit var tvLevelInfo: TextView
    private lateinit var tvLevelDetail: TextView
    // R12 说得顺不顺
    private lateinit var swFluency: Switch
    private lateinit var tvFluencyGap: TextView
    private lateinit var tvFluencyTop: TextView
    private lateinit var seekFluencyGap: SeekBar
    private lateinit var seekFluencyTop: SeekBar

    // 标注页
    private lateinit var swAnnot: Switch
    // 四层标注（标注多层次设计 v1）：每层独立可关
    private lateinit var swL1: Switch          // L1 代词标红（红 + 下划线）
    private lateinit var swL1Model: Switch     // L1 子：用大模型标代词（PC 判定关时置灰）
    private lateinit var swL1Filler: Switch    // L1 子：也标口头禅（旧「口头禅」并入）
    private lateinit var swL2: Switch          // L2 不完整句（橙下划线 + ⚠）
    private lateinit var swL2Model: Switch     // L2 子：用大模型判（PC 判定关时置灰）
    private lateinit var swL3: Switch          // L3 指代歧义（暗金 ?，模型专属）
    private lateinit var swL4: Switch          // L4 更清楚的说法（蓝绿 →，模型专属）
    private lateinit var seekAnnFont: SeekBar  // 标注字号 8..16sp
    private lateinit var tvAnnFont: TextView
    private lateinit var btnEditPronouns: View
    // 声纹：每个已登记的人一个「加不加名字」开关（运行时按档案动态生成）
    private lateinit var llSpkToggles: LinearLayout
    private lateinit var tvSpkNone: TextView

    // 外观页
    private lateinit var rgColor: RadioGroup
    private lateinit var rgSkin: RadioGroup
    private lateinit var swNav: Switch

    // 存储页
    private lateinit var tvStorage: TextView
    private lateinit var tvDirs: TextView
    private lateinit var spHoldDays: Spinner
    private lateinit var spHoldGb: Spinner
    private lateinit var swKeepText: Switch

    // 模型页
    private lateinit var tvModelStatus: TextView
    private lateinit var tvPerm: TextView
    private lateinit var tvNetStatus: TextView
    private lateinit var tvPcAddr: TextView
    private lateinit var rgNet: RadioGroup
    private lateinit var rgLang: RadioGroup
    private lateinit var swPcJudge: Switch
    // 说话人声纹（可选）
    private lateinit var tvSpkStatus: TextView
    private lateinit var tvSpkList: TextView
    private lateinit var btnSpkLoad: View
    private lateinit var btnSpkAdd: View
    private lateinit var btnSpkManage: View
    private val REQ_REC_AUDIO = 4321

    private val asrExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun hasAllFiles(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    // ================= 生命周期 =================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        tabBar = findViewById(R.id.tabBar)
        tabContent = findViewById(R.id.tabContent)
        buildTabs()
        // ⚠️ 不能在这里 bindPractice()/bindModel()…：五个分区页面是 selectTab() 里才 inflate 的，
        //    此时 findViewById 全返回 null，字段被赋成 null，随后 initXxx() 一用就 NPE
        //    —— 真机「按设置按钮直接跳出（闪退）」的根因（2026-10-23）。绑定挪到 selectTab() 里。
        findViewById<TextView>(R.id.tvVersion).text = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "" }
        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        selectTab(intent.getIntExtra("tab", 0))
        Ui.hideStatusBar(this)
    }

    override fun onResume() {
        super.onResume()
        refreshPerm()
        refreshModelStatus()
        refreshStorage()
        refreshLevel()
        refreshNet()
        refreshSpkStatus()
        // 从模型页录完/删完人回来，标注页的逐人开关要跟着变
        if (::llSpkToggles.isInitialized) rebuildSpkToggles()
    }

    // ================= 标签 =================

    private fun buildTabs() {
        val p = Skin.palette(this)
        val d = resources.displayMetrics.density
        tabBar.removeAllViews()
        tabViews.clear(); tabUnderlines.clear()
        for ((i, t) in TAB_TITLES.withIndex()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(0, (8 * d).toInt(), 0, 0)
                isClickable = true
                setOnClickListener { selectTab(i) }
            }
            val label = TextView(this).apply {
                text = t
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) p.accent else p.text2)
            }
            val underline = View(this).apply {
                layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (2 * d).toInt()).apply {
                    topMargin = (7 * d).toInt()
                }
                setBackgroundColor(if (i == 0) p.accent else 0x00000000)
            }
            item.addView(label); item.addView(underline)
            item.tag = label
            tabBar.addView(item)
            tabViews.add(item); tabUnderlines.add(underline)
        }
    }

    private fun selectTab(index: Int) {
        if (pages.isEmpty()) {
            // 五个分区一次性 inflate，之后只切可见性：来回切标签不重建界面
            pages = listOf(
                layoutInflater.inflate(R.layout.settings_practice, tabContent, false),
                layoutInflater.inflate(R.layout.settings_mark, tabContent, false),
                layoutInflater.inflate(R.layout.settings_appearance, tabContent, false),
                layoutInflater.inflate(R.layout.settings_storage, tabContent, false),
                layoutInflater.inflate(R.layout.settings_model, tabContent, false)
            )
            tabContent.removeAllViews()
            for (v in pages) tabContent.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // 后 inflate 进来的页面还没被基类重着色过，这里补一次
            Skin.recolor(tabContent)
            // 绑定必须在页面 inflate 之后（见 onCreate 的注释）
            bindPractice(); bindMark(); bindAppearance(); bindStorage(); bindModel()
            initPractice(); initMark(); initAppearance(); initStorage(); initModel()
        }
        val idx = index.coerceIn(0, pages.size - 1)
        pages.forEachIndexed { i, v -> v.visibility = if (i == idx) View.VISIBLE else View.GONE }
        // 切回标注页时刷新一次置灰：L3/L4 和 L1/L2 的「用大模型」都依赖 PcJudge，
        // 而 PcJudge 是在「模型与通道」页开关的 —— 那边改了，这边要跟着变。
        if (idx == 1) applyAnnotEnabled()
        val p = Skin.palette(this)
        for (i in tabViews.indices) {
            (tabViews[i].tag as? TextView)?.setTextColor(if (i == idx) p.accent else p.text2)
            tabUnderlines[i].setBackgroundColor(if (i == idx) p.accent else 0x00000000)
        }
    }

    /** SeekBar 只要「值变了」这一件事，几个滑杆共用一个空实现壳子 */
    private abstract class SimpleSeek : SeekBar.OnSeekBarChangeListener {
        abstract fun onChange(p: Int)
        override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }

    private fun simpleAdapter(items: List<String>): android.widget.ArrayAdapter<String> =
        android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    // ================= 练习页 =================

    private fun bindPractice() {
        tvDbValue = findViewById(R.id.tvDbValue)
        tvVadValue = findViewById(R.id.tvVadValue)
        tvGlowTimes = findViewById(R.id.tvGlowTimes)
        tvGlowValue = findViewById(R.id.tvGlowValue)
        swFluency = findViewById(R.id.swFluency)
        tvFluencyGap = findViewById(R.id.tvFluencyGap)
        tvFluencyTop = findViewById(R.id.tvFluencyTop)
        seekFluencyGap = findViewById(R.id.seekFluencyGap)
        seekFluencyTop = findViewById(R.id.seekFluencyTop)
        seekDb = findViewById(R.id.seekDb)
        seekVad = findViewById(R.id.seekVad)
        seekGlowTimes = findViewById(R.id.seekGlowTimes)
        seekGlow = findViewById(R.id.seekGlow)
        rgPunct = findViewById(R.id.rgPunct)
        rgGlowScope = findViewById(R.id.rgGlowScope)
        rgGlowStrength = findViewById(R.id.rgGlowStrength)
        spLevel = findViewById(R.id.spLevel)
        tvLevelInfo = findViewById(R.id.tvLevelInfo)
        tvLevelDetail = findViewById(R.id.tvLevelDetail)
    }

    private fun initPractice() {
        seekDb.max = 40
        seekDb.progress = Prefs.dbThreshold(this) - 10
        tvDbValue.text = Prefs.dbThreshold(this).toString()
        seekDb.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = p + 10
                Prefs.setDbThreshold(this@SettingsActivity, v)
                tvDbValue.text = v.toString()
            }
        })

        seekVad.max = 8
        seekVad.progress = ((Prefs.vadSilenceMs(this) - 400) / 100).coerceIn(0, 8)
        tvVadValue.text = "${Prefs.vadSilenceMs(this)}ms"
        seekVad.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = 400 + p * 100
                Prefs.setVadSilenceMs(this@SettingsActivity, v)
                tvVadValue.text = "${v}ms"
            }
        })

        rgPunct.check(if (Prefs.punctMode(this) == 0) R.id.rbPunctOff else R.id.rbPunctRule)
        rgPunct.setOnCheckedChangeListener { _, id ->
            Prefs.setPunctMode(this, if (id == R.id.rbPunctOff) 0 else 1)
        }

        rgGlowScope.check(when (Prefs.glowScope(this)) {
            1 -> R.id.rbScopeFull
            2 -> R.id.rbScopeOff
            else -> R.id.rbScopeVideo
        })
        rgGlowScope.setOnCheckedChangeListener { _, id ->
            Prefs.setGlowScope(this, when (id) {
                R.id.rbScopeFull -> 1
                R.id.rbScopeOff -> 2
                else -> 0
            })
        }

        seekGlowTimes.max = 5
        seekGlowTimes.progress = (Prefs.glowTimes(this) - 1).coerceIn(0, 5)
        tvGlowTimes.text = "${Prefs.glowTimes(this)} 次"
        seekGlowTimes.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = p + 1
                Prefs.setGlowTimes(this@SettingsActivity, v)
                tvGlowTimes.text = "$v 次"
            }
        })

        rgGlowStrength.check(when (Prefs.glowStrength(this)) {
            1 -> R.id.rbStr1
            3 -> R.id.rbStr3
            else -> R.id.rbStr2
        })
        rgGlowStrength.setOnCheckedChangeListener { _, id ->
            Prefs.setGlowStrength(this, when (id) {
                R.id.rbStr1 -> 1
                R.id.rbStr3 -> 3
                else -> 2
            })
        }

        seekGlow.max = 9
        seekGlow.progress = (Prefs.glowDebounceSec(this) - 1).coerceIn(0, 9)
        tvGlowValue.text = "${Prefs.glowDebounceSec(this)}s"
        seekGlow.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = p + 1
                Prefs.setGlowDebounceSec(this@SettingsActivity, v)
                tvGlowValue.text = "${v}s"
            }
        })

        initFluency()

        spLevel.adapter = simpleAdapter(listOf(
            "自动（按第几次会话递进）", "1 · L1 指代词", "2 · ＋L3 口头禅",
            "3 · ＋L2 模糊词", "4 · ＋L4-a 缺主体", "5 · 全开"))
        spLevel.setSelection(Prefs.levelOverride(this).coerceIn(0, 5), false)
        spLevel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setLevelOverride(this@SettingsActivity, pos); refreshLevel()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        refreshLevel()
    }

    // ---- R12「说得顺不顺」：三个副产品的开关与阈值 ----

    private fun initFluency() {
        swFluency.isChecked = Prefs.fluencyEnabled(this)
        swFluency.setOnCheckedChangeListener { _, v -> Prefs.setFluencyEnabled(this, v) }

        // 0.8s ~ 3.0s，步长 0.1s（22 档）。下限有意卡在 800：不能比 VAD 断句阈值还小
        seekFluencyGap.max = 22
        seekFluencyGap.progress = ((Prefs.fluencyGapMs(this) - 800) / 100).coerceIn(0, 22)
        tvFluencyGap.text = gapLabel(Prefs.fluencyGapMs(this))
        seekFluencyGap.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = 800 + p * 100
                Prefs.setFluencyGapMs(this@SettingsActivity, v)
                tvFluencyGap.text = gapLabel(v)
            }
        })

        seekFluencyTop.max = 4
        seekFluencyTop.progress = (Prefs.fluencyTopN(this) - 1).coerceIn(0, 4)
        tvFluencyTop.text = "${Prefs.fluencyTopN(this)} 处"
        seekFluencyTop.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = p + 1
                Prefs.setFluencyTopN(this@SettingsActivity, v)
                tvFluencyTop.text = "$v 处"
            }
        })
    }

    private fun gapLabel(ms: Int): String = String.format(java.util.Locale.US, "%.1fs", ms / 1000f)

    private fun refreshLevel() {
        if (!::tvLevelInfo.isInitialized) return
        val n = Prefs.sessionCount(this)
        val override = Prefs.levelOverride(this)
        val lv = RuleEngine.levels(n, override)
        val auto = RuleEngine.autoLevelIndex(n)
        tvLevelInfo.text = buildString {
            append("这是第 ").append(n).append(" 次会话")
            if (override == 0) append(" · 自动到第 ").append(auto).append(" 级") else append(" · 已手动锁定")
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

    // ================= 标注页 =================

    private fun bindMark() {
        swAnnot = findViewById(R.id.swAnnot)
        swL1 = findViewById(R.id.swL1)
        swL1Model = findViewById(R.id.swL1Model)
        swL1Filler = findViewById(R.id.swL1Filler)
        swL2 = findViewById(R.id.swL2)
        swL2Model = findViewById(R.id.swL2Model)
        swL3 = findViewById(R.id.swL3)
        swL4 = findViewById(R.id.swL4)
        seekAnnFont = findViewById(R.id.seekAnnFont)
        tvAnnFont = findViewById(R.id.tvAnnFont)
        btnEditPronouns = findViewById(R.id.btnEditPronouns)
        llSpkToggles = findViewById(R.id.llSpkToggles)
        tvSpkNone = findViewById(R.id.tvSpkNone)
    }

    private fun initMark() {
        swAnnot.isChecked = Prefs.annotEnabled(this)
        swL1.isChecked = Prefs.annotL1(this)
        swL1Model.isChecked = Prefs.annotL1Model(this)
        swL1Filler.isChecked = Prefs.annotL1Filler(this)
        swL2.isChecked = Prefs.annotL2(this)
        swL2Model.isChecked = Prefs.annotL2Model(this)
        swL3.isChecked = Prefs.annotL3(this)
        swL4.isChecked = Prefs.annotL4(this)
        applyAnnotEnabled()
        swAnnot.setOnCheckedChangeListener { _, v -> Prefs.setAnnotEnabled(this, v); applyAnnotEnabled() }
        swL1.setOnCheckedChangeListener { _, v -> Prefs.setAnnotL1(this, v); applyAnnotEnabled() }
        swL1Model.setOnCheckedChangeListener { _, v -> Prefs.setAnnotL1Model(this, v) }
        swL1Filler.setOnCheckedChangeListener { _, v -> Prefs.setAnnotL1Filler(this, v) }
        swL2.setOnCheckedChangeListener { _, v -> Prefs.setAnnotL2(this, v); applyAnnotEnabled() }
        swL2Model.setOnCheckedChangeListener { _, v -> Prefs.setAnnotL2Model(this, v) }
        swL3.setOnCheckedChangeListener { _, v -> Prefs.setAnnotL3(this, v) }
        swL4.setOnCheckedChangeListener { _, v -> Prefs.setAnnotL4(this, v) }

        // 标注字号 8..16sp，默认 10（9 档）。作用于所有标注文字与符号
        seekAnnFont.max = 8
        val fs = Prefs.annotFontSizeSp(this)
        seekAnnFont.progress = (fs - 8).coerceIn(0, 8)
        tvAnnFont.text = "${fs}sp"
        seekAnnFont.setOnSeekBarChangeListener(object : SimpleSeek() {
            override fun onChange(p: Int) {
                val v = 8 + p
                Prefs.setAnnotFontSizeSp(this@SettingsActivity, v)
                tvAnnFont.text = "${v}sp"
            }
        })

        refreshPronounButton()
        btnEditPronouns.setOnClickListener { editPronounList() }
        findViewById<Button>(R.id.btnLegend).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("识别标注图例")
                .setMessage(com.oska.loudspeak.core.Annotator.legend() + "\n\n（这些都能在上面逐项关掉）")
                .setPositiveButton("知道了", null)
                .show()
        }
        // 声纹：铺出每个人的开关，并给一个进「录入/管理」的入口（切到模型页，那里有全套）
        rebuildSpkToggles()
        findViewById<Button>(R.id.btnSpkManage).setOnClickListener { selectTab(4) }   // 4 = 「模型」页（声纹录入/装模型都在那）
    }

    /**
     * 声纹逐人开关。
     *
     * 为什么放在「标注」页：用户反馈「入录声纹后在标注里找不到每个声纹的开关」——
     * 原来它埋在「模型」页的二级弹窗里（管理已登记的声纹 → 点名字 → 关闭标注），
     * 等于没有。现在按档案直接铺成一行一个人，一眼能关。
     */
    private fun rebuildSpkToggles() {
        if (!::llSpkToggles.isInitialized) return
        llSpkToggles.removeAllViews()
        val list = SpeakerProfileStore(Prefs.modelsDir(this)).load()
        tvSpkNone.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val d = resources.displayMetrics.density
        for (p in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
            }
            row.addView(TextView(this).apply {
                text = p.name
                textSize = 15f
                setTextColor(Skin.palette(this@SettingsActivity).text)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(Switch(this).apply {
                isChecked = p.transcribe
                setOnCheckedChangeListener { _, v ->
                    // 只改这一个人的这一位，别整表覆盖（避免把别人的改动冲掉）
                    val st = SpeakerProfileStore(Prefs.modelsDir(this@SettingsActivity))
                    val l = st.load()
                    val i = l.indexOfFirst { it.id == p.id }
                    if (i >= 0) { l[i] = l[i].copy(transcribe = v); st.save(l) }
                }
            })
            llSpkToggles.addView(row)
        }
    }

    /**
     * 主开关关掉时，下面所有层灰掉（看得见但不能按，不能直接消失）。
     * 另外：依赖 PC 判定的开关（L1/L2 的「用大模型」、L3、L4）在 PcJudge 没开时再灰一层
     * —— 让她看得见有这个功能，也看得见为什么现在点不动。
     */
    private fun applyAnnotEnabled() {
        val on = swAnnot.isChecked
        val pc = Prefs.pcJudgeEnabled(this)

        fun gate(v: View, enabled: Boolean) {
            v.isEnabled = enabled
            v.alpha = if (enabled) 1f else 0.4f
        }

        // 主开关关掉 → 整页灰掉（看得见，但按不动，不能直接消失）
        for (v in listOf<View>(swL1, swL1Model, swL1Filler, swL2, swL2Model,
            swL3, swL4, seekAnnFont, tvAnnFont)) {
            gate(v, on)
        }
        // 父层关掉 → 它的子开关跟着灰掉
        gate(swL1Model, on && swL1.isChecked && pc)
        gate(swL1Filler, on && swL1.isChecked)
        gate(swL2Model, on && swL2.isChecked && pc)
        // 模型专属层：PcJudge 没开 → 灰掉（看得见有这功能，也看得见为什么点不动）
        gate(swL3, on && pc)
        gate(swL4, on && pc)
    }

    /** 按钮上顺手显示当前词表个数 */
    private fun refreshPronounButton() {
        if (!::btnEditPronouns.isInitialized) return
        val n = Prefs.pronounList(this).size
        (btnEditPronouns as? android.widget.TextView)?.text = "编辑代词词表（$n 个）"
    }

    /**
     * 编辑代词词表（方案 A）：一个填写窗口，每行一个词，可增删改。
     * 规则：
     *  - 通配符：`?` = 一个字，`*` = 多个字（直到空白/标点）。
     *    例如 `这?` 覆盖 这个/这些/这样/这边…， `那?` 覆盖 那个/那些/那样/那边…。
     *  - `*` 在末尾会把「这个词开头的整段」都标成代词，即「这个三角形 / 那个红色方块」整段提示换名字。
     *  - 不带通配符的按原词精确匹配（如 它、这边）。
     *  - 空行忽略；保存后实时判断与回放标注都用这一套。
     */
    private fun editPronounList() {
        val cur = Prefs.pronounList(this)
        val edit = EditText(this).apply {
            setText(cur.joinToString("\n"))
            setSelection(text.length)
            hint = "每行一个词，支持通配符：\n这?\n那?\n这个*\n它"
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            minLines = 4
            maxLines = 10
            val d = resources.displayMetrics.density
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        AlertDialog.Builder(this)
            .setTitle("编辑代词词表")
            .setMessage("每行一个词，可增删改。\n通配符：? = 一个字，* = 多个字。\n例：这? 覆盖 这个/这些/这样…；这个* 把「这个X」整段标红；它 按原词精确匹配。空行忽略。")
            .setView(edit)
            .setNegativeButton("取消", null)
            .setNeutralButton("恢复默认") { _, _ ->
                Prefs.resetPronounList(this)
                refreshPronounButton()
                Toast.makeText(this, "已恢复出厂词表 ${Prefs.DEFAULT_PRONOUNS.size} 个", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("保存") { _, _ ->
                val list = edit.text.toString().lineSequence()
                    .map { it.trim() }.filter { it.isNotEmpty() }.toList()
                if (list.isEmpty()) {
                    Toast.makeText(this, "词表不能为空，已保留原词表", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Prefs.setPronounList(this, list)
                refreshPronounButton()
                Toast.makeText(this, "已保存 $list 个词", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ================= 外观页 =================

    private fun bindAppearance() {
        rgColor = findViewById(R.id.rgColor)
        rgSkin = findViewById(R.id.rgSkin)
        swNav = findViewById(R.id.swNav)
    }

    private fun initAppearance() {
        rgColor.check(when (Prefs.colorMode(this)) {
            1 -> R.id.rbLight
            2 -> R.id.rbAuto
            else -> R.id.rbDark
        })
        rgColor.setOnCheckedChangeListener { _, id ->
            val target = when (id) {
                R.id.rbLight -> 1
                R.id.rbAuto -> 2
                else -> 0
            }
            if (target != Prefs.colorMode(this)) {
                Prefs.setColorMode(this, target)
                restartForSkin()
            }
        }

        // 皮肤选项带一句性格描述（两行），比一行纯名字好选
        val p = Skin.palette(this)
        val ids = listOf(R.id.rbSkinClassic, R.id.rbSkinPaper, R.id.rbSkinTerminal, R.id.rbSkinNeon)
        for ((i, id) in ids.withIndex()) {
            val rb = findViewById<android.widget.RadioButton>(id)
            val name = Skin.skinName(i)
            val ss = SpannableString("$name\n${Skin.skinDesc(i)}")
            ss.setSpan(RelativeSizeSpan(0.80f), name.length + 1, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ss.setSpan(ForegroundColorSpan(p.text2), name.length + 1, ss.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            rb.setText(ss)
        }
        rgSkin.check(ids[Prefs.skin(this).coerceIn(0, 3)])
        rgSkin.setOnCheckedChangeListener { _, id ->
            val idx = ids.indexOf(id).coerceAtLeast(0)
            if (idx != Prefs.skin(this)) {
                Prefs.setSkin(this, idx)
                restartForSkin()
            }
        }

        swNav.isChecked = Prefs.navHidden(this)
        swNav.setOnCheckedChangeListener { _, v ->
            Prefs.setNavHidden(this, v)
            Ui.applySystemBars(this, Skin.palette(this), v)
        }
    }

    /** 换皮肤 / 换色彩模式：直接重建这个界面，整套按新调色板重画 */
    private fun restartForSkin() {
        recreate()
    }

    // ================= 存储页 =================

    private fun bindStorage() {
        tvStorage = findViewById(R.id.tvStorage)
        tvDirs = findViewById(R.id.tvDirs)
        spHoldDays = findViewById(R.id.spHoldDays)
        spHoldGb = findViewById(R.id.spHoldGb)
        swKeepText = findViewById(R.id.swKeepText)
    }

    private fun initStorage() {
        swKeepText.isChecked = Prefs.keepTextOnDelete(this)
        swKeepText.setOnCheckedChangeListener { _, v -> Prefs.setKeepTextOnDelete(this, v) }

        val dayOpts = listOf(7, 14, 30, 60, 90, 180)
        spHoldDays.adapter = simpleAdapter(dayOpts.map { "$it 天" })
        spHoldDays.setSelection(dayOpts.indexOf(Prefs.retentionDays(this)).let { if (it < 0) 2 else it }, false)
        spHoldDays.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setRetentionDays(this@SettingsActivity, dayOpts[pos]); refreshStorage()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        val gbOpts = listOf(2, 5, 10, 20, 50)
        spHoldGb.adapter = simpleAdapter(gbOpts.map { "$it GB" })
        spHoldGb.setSelection(gbOpts.indexOf(Prefs.retentionGb(this)).let { if (it < 0) 2 else it }, false)
        spHoldGb.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setRetentionGb(this@SettingsActivity, gbOpts[pos]); refreshStorage()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnChangeDir).setOnClickListener { changeDirDialog() }
        findViewById<Button>(R.id.btnMigrate).setOnClickListener { migrateDialog() }
        findViewById<Button>(R.id.btnResetDir).setOnClickListener { resetDir() }
        findViewById<Button>(R.id.btnCleanup).setOnClickListener { showCleanup() }
        findViewById<Button>(R.id.btnTrash).setOnClickListener {
            startActivity(Intent(this, TrashActivity::class.java))
        }
        findViewById<Button>(R.id.btnEmptyTrash).setOnClickListener { confirmEmptyTrash() }
    }

    private fun refreshStorage() {
        if (!::tvStorage.isInitialized) return
        tvStorage.text = "统计中…"
        asrExecutor.execute {
            val st = SessionStore(this)
            val trash = st.trashSizeBytes()
            val sessions = (st.sessionsSizeBytes() - trash).coerceAtLeast(0)
            val root = File(Prefs.modelsDir(this)).parentFile ?: filesDir
            val free = root.usableSpace
            val oldest = st.oldestSessions(1).firstOrNull()
            val ageDays = oldest?.let { (System.currentTimeMillis() - it.startedAt) / 86_400_000 } ?: 0
            val limitGb = Prefs.retentionGb(this)
            val limitDays = Prefs.retentionDays(this)
            val warn = StringBuilder()
            if (sessions > limitGb * 1_000_000_000L) warn.append("⚠ 会话已超过 ${limitGb}GB，可以看看最旧的几条\n")
            if (ageDays > limitDays) warn.append("⚠ 最旧的一条已经 $ageDays 天（超过 $limitDays 天，只提示不自动删）\n")
            if (free in 1..2_000_000_000L) warn.append("⚠ 剩余空间不足 2GB\n")
            val txt = "${warn}会话 ${gb(sessions)} · 回收站 ${gb(trash)} · 可用 ${gb(free)}"
            val dirs = buildString {
                val custom = Prefs.sessionsRootCustom(this@SettingsActivity)
                append("模型：").append(Prefs.modelsDir(this@SettingsActivity)).append('\n')
                append("录制：").append(Prefs.sessionsRoot(this@SettingsActivity))
                append(if (custom.isNotBlank()) "（自定义）" else "（默认）")
            }
            runOnUiThread { tvStorage.text = txt; tvDirs.text = dirs }
        }
    }

    private fun gb(b: Long): String = "%.2fGB".format(b / 1e9)

    private fun showCleanup() {
        val st = SessionStore(this)
        val olds = st.oldestSessions(10)
        if (olds.isEmpty()) { Toast.makeText(this, "还没有会话记录", Toast.LENGTH_SHORT).show(); return }
        val labels = olds.map { r ->
            val d = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(r.startedAt))
            "   %s · %d分钟 · %.0fMB".format(d, r.durationMs / 60000, st.dirSizeBytes(File(r.dirPath)) / 1e6)
        }.toTypedArray()
        val checked = BooleanArray(olds.size)
        val keepText = Prefs.keepTextOnDelete(this)
        AlertDialog.Builder(this)
            .setTitle("清理建议：最旧的 ${olds.size} 条")
            .setMessage(
                if (keepText) "会移入回收站（可恢复）；按当前设置只删音视频、保留转写文字。"
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

    private fun confirmEmptyTrash() {
        val st = SessionStore(this)
        if (st.listTrash().isEmpty()) { Toast.makeText(this, "回收站是空的", Toast.LENGTH_SHORT).show(); return }
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

    private fun changeDirDialog() {
        val input = EditText(this).apply {
            setText(
                if (Prefs.sessionsRootCustom(this@SettingsActivity).isNotBlank())
                    Prefs.sessionsRootCustom(this@SettingsActivity)
                else Prefs.sessionsRoot(this@SettingsActivity))
            setSelection(text.length)
            hint = "/storage/emulated/0/SpeakClear/sessions"
        }
        val quick = arrayOf(
            "App 目录（默认，免权限）",
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

    private fun askMigrateThen(path: String) {
        AlertDialog.Builder(this)
            .setTitle("把已有记录一起搬过去吗？")
            .setMessage("搬移可能持续几十秒，别中途退出。\n不搬也可以：老记录留在原处，只是新的写到这里。")
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
            val (n, err) = if (targetPath != null) st.migrateTo(targetPath)
            else st.migrateFrom(Prefs.defaultSessionsRoot(this))
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
            .setMessage("只会把「之后录的」写回默认位置，已有记录仍留在当前目录里，不会丢。")
            .setPositiveButton("恢复默认") { _, _ ->
                Prefs.setSessionsRootCustom(this, "")
                Toast.makeText(this, "已恢复默认目录", Toast.LENGTH_SHORT).show()
                refreshStorage()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 模型与通道 =================

    private fun bindModel() {
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvPerm = findViewById(R.id.tvPerm)
        tvNetStatus = findViewById(R.id.tvNetStatus)
        tvPcAddr = findViewById(R.id.tvPcAddr)
        rgNet = findViewById(R.id.rgNet)
        swPcJudge = findViewById(R.id.swPcJudge)
        rgLang = findViewById(R.id.rgLang)
        tvSpkStatus = findViewById(R.id.tvSpkStatus)
        tvSpkList = findViewById(R.id.tvSpkList)
        btnSpkLoad = findViewById(R.id.btnSpkLoad)
        btnSpkAdd = findViewById(R.id.btnSpkAdd)
        btnSpkManage = findViewById(R.id.btnSpkManage)
    }

    private fun initModel() {
        findViewById<Button>(R.id.btnScan).setOnClickListener { scanModels() }
        findViewById<Button>(R.id.btnSaf).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 200)
        }
        // 手动选文件：比「选目录」更绕得开分区存储限制，用户点到模型所在文件夹直接勾文件
        findViewById<Button>(R.id.btnPick).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try { startActivityForResult(i, 201) } catch (e: Exception) {
                Toast.makeText(this, "打不开文件选择器：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkModel() }
        findViewById<Button>(R.id.btnPerm).setOnClickListener {
            if (hasAllFiles()) Toast.makeText(this, "已经授予了", Toast.LENGTH_SHORT).show()
            else if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Toast.makeText(this, "打不开系统页：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
                }
            }
        }

        rgNet.check(when (Prefs.netMode(this)) {
            1 -> R.id.rbNetLocal
            2 -> R.id.rbNetPc
            else -> R.id.rbNetAuto
        })
        rgNet.setOnCheckedChangeListener { _, id ->
            Prefs.setNetMode(this, when (id) {
                R.id.rbNetLocal -> 1
                R.id.rbNetPc -> 2
                else -> 0
            })
            refreshNet()
        }

        rgLang.check(when (Prefs.asrLangMode(this)) {
            Prefs.ASR_LANG_EN -> R.id.rbLangEn
            Prefs.ASR_LANG_AUTO -> R.id.rbLangAuto
            Prefs.ASR_LANG_RAW -> R.id.rbLangRaw
            else -> R.id.rbLangZh
        })
        rgLang.setOnCheckedChangeListener { _, id ->
            Prefs.setAsrLangMode(this, when (id) {
                R.id.rbLangEn -> Prefs.ASR_LANG_EN
                R.id.rbLangAuto -> Prefs.ASR_LANG_AUTO
                R.id.rbLangRaw -> Prefs.ASR_LANG_RAW
                else -> Prefs.ASR_LANG_ZH
            })
            Toast.makeText(this, "识别语言已切换，下一段开始生效", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPcIp).setOnClickListener {
            val input = EditText(this).apply {
                setText(Prefs.pcIp(this@SettingsActivity)); setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle("PC 的局域网 IP")
                .setMessage("在 PC 上跑 start-server.bat 或 ipconfig 查 IPv4，形如 192.168.3.15")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    val ip = input.text.toString().trim()
                    if (ip.isNotEmpty()) { Prefs.setPcIp(this@SettingsActivity, ip); refreshNet() }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        swPcJudge.isChecked = Prefs.pcJudgeEnabled(this)
        swPcJudge.setOnCheckedChangeListener { _, v ->
            if (!v) { Prefs.setPcJudgeEnabled(this, false); refreshNet(); return@setOnCheckedChangeListener }
            AlertDialog.Builder(this)
                .setTitle("打开「PC 大模型判定」？")
                .setMessage(
                    "打开后，每次判定会把【本次的句子文字】发到家里这台 PC：\n" +
                    "http://${Prefs.pcIp(this)}:${Prefs.pcJudgePort(this)}\n\n" +
                    "录音文件本身永远不会发。PC 没开、断了、超时，都自动退回手机本地规则，不会卡住。\n\n" +
                    "想要「数据绝不出手机」，保持关闭就行 —— 关着的时候 App 一次网络请求都不会发。"
                )
                .setPositiveButton("我认可，打开") { _, _ ->
                    Prefs.setPcJudgeEnabled(this, true)
                    refreshNet()
                    Toast.makeText(this, "已打开，建议先点下面测一次", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消") { _, _ -> swPcJudge.isChecked = false }
                .setCancelable(false)
                .show()
        }
        findViewById<Button>(R.id.btnPcJudgeTest).setOnClickListener { testPcJudge() }

        findViewById<Button>(R.id.btnSelf).setOnClickListener {
            startActivity(Intent(this, SelfCheckActivity::class.java))
        }
        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            startActivity(Intent(this, CaptureVerifyActivity::class.java))
        }
        findViewById<Button>(R.id.btnErr).setOnClickListener {
            startActivity(Intent(this, ErrorsActivity::class.java))
        }
        btnSpkLoad.setOnClickListener { loadSpeakerDialog() }
        btnSpkAdd.setOnClickListener { addSpeakerFlow() }
        btnSpkManage.setOnClickListener { manageSpeakers() }
        findViewById<Button>(R.id.btnSpkPickFile)?.setOnClickListener { pickSpkFile() }
        findViewById<android.widget.Switch>(R.id.swLowVol)?.apply {
            isChecked = Prefs.annotLowVol(this@SettingsActivity)
            setOnCheckedChangeListener { _, c -> Prefs.setAnnotLowVol(this@SettingsActivity, c) }
        }
        refreshSpkStatus()
        refreshNet()
    }

    private fun refreshNet() {
        if (!::tvNetStatus.isInitialized) return
        val ip = Prefs.pcIp(this)
        val mode = Prefs.netMode(this)
        tvPcAddr.text = "ASR http://$ip:${Prefs.pcAsrPort(this)}\n判定 http://$ip:${Prefs.pcJudgePort(this)}"
        tvNetStatus.text = buildString {
            append("当前选择：").append(NetChannel.name(mode)).append('\n')
            append(NetChannel.statusText(this@SettingsActivity)).append('\n')
            append("PC 大模型判定：")
                .append(if (Prefs.pcJudgeEnabled(this@SettingsActivity)) "开（判定走 PC，关不掉也回退本地）" else "关（纯离线）")
        }
    }

    /**
     * 「测试 PC 判定」：拿两句样例真跑一次 /judge，把结果说成人话。
     * 走的是和正式判定完全相同的那条路（同一个 post、同一份超时），所以它能通就等于判定能用。
     */
    private fun testPcJudge() {
        val ip = Prefs.pcIp(this)
        val port = Prefs.pcJudgePort(this)
        tvNetStatus.text = "测试中… http://$ip:$port/judge\n（真跑一次大模型，十几秒，别退出这一页）"
        asrExecutor.execute {
            val (ok, msg) = PcJudge.probe(ip, port)
            runOnUiThread {
                tvNetStatus.text = buildString {
                    append("PC 判定测试：").append(msg).append('\n')
                    append("PC 大模型判定：")
                        .append(if (Prefs.pcJudgeEnabled(this@SettingsActivity)) "开" else "关")
                }
                Toast.makeText(this, if (ok) "PC 判定可用 ✓" else "PC 判定不通，判定会继续走本地规则",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshPerm() {
        if (!::tvPerm.isInitialized) return
        tvPerm.text = if (hasAllFiles())
            "全盘读取：已授予 ✓（能扫 Download / Documents 里的模型）"
        else "全盘读取：未授予 —— 扫不到 Download 是这个原因，不是文件没放好。"
    }

    private fun refreshModelStatus() {
        if (!::tvModelStatus.isInitialized) return
        val (m, t) = AsrEngine(Prefs.modelsDir(this)).checkFiles()
        tvModelStatus.text = buildString {
            append("ASR 模型　").append(if (m?.isFile == true) "✓ ${m.length() / 1_000_000}MB" else "✗ 缺").append('\n')
            append("ASR 词表　").append(if (t?.isFile == true) "✓" else "✗ 缺").append("\n\n")
            append("识别与判分全部在手机本地完成，不需要也不加载任何大模型。缺什么就补上面那一两种文件，其它都不用管。")
        }
    }

    // ---- 说话人声纹（可选）：加载 / 校验 ----

    private fun refreshSpkStatus() {
        if (!::tvSpkStatus.isInitialized) return
        val eng = SpeakerEngine(Prefs.modelsDir(this))
        val dir = eng.spkDir()
        val models = eng.findModels()
        tvSpkStatus.text = if (models.isEmpty()) buildString {
            append("没找到声纹模型。把 .onnx 拷到：\n")
            append(dir.absolutePath).append('\n')
            append("（fp16 约 14MB / int8 约 8MB；拷完点下面加载）")
        } else buildString {
            append("spk/ 里有 ").append(models.size).append(" 个模型：\n")
            models.forEach { append("· ").append(it.name).append("（").append(it.length() / 1_000_000).append("MB）\n") }
            append("\n点下面「加载」选一个，只验证能否加载与推理。")
        }
        if (::tvSpkList.isInitialized) {
            val profiles = SpeakerProfileStore(Prefs.modelsDir(this)).load()
            tvSpkList.text = if (profiles.isEmpty()) "已登记声纹：还没有"
            else "已登记声纹（${profiles.size}）：" + profiles.joinToString("、") { it.name }
        }
    }

    private fun loadSpeakerDialog() {
        val eng = SpeakerEngine(Prefs.modelsDir(this))
        val files = eng.findModels()
        if (files.isEmpty()) {
            Toast.makeText(this, "spk/ 下没有 .onnx，先把模型拷进去", Toast.LENGTH_LONG).show()
            refreshSpkStatus(); return
        }
        val labels = files.map { "${it.name}（${it.length() / 1_000_000}MB）" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选一个声纹模型加载")
            .setItems(labels) { _, which -> doLoadSpeaker(files[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doLoadSpeaker(f: File) {
        tvSpkStatus.text = "加载并试算中…（${f.name}）"
        asrExecutor.execute {
            val eng = SpeakerEngine(Prefs.modelsDir(this))
            val ok = eng.load(f)
            val statusMsg = eng.status
            val dimv = eng.dim
            val (tok, tmsg) = if (ok) eng.selfTest() else (false to "")
            eng.release()
            runOnUiThread {
                tvSpkStatus.text = when {
                    !ok -> "✗ $statusMsg"
                    tok -> "✓ 加载成功：${f.name}\n$tmsg\n（下一步才会接入录音区分）"
                    else -> "✓ 已加载 ${f.name}（${dimv} 维），但$tmsg"
                }
                Toast.makeText(this,
                    if (ok && tok) "声纹模型可用 ✓" else if (ok) "已加载，但试算未通过" else "加载失败，详情见上方",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addSpeakerFlow() {
        val input = EditText(this).apply { hint = "给这个人起个名字（如 爸爸 / 妈妈 / A）" }
        AlertDialog.Builder(this)
            .setTitle("添加声纹（录 6 秒）")
            .setMessage("让对方对着手机正常说话几秒。名字随便起，用来在文字前标「名字：」。")
            .setView(input)
            .setPositiveButton("开始录音") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "未命名" }
                startEnroll(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startEnroll(name: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_REC_AUDIO)
            Toast.makeText(this, "先授权麦克风，再点一次「添加声纹」", Toast.LENGTH_LONG).show()
            return
        }
        val secs = 6
        val pd = ProgressDialog(this).apply {
            setMessage("正在录【$name】… 还剩 $secs 秒\n请让 TA 正常说话")
            setCancelable(false)
            show()
        }
        asrExecutor.execute {
            val eng = SpeakerEngine(Prefs.modelsDir(this))
            if (!eng.loadFirst()) {
                val st = eng.status
                eng.release()
                runOnUiThread { pd.dismiss(); Toast.makeText(this, "先加载声纹模型：$st", Toast.LENGTH_LONG).show() }
                return@execute
            }
            val pcm = SampleRecorder.record(secs) { left ->
                runOnUiThread { if (pd.isShowing) pd.setMessage("正在录【$name】… 还剩 $left 秒\n请让 TA 正常说话") }
            }
            val emb = eng.embed(pcm)
            eng.release()
            if (emb == null) {
                runOnUiThread { pd.dismiss(); tvSpkStatus.text = "✗ 声纹提取失败（录音太短/太静？）" }
                return@execute
            }
            val ok = SpeakerProfileStore(Prefs.modelsDir(this)).add(name, emb)
            runOnUiThread {
                pd.dismiss()
                if (ok) {
                    Toast.makeText(this, "已添加声纹：$name", Toast.LENGTH_SHORT).show()
                    refreshSpkStatus()
                } else tvSpkStatus.text = "✗ 保存失败（可能和已登记的模型维度不一致，换回同一个模型再录）"
            }
        }
    }

    private fun manageSpeakers() {
        val store = SpeakerProfileStore(Prefs.modelsDir(this))
        val list = store.load()
        if (list.isEmpty()) { Toast.makeText(this, "还没有登记声纹", Toast.LENGTH_SHORT).show(); return }
        val names = list.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("已登记声纹（点一个改名或删除）")
            .setItems(names) { _, which -> speakerActions(list[which]) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun speakerActions(p: SpeakerProfile) {
        val toggle = if (p.transcribe) "关闭标注（不转写这句）" else "开启标注（转写这句）"
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("改名", "删除", toggle)) { _, which ->
                when (which) {
                    0 -> renameSpeaker(p)
                    1 -> {
                        SpeakerProfileStore(Prefs.modelsDir(this)).remove(p.id)
                        Toast.makeText(this, "已删除：${p.name}", Toast.LENGTH_SHORT).show()
                        refreshSpkStatus()
                    }
                    2 -> toggleTranscribe(p)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun toggleTranscribe(p: SpeakerProfile) {
        val store = SpeakerProfileStore(Prefs.modelsDir(this))
        val l = store.load()
        val i = l.indexOfFirst { it.id == p.id }
        if (i >= 0) { l[i] = l[i].copy(transcribe = !l[i].transcribe); store.save(l) }
        Toast.makeText(this,
            if (l.getOrNull(i)?.transcribe == true) "已开启「${p.name}」标注" else "已关闭「${p.name}」标注",
            Toast.LENGTH_SHORT).show()
        refreshSpkStatus()
    }

    /** 从文件管理器选声纹模型 .onnx（可多选：fp16/int8…），直接复制到 spk/ 目录 */
    private fun pickSpkFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(i, 203) } catch (e: Exception) {
            Toast.makeText(this, "打不开文件选择器：${e.message?.take(60)}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importSpkFiles(uris: List<android.net.Uri>) {
        val eng = SpeakerEngine(Prefs.modelsDir(this))
        var ok = 0; var fail = 0
        for (u in uris) {
            val name = com.oska.loudspeak.core.ModelScanner.queryName(this, u) ?: continue
            if (!name.endsWith(".onnx", true)) continue
            if (eng.importUri(this, u, name)) ok++ else fail++
        }
        Toast.makeText(this, "声纹模型已导入 $ok 个${if (fail > 0) "，失败 $fail 个" else ""}", Toast.LENGTH_LONG).show()
        refreshSpkStatus()
    }

    private fun renameSpeaker(p: SpeakerProfile) {
        val input = EditText(this).apply { setText(p.name) }
        AlertDialog.Builder(this)
            .setTitle("改名")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) {
                    SpeakerProfileStore(Prefs.modelsDir(this)).rename(p.id, n)
                    refreshSpkStatus()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 扫描常见位置：把候选 ASR（onnx + tokens）列出来，选一个装一个 */
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
                    if (ok) "已装好：语音识别（回主界面会自动加载）" else "安装失败",
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
        // 201 = 手动多选文件（.onnx + tokens.txt）
        if (req == 201 && res == Activity.RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i)?.uri?.let { uris.add(it) } }
            data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importPicked(uris)
        }
        // 203 = 声纹模型文件（.onnx）多选
        if (req == 203 && res == Activity.RESULT_OK && data != null) {
            val uris = ArrayList<android.net.Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i)?.uri?.let { uris.add(it) } }
            data.data?.let { uris.add(it) }
            if (uris.isNotEmpty()) importSpkFiles(uris)
        }
    }

    /** 手选的若干文件 → ModelScanner 统一分类落位（与首次引导 / 主界面同一套逻辑） */
    private fun importPicked(uris: List<Uri>) {
        tvModelStatus.text = "导入中…（${uris.size} 个文件）"
        asrExecutor.execute {
            val msg = try { ModelScanner.importUris(this, uris, Prefs.modelsDir(this)) }
            catch (e: Exception) { "导入失败：${e.message?.take(80)}" }
            runOnUiThread {
                tvModelStatus.text = "导入结果：$msg"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * SAF 目录树导入：委托给 ModelScanner（首次引导与设置页共用同一套逻辑）。
     * 早先只在设置页有这入口，且埋在二级页里；现在抽到 ModelScanner，首次引导也能直接调。
     */
    private fun importModels(treeUri: Uri) {
        tvModelStatus.text = "导入中…（扫描所选目录含子目录，请勿离开）"
        asrExecutor.execute {
            val msg = try { ModelScanner.importTree(contentResolver, treeUri, Prefs.modelsDir(this)) }
            catch (e: Exception) { "导入失败：${e.message?.take(120)}" }
            runOnUiThread {
                tvModelStatus.text = "导入结果：$msg"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                refreshModelStatus()
            }
        }
    }
}
