package com.oska.loudspeak.core

import android.content.Context
import android.content.SharedPreferences
import android.view.WindowManager
import java.io.File

/**
 * 全局配置（SharedPreferences 落地）。
 * 「第 N 次会话」计数也在这里：③ ④ 档不落盘，但同样计入会话次数（§12.0）。
 */
object Prefs {
    private const val FILE = "loudspeak"

    // 四档模式（§6.1.1）：开始前选定，会中不可切
    const val MODE_FULL = "full"                // ① 完整录制
    const val MODE_AUDIO = "audio"              // ② 仅音频
    const val MODE_DISPLAY_VIDEO = "display_video" // ③ 纯显示·带画面（默认）
    const val MODE_DISPLAY_AUDIO = "display_audio" // ④ 纯显示·音频

    fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getMode(ctx: Context): String = sp(ctx).getString("mode", MODE_DISPLAY_VIDEO)!!
    fun setMode(ctx: Context, m: String) = sp(ctx).edit().putString("mode", m).apply()

    fun dbThreshold(ctx: Context): Int = sp(ctx).getInt("dbThreshold", 30)
    fun setDbThreshold(ctx: Context, v: Int) = sp(ctx).edit().putInt("dbThreshold", v.coerceIn(10, 50)).apply()

    /** 静音断句时长 ms，可调 400–1200（§14.2 B2），默认 800 */
    fun vadSilenceMs(ctx: Context): Int = sp(ctx).getInt("vadSilenceMs", 800)
    fun setVadSilenceMs(ctx: Context, v: Int) = sp(ctx).edit().putInt("vadSilenceMs", v.coerceIn(400, 1200)).apply()

    /** 红晕呈现：0 泛红提醒（默认）/ 1 仅计数不泛红 / 2 完全静默（§7.3） */
    fun glowMode(ctx: Context): Int = sp(ctx).getInt("glowMode", 0)
    fun setGlowMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("glowMode", v).apply()

    /** 红晕防抖秒数，默认 3，可调 1–10（§7.1） */
    fun glowDebounceSec(ctx: Context): Int = sp(ctx).getInt("glowDebounceSec", 3)
    fun setGlowDebounceSec(ctx: Context, v: Int) = sp(ctx).edit().putInt("glowDebounceSec", v.coerceIn(1, 10)).apply()

    /** 红晕呼吸次数：触发一次连做几下（1–6，默认 3） */
    fun glowTimes(ctx: Context): Int = sp(ctx).getInt("glowTimes", 3)
    fun setGlowTimes(ctx: Context, v: Int) = sp(ctx).edit().putInt("glowTimes", v.coerceIn(1, 6)).apply()

    /** 红晕浓烈度：1 淡 / 2 中 / 3 浓（默认 2） */
    fun glowStrength(ctx: Context): Int = sp(ctx).getInt("glowStrength", 2)
    fun setGlowStrength(ctx: Context, v: Int) = sp(ctx).edit().putInt("glowStrength", v.coerceIn(1, 3)).apply()

    /**
     * 红晕范围：0 = 只视频窗口四周一圈（默认）/ 1 = 整个界面四周一圈 / 2 = 关闭红晕。
     * 注：「关闭红晕」不影响卡片里的代词计数 —— 计数照常显示，只是不泛红。
     */
    fun glowScope(ctx: Context): Int = sp(ctx).getInt("glowScope", 0)
    fun setGlowScope(ctx: Context, v: Int) = sp(ctx).edit().putInt("glowScope", v.coerceIn(0, 2)).apply()

    // ---- 外观（R11：深色/浅色 + 皮肤 + 导航栏）----
    /** 色彩模式：0 深色 / 1 浅色 / 2 跟随系统 */
    fun colorMode(ctx: Context): Int = sp(ctx).getInt("colorMode", 0)
    fun setColorMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("colorMode", v.coerceIn(0, 2)).apply()

    /** 皮肤：0 经典 / 1 纸张 / 2 终端 / 3 霓虹 */
    fun skin(ctx: Context): Int = sp(ctx).getInt("skin", 0)
    fun setSkin(ctx: Context, v: Int) = sp(ctx).edit().putInt("skin", v.coerceIn(0, 3)).apply()

    /** 是否隐藏手机导航栏（默认隐藏，边缘上滑可临时唤出） */
    fun navHidden(ctx: Context): Boolean = sp(ctx).getBoolean("navHidden", true)
    fun setNavHidden(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("navHidden", v).apply()

    // ---- 句末断句的语法优化（R11）----
    /**
     * 0 = 只用停顿断句（原行为）/ 1 = 停顿 + 规则标点与短句合并。
     * 「不改字」是硬约定：只加标点、只把碎片并回上一句，一个字都不替换、不删。
     */
    fun punctMode(ctx: Context): Int = sp(ctx).getInt("punctMode", 1)
    fun setPunctMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("punctMode", v.coerceIn(0, 2)).apply()

    // ---- 「说得顺不顺」（R12，SenseVoice 字级时间戳的三个副产品）----
    /**
     * 独立开关，默认开。**不进 L1–L5 分级**（§8.6）：
     * 流畅度是「怎么说」，措辞完整是「说什么」，两个维度混在一张分级表里会变得没法解释。
     * 关掉 = 会话结束不再生成那段报告，实时链路一个字都不受影响。
     */
    fun fluencyEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("fluencyEnabled", true)
    fun setFluencyEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("fluencyEnabled", v).apply()

    /**
     * 卡顿阈值（毫秒），超过这么久才算「停了一下」，默认 1200。
     *
     * ⚠️ 为什么下限卡在 800：[Prefs.vadSilenceMs] 是 VAD 的静音断句阈值（默认 800ms），
     * 卡顿阈值必须**大于**它，否则「正常的句间停顿」和「句内卡壳」会互相打架。
     * 上限 3000：再长就不是卡壳，是停下来想整道题了（那属于讲题节奏，不算表达问题）。
     */
    fun fluencyGapMs(ctx: Context): Int = sp(ctx).getInt("fluencyGapMs", 1200)
    fun setFluencyGapMs(ctx: Context, v: Int) = sp(ctx).edit().putInt("fluencyGapMs", v.coerceIn(800, 3000)).apply()

    /** 报告里列「停得最久」的几处，默认 3 条（可调 1–5）。列太多等于噪音。 */
    fun fluencyTopN(ctx: Context): Int = sp(ctx).getInt("fluencyTopN", 3)
    fun setFluencyTopN(ctx: Context, v: Int) = sp(ctx).edit().putInt("fluencyTopN", v.coerceIn(1, 5)).apply()

    // ---- 网络模型通道（R11 预留）----
    /** 0 自动（探活成功走 PC）/ 1 仅本地 / 2 仅 PC。当前 App 未申请 INTERNET，见 NetChannel。 */
    fun netMode(ctx: Context): Int = sp(ctx).getInt("netMode", 0)
    fun setNetMode(ctx: Context, v: Int) = sp(ctx).edit().putInt("netMode", v.coerceIn(0, 2)).apply()

    // ---- 识别标注体系（§R9）：色彩 + 符号，逐项可关 ----
    fun annotEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("annotEnabled", true)
    fun setAnnotEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotEnabled", v).apply()
    fun annotPronouns(ctx: Context): Boolean = sp(ctx).getBoolean("annotPronouns", true)
    fun setAnnotPronouns(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotPronouns", v).apply()
    fun annotFillers(ctx: Context): Boolean = sp(ctx).getBoolean("annotFillers", true)
    fun setAnnotFillers(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotFillers", v).apply()
    fun annotIssues(ctx: Context): Boolean = sp(ctx).getBoolean("annotIssues", true)
    fun setAnnotIssues(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotIssues", v).apply()

    // ---- 四层标注（标注多层次设计 v1）：L1 代词 / L2 不完整句 / L3 指代歧义 / L4 更清楚的说法 ----
    // 每层独立可关。L1/L2 有「用大模型」子开关；L3/L4 模型专属，PcJudge 关时设置页置灰。
    fun annotL1(ctx: Context): Boolean = sp(ctx).getBoolean("annotL1", true)
    fun setAnnotL1(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotL1", v).apply()

    /** L1 子开关：用大模型标代词，代替本地词表（PcJudge 关时设置页置灰） */
    fun annotL1Model(ctx: Context): Boolean = sp(ctx).getBoolean("annotL1Model", false)
    fun setAnnotL1Model(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotL1Model", v).apply()

    /** L1 子开关：也标口头禅（旧「口头禅」并入 L1，灰字 + 下划线） */
    fun annotL1Filler(ctx: Context): Boolean = sp(ctx).getBoolean("annotL1Filler", true)
    fun setAnnotL1Filler(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotL1Filler", v).apply()

    fun annotL2(ctx: Context): Boolean = sp(ctx).getBoolean("annotL2", true)
    fun setAnnotL2(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotL2", v).apply()

    /** L2 子开关：用大模型判不完整句（PcJudge 关时设置页置灰） */
    fun annotL2Model(ctx: Context): Boolean = sp(ctx).getBoolean("annotL2Model", false)
    fun setAnnotL2Model(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotL2Model", v).apply()

    /** L3 指代歧义（暗金 ?）：模型专属，默认关 */
    fun annotL3(ctx: Context): Boolean = sp(ctx).getBoolean("annotL3", false)
    fun setAnnotL3(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotL3", v).apply()

    /** L4 更清楚的说法（蓝绿 →，点按才展开改写小字）：模型专属，默认关 */
    fun annotL4(ctx: Context): Boolean = sp(ctx).getBoolean("annotL4", false)
    fun setAnnotL4(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotL4", v).apply()

    /** 标注字号（sp）：作用于所有标注文字与符号，8..16，默认 10 */
    fun annotFontSizeSp(ctx: Context): Int = sp(ctx).getInt("annotFontSizeSp", 10)
    fun setAnnotFontSizeSp(ctx: Context, v: Int) =
        sp(ctx).edit().putInt("annotFontSizeSp", v.coerceIn(8, 16)).apply()

    /** 音量不达标下划线标注（独立于四层标注层，默认开；关掉则「偏小」句不再标蓝下划线） */
    fun annotLowVol(ctx: Context): Boolean = sp(ctx).getBoolean("annotLowVol", true)
    fun setAnnotLowVol(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotLowVol", v).apply()

    /** 每人的声纹转写开关：id -> 是否转写（标注）。默认都转写（缺省=转写）。 */
    fun speakerTranscribe(ctx: Context): Map<String, Boolean> {
        val raw = sp(ctx).getString("speakerTranscribe", null) ?: return emptyMap()
        val m = mutableMapOf<String, Boolean>()
        raw.split(";").forEach { p ->
            val i = p.indexOf(':')
            if (i > 0) m[p.substring(0, i)] = p.substring(i + 1) == "1"
        }
        return m
    }
    fun setSpeakerTranscribe(ctx: Context, map: Map<String, Boolean>) =
        sp(ctx).edit().putString("speakerTranscribe",
            map.entries.joinToString(";") { "${it.key}:${if (it.value) 1 else 0}" }).apply()

    /**
     * 本次要送进 /judge 的四层门控：只含「开关开着 **且** 需要模型算」的层。
     * 关掉的层不送 —— 省 PC 端 token 与延时，也不会把用户关掉的东西算出来。
     * 返回 null 表示一层都不需要模型，App 不必发请求。
     */
    fun judgeLevels(ctx: Context): Map<String, Boolean>? {
        val pc = pcJudgeEnabled(ctx)
        val l1 = annotL1(ctx) && annotL1Model(ctx) && pc
        val l2 = annotL2(ctx) && annotL2Model(ctx) && pc
        val l3 = annotL3(ctx) && pc
        val l4 = annotL4(ctx) && pc
        if (!l1 && !l2 && !l3 && !l4) return null
        return mapOf("pronoun" to l1, "incomplete" to l2, "ambiguity" to l3, "rewrite" to l4)
    }

    /**
     * 默认代词词表（§8.1 / R9）：可在设置里编辑。通配符：? = 一个字，* = 多个字（直到空白/标点）。
     * 出厂默认用 `这?` / `那?` 两个就覆盖所有「这/那 + 一个字」的指代词，用户可增删。
     * 「原有字」= 出厂这套，默认就是它，用户在设置里可增删。
     */
    val DEFAULT_PRONOUNS = listOf("这?", "那?")

    /** 代词词表（可编辑）。首次读会自动写回默认，保证后续稳定 */
    fun pronounList(ctx: Context): List<String> {
        val raw = sp(ctx).getString("pronounList", null)
        if (raw == null) {
            setPronounList(ctx, DEFAULT_PRONOUNS)
            return DEFAULT_PRONOUNS
        }
        return raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }
    fun setPronounList(ctx: Context, list: List<String>) =
        sp(ctx).edit().putString("pronounList", list.joinToString("|")).apply()

    /** 一键恢复出厂代词词表 */
    fun resetPronounList(ctx: Context) = setPronounList(ctx, DEFAULT_PRONOUNS)

    // ---- 识别语言（§ASR）：中文 / 英文 / 中文和英文 / 自动 ----
    const val ASR_LANG_ZH = "zh"      // 只保留中文句，英文句整句丢弃
    const val ASR_LANG_EN = "en"      // 只保留英文句，中文句整句丢弃
    const val ASR_LANG_AUTO = "auto"  // 中英混（自动），只留 zh/en，其余丢弃
    const val ASR_LANG_RAW = "all"    // 自动：识别什么就什么，不过滤任何语种
    /** 识别语言档位，默认中文（用户要求；默认中文后说英文会被丢弃，不显示乱码） */
    fun asrLangMode(ctx: Context): String =
        sp(ctx).getString("asrLangMode", ASR_LANG_ZH) ?: ASR_LANG_ZH
    fun setAsrLangMode(ctx: Context, v: String) =
        sp(ctx).edit().putString("asrLangMode", v).apply()

    /**
     * 一次性读出整套标注偏好。
     * 四层（annotL1..L4）是新的权威开关；旧的 pronouns/fillers/issues 由四层推导，
     * 这样老引擎（Annotation.kt）不用改就立刻服从新开关，不会出现「两层打架」。
     */
    fun annotationPrefs(ctx: Context): AnnotationPrefs =
        AnnotationPrefs(
            enabled = annotEnabled(ctx),
            pronouns = annotL1(ctx),
            fillers = annotL1(ctx) && annotL1Filler(ctx),
            issues = annotL2(ctx),
            // 旧「模糊词」本地词表已并入 L3（改由模型判），本地那份不再单独生效
            muzzy = false,
            pronounList = pronounList(ctx),
            l1 = annotL1(ctx),
            l1Model = annotL1Model(ctx),
            l2 = annotL2(ctx),
            l2Model = annotL2Model(ctx),
            l3 = annotL3(ctx),
            l4 = annotL4(ctx),
            fontSizeSp = annotFontSizeSp(ctx),
            lowVol = annotLowVol(ctx),
            speakerTranscribe = speakerTranscribe(ctx)
        )

    /** 模糊词标注（跟 L2 一起开，默认关） */
    fun annotMuzzy(ctx: Context): Boolean = sp(ctx).getBoolean("annotMuzzy", false)
    fun setAnnotMuzzy(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("annotMuzzy", v).apply()

    /** 第 N 次会话计数（所有档位都计，§12.0） */
    fun sessionCount(ctx: Context): Int = sp(ctx).getInt("sessionCount", 0)
    fun incSessionCount(ctx: Context): Int {
        val n = sessionCount(ctx) + 1
        sp(ctx).edit().putInt("sessionCount", n).apply()
        return n
    }

    /** 首次引导是否已完成（§12.1） */
    fun firstRunDone(ctx: Context): Boolean = sp(ctx).getBoolean("firstRunDone", false)
    fun setFirstRunDone(ctx: Context) = sp(ctx).edit().putBoolean("firstRunDone", true).apply()

    /**
     * PC 服务端 IP（§3.3 双通道）。PC 换机 / 换 IP 时只改这一处：
     * ASR = http://<ip>:8010（asr_server.py），判定 = http://<ip>:8011（judge_server.py）。
     */
    fun pcIp(ctx: Context): String = sp(ctx).getString("pcIp", "192.168.3.15")!!
    fun setPcIp(ctx: Context, ip: String) = sp(ctx).edit().putString("pcIp", ip.trim()).apply()
    fun pcAsrPort(ctx: Context): Int = sp(ctx).getInt("pcAsrPort", 8010)

    /**
     * PC 判定服务端口（judge_server.py，默认 8011）。
     * 注意别和 11434 搞混：11434 是 Ollama 自己的端口，手机不直接访问它，
     * 中间隔着 judge_server（负责把大模型的回答翻译成约定好的 JSON）。
     */
    fun pcJudgePort(ctx: Context): Int = sp(ctx).getInt("pcJudgePort", 8011)

    /** 模型目录（app 私有拷贝工作区，SAF 导入后落地于此） */
    fun modelsDir(ctx: Context): String =
        ctx.getExternalFilesDir(null)?.let { "${it.absolutePath}/models" } ?: "${ctx.filesDir.absolutePath}/models"

    /**
     * 局域网 PC 判定桥开关（§R9「PC 局域网加速」路线）。
     * 默认关：关闭时判定数据绝不离开手机（守住「完全离线、数据不出手机」）。
     * 仅当用户明确认可「家庭可信 LAN 内可接受」时才打开；打开后终判会把本次分句 POST 到
     * http://Prefs.pcIp:Prefs.pcJudgePort/judge（家里 PC 跑 judge_server.py）。
     */
    fun pcJudgeEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("pcJudgeEnabled", false)
    fun setPcJudgeEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("pcJudgeEnabled", v).apply()

    // ================= 存储位置（§11.1：可改地址，只影响新会话） =================

    /** 默认会话根目录（app 外部私有，免权限写入；文件管理器/USB 可访问） */
    fun defaultSessionsRoot(ctx: Context): String =
        ctx.getExternalFilesDir(null)?.let { "${it.absolutePath}/SpeakClear/sessions" }
            ?: "${ctx.filesDir.absolutePath}/SpeakClear/sessions"

    /**
     * 自定义会话目录（空 = 用默认）。
     * 只在用户于设置页主动改过之后才有值；改完**只影响新会话**，
     * 老记录留在原处不会被悄悄搬走（§11.1），要搬得点「迁移已有记录」。
     */
    fun sessionsRootCustom(ctx: Context): String = sp(ctx).getString("sessionsRootCustom", "") ?: ""
    fun setSessionsRootCustom(ctx: Context, path: String) =
        sp(ctx).edit().putString("sessionsRootCustom", path.trim()).apply()

    /** 会话根目录：有自定义就用自定义，否则默认 */
    fun sessionsRoot(ctx: Context): String =
        sessionsRootCustom(ctx).ifBlank { defaultSessionsRoot(ctx) }

    // ================= 保留策略（§11.5 / E1 / E4） =================

    /** 会话保留提示天数，默认 30；超了**只提示不自动删**（E3） */
    fun retentionDays(ctx: Context): Int = sp(ctx).getInt("retentionDays", 30)
    fun setRetentionDays(ctx: Context, v: Int) = sp(ctx).edit().putInt("retentionDays", v.coerceIn(7, 365)).apply()

    /** 会话占用提示阈值（GB），默认 10；同样只提示不自动删 */
    fun retentionGb(ctx: Context): Int = sp(ctx).getInt("retentionGb", 10)
    fun setRetentionGb(ctx: Context, v: Int) = sp(ctx).edit().putInt("retentionGb", v.coerceIn(1, 128)).apply()

    /** 回收站保留天数，固定 7（E1），不放到设置里让孩子改 */
    val TRASH_KEEP_DAYS = 7

    /** 删除时「只删视频、保留转写文字」（§11.4 E2），默认关 */
    fun keepTextOnDelete(ctx: Context): Boolean = sp(ctx).getBoolean("keepTextOnDelete", false)
    fun setKeepTextOnDelete(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("keepTextOnDelete", v).apply()

    // ================= 分级节奏（§8.6） =================

    /**
     * 级别覆盖：0 = 自动（按第 N 次会话递进）；1..5 = 手动锁定到该级。
     * 给家长留一个「孩子已经能做到，别再等次数」的出口，也方便真机验证各级判定。
     */
    fun levelOverride(ctx: Context): Int = sp(ctx).getInt("levelOverride", 0)
    fun setLevelOverride(ctx: Context, v: Int) = sp(ctx).edit().putInt("levelOverride", v.coerceIn(0, 5)).apply()

    /** L2 模糊词总开关（D3：默认关闭，第 9 次会话起才自动开） */
    fun muzzyEnabled(ctx: Context): Boolean = sp(ctx).getBoolean("muzzyEnabled", true)
    fun setMuzzyEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("muzzyEnabled", v).apply()

    /**
     * 取景水平偏移：**按屏幕朝向分别存**（Surface.ROTATION_0/90/180/270）。
     * -1 = 露出画面最右一段，0 = 正中，+1 = 露出最左一段。
     *
     * ⚠️ 为什么要分朝向：本 app 是 sensorLandscape（允许横屏正反两个方向）。
     * 手机掉个头，镜头相对孩子的左右就翻过来了，同一个偏移量在另一头一定是错的。
     * 这里**不猜映射关系**（是取反还是别的，取决于镜像 + 旋转怎么叠，各机型还不统一），
     * 而是一头一套：自动标定（画面里肤色质心）写这里；用户手拖过就以手拖的为准，不再自动改。
     */
    fun displayRotation(ctx: Context): Int {
        val r = if (android.os.Build.VERSION.SDK_INT >= 30) {
            ctx.display?.rotation
        } else {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
        }
        return r ?: 0
    }

    private fun biasKey(rot: Int) = "cropBias_r$rot"
    private fun manualKey(rot: Int) = "cropBiasManual_r$rot"

    fun cropBias(ctx: Context, rot: Int): Float = sp(ctx).getFloat(biasKey(rot), 0f)
    fun setCropBias(ctx: Context, rot: Int, v: Float, manual: Boolean = false) =
        sp(ctx).edit().putFloat(biasKey(rot), v.coerceIn(-1f, 1f))
            .putBoolean(manualKey(rot), manual).apply()
    /** 用户是否手动拖过取景（拖过就不再自动标定） */
    fun cropBiasManual(ctx: Context, rot: Int): Boolean = sp(ctx).getBoolean(manualKey(rot), false)
    /** 这个朝向标定过吗 —— 没标定过必须重新自动标定，绝不能拿另一个朝向的值顶上 */
    fun hasCropBias(ctx: Context, rot: Int): Boolean = sp(ctx).contains(biasKey(rot))
}
