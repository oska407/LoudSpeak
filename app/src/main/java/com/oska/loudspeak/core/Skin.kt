package com.oska.loudspeak.core

import com.oska.loudspeak.R
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * 皮肤 / 深色浅色模式（设置页「外观」）。
 *
 * 为什么不用「8 套主题资源」：界面里大量控件是代码里 new 出来的，
 * 主题属性只能在 XML 里解析，代码里 new 的控件还得自己上色，两套机制并存最容易出错。
 * 这里改成**运行时统一重着色**：
 *  - 布局里写的仍是「基准深色」的颜色（bg/card/label/white…）；
 *  - `recolor()` 递归走一遍视图树，把「基准色」映射成当前调色板里的同角色颜色；
 *  - 卡片/按钮那种 GradientDrawable 也照着映射（保留原圆角，换成皮肤的圆角）。
 * 好处：一个入口、所有页面（含代码里 new 的）一起变，且能同时调字体与圆角。
 *
 * 四套皮肤（各有故意做得不一样的「性格」）：
 *  - 经典 Classic：深灰蓝 + 绿，无衬线，24 段条形分贝器 —— 原来那套。
 *  - 纸张 Paper：米白 + 墨黑 + **衬线字**，圆角很方，分贝器只留大数字与细刻度。
 *  - 终端 Terminal：近黑 + 磷光绿 + **等宽字**，40 段细 LED，扫描线更重。
 *  - 霓虹 Neon：紫黑 + 青，圆角最圆，分贝器带发光渐变。
 */
object Skin {

    // ---- 色彩模式 ----
    const val MODE_DARK = 0
    const val MODE_LIGHT = 1
    const val MODE_SYSTEM = 2

    // ---- 皮肤 ----
    const val SKIN_CLASSIC = 0
    const val SKIN_PAPER = 1
    const val SKIN_TERMINAL = 2
    const val SKIN_NEON = 3

    // ---- 分贝器形态 ----
    const val DB_BARS = 0      // 24 段 + 扫描线
    const val DB_MINIMAL = 1   // 只有大数字 + 细刻度（纸感）
    const val DB_LED = 2       // 40 段细 LED（终端）
    const val DB_GLOW = 3      // 条 + 发光渐变（霓虹）

    /** 一套调色板。字段够用就好，不要到处 +1。 */
    data class Palette(
        val bg: Int, val card: Int, val soft: Int, val stroke: Int,
        val text: Int, val text2: Int, val text3: Int,
        val accent: Int, val onAccent: Int,
        val ok: Int, val warn: Int, val danger: Int,
        val lcdBg: Int, val lcdOn: Int, val lcdOff: Int,
        val light: Boolean,
        val radiusDp: Int,
        val font: String,
        val dbStyle: Int
    )

    // 基准色（布局里写死的那些）—— 映射表的 key
    private const val C_BG_A = 0xFF101418.toInt()
    private const val C_BG_B = 0xFF0E1216.toInt()
    private const val C_CARD_A = 0xFF1C242B.toInt()
    private const val C_CARD_B = 0xFF17202A.toInt()
    private const val C_SOFT = 0xFF1F2A35.toInt()
    private const val C_WHITE = 0xFFFFFFFF.toInt()
    private const val C_TEXT_A = 0xFFE9EFF4.toInt()
    private const val C_LABEL = 0xFF9AA7B0.toInt()
    private const val C_TEXT2 = 0xFF8B99A5.toInt()
    private const val C_TEXT3_A = 0xFF6E7B84.toInt()
    private const val C_TEXT3_B = 0xFF69757F.toInt()
    private const val C_ACCENT_A = 0xFF4FA3FF.toInt()
    private const val C_ACCENT_B = 0xFF4ADE80.toInt()
    private const val C_DIV_A = 0xFF22303A.toInt()
    private const val C_DIV_B = 0xFF26323D.toInt()
    private const val C_RED_A = 0xFFFF5252.toInt()
    private const val C_RED_B = 0xFFFF5A52.toInt()
    private const val C_OK = 0xFF46C08A.toInt()

    /** 最近一次应用的调色板：自绘控件（分贝器/波形）直接取它，免得层层传参 */
    @Volatile
    var current: Palette = classic(false)

    private fun classic(light: Boolean) = if (!light) Palette(
        bg = C_BG_A, card = C_CARD_A, soft = C_SOFT, stroke = C_DIV_A,
        text = C_TEXT_A, text2 = C_LABEL, text3 = C_TEXT3_A,
        accent = C_ACCENT_B, onAccent = 0xFF08170B.toInt(),
        ok = C_OK, warn = 0xFFFFB74D.toInt(), danger = C_RED_B,
        lcdBg = 0xFF0E1A12.toInt(), lcdOn = 0xFF4ADE80.toInt(), lcdOff = 0xFF1E2C24.toInt(),
        light = false, radiusDp = 14, font = "sans-serif", dbStyle = DB_BARS
    ) else Palette(
        bg = 0xFFF5F7F8.toInt(), card = 0xFFFFFFFF.toInt(), soft = 0xFFEDF1F4.toInt(),
        stroke = 0xFFDCE3E8.toInt(),
        text = 0xFF18222A.toInt(), text2 = 0xFF5C6A75.toInt(), text3 = 0xFF7C8993.toInt(),
        accent = 0xFF16A34A.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        ok = 0xFF16A34A.toInt(), warn = 0xFFB45309.toInt(), danger = 0xFFDC2626.toInt(),
        lcdBg = 0xFF0E1A12.toInt(), lcdOn = 0xFF4ADE80.toInt(), lcdOff = 0xFF1E2C24.toInt(),
        light = true, radiusDp = 14, font = "sans-serif", dbStyle = DB_BARS
    )

    private fun paper(light: Boolean) = if (!light) Palette(
        bg = 0xFF191612.toInt(), card = 0xFF221E18.toInt(), soft = 0xFF2C2720.toInt(),
        stroke = 0xFF3A332A.toInt(),
        text = 0xFFF3EADA.toInt(), text2 = 0xFFB5A88F.toInt(), text3 = 0xFF7D7360.toInt(),
        accent = 0xFFE8834A.toInt(), onAccent = 0xFF1A0F06.toInt(),
        ok = 0xFF9BBF6A.toInt(), warn = 0xFFD9A441.toInt(), danger = 0xFFD9603B.toInt(),
        lcdBg = 0xFF211B14.toInt(), lcdOn = 0xFFE8C07A.toInt(), lcdOff = 0xFF3A3226.toInt(),
        light = false, radiusDp = 6, font = "serif", dbStyle = DB_MINIMAL
    ) else Palette(
        bg = 0xFFF3ECDF.toInt(), card = 0xFFFFFBF2.toInt(), soft = 0xFFEDE3D2.toInt(),
        stroke = 0xFFDCCFB6.toInt(),
        text = 0xFF2A2520.toInt(), text2 = 0xFF6B6152.toInt(), text3 = 0xFF867B68.toInt(),
        accent = 0xFFB45309.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        ok = 0xFF4D7C0F.toInt(), warn = 0xFFB45309.toInt(), danger = 0xFFB91C1C.toInt(),
        lcdBg = 0xFF2A241C.toInt(), lcdOn = 0xFFE8C07A.toInt(), lcdOff = 0xFF3A3226.toInt(),
        light = true, radiusDp = 6, font = "serif", dbStyle = DB_MINIMAL
    )

    private fun terminal(light: Boolean) = if (!light) Palette(
        bg = 0xFF050807.toInt(), card = 0xFF0B1210.toInt(), soft = 0xFF101B17.toInt(),
        stroke = 0xFF1C2E26.toInt(),
        text = 0xFFBDF5CE.toInt(), text2 = 0xFF5E8B6E.toInt(), text3 = 0xFF547E6B.toInt(),
        accent = 0xFF35D07F.toInt(), onAccent = 0xFF03110A.toInt(),
        ok = 0xFF35D07F.toInt(), warn = 0xFFD7C85B.toInt(), danger = 0xFFFF6B5E.toInt(),
        lcdBg = 0xFF03110A.toInt(), lcdOn = 0xFF35D07F.toInt(), lcdOff = 0xFF0F2419.toInt(),
        light = false, radiusDp = 4, font = "monospace", dbStyle = DB_LED
    ) else Palette(
        bg = 0xFFF0F5F1.toInt(), card = 0xFFFEFFFE.toInt(), soft = 0xFFE4EDE7.toInt(),
        stroke = 0xFFCBDCD2.toInt(),
        text = 0xFF0F241A.toInt(), text2 = 0xFF4C6B58.toInt(), text3 = 0xFF5F7C6A.toInt(),
        accent = 0xFF12824C.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        ok = 0xFF12824C.toInt(), warn = 0xFF8A6D1F.toInt(), danger = 0xFFB3261E.toInt(),
        lcdBg = 0xFF03110A.toInt(), lcdOn = 0xFF35D07F.toInt(), lcdOff = 0xFF0F2419.toInt(),
        light = true, radiusDp = 4, font = "monospace", dbStyle = DB_LED
    )

    private fun neon(light: Boolean) = if (!light) Palette(
        bg = 0xFF0B0A14.toInt(), card = 0xFF17142A.toInt(), soft = 0xFF221D3A.toInt(),
        stroke = 0xFF342D55.toInt(),
        text = 0xFFEDEAFF.toInt(), text2 = 0xFF9C93C4.toInt(), text3 = 0xFF6E6690.toInt(),
        accent = 0xFF22D3EE.toInt(), onAccent = 0xFF04121A.toInt(),
        ok = 0xFF34D399.toInt(), warn = 0xFFFBBF24.toInt(), danger = 0xFFFB5BA8.toInt(),
        lcdBg = 0xFF120A1F.toInt(), lcdOn = 0xFF22D3EE.toInt(), lcdOff = 0xFF241A3C.toInt(),
        light = false, radiusDp = 20, font = "sans-serif-condensed", dbStyle = DB_GLOW
    ) else Palette(
        bg = 0xFFF5F2FF.toInt(), card = 0xFFFFFFFF.toInt(), soft = 0xFFEDE7FF.toInt(),
        stroke = 0xFFDDD4F7.toInt(),
        text = 0xFF1B1730.toInt(), text2 = 0xFF6B6392.toInt(), text3 = 0xFF766D9C.toInt(),
        accent = 0xFF7C3AED.toInt(), onAccent = 0xFFFFFFFF.toInt(),
        ok = 0xFF059669.toInt(), warn = 0xFFB45309.toInt(), danger = 0xFFDB2777.toInt(),
        lcdBg = 0xFF120A1F.toInt(), lcdOn = 0xFF22D3EE.toInt(), lcdOff = 0xFF241A3C.toInt(),
        light = true, radiusDp = 20, font = "sans-serif-condensed", dbStyle = DB_GLOW
    )

    fun isDark(ctx: Context): Boolean = when (Prefs.colorMode(ctx)) {
        MODE_DARK -> true
        MODE_LIGHT -> false
        else -> (ctx.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(ctx: Context): Palette {
        val light = !isDark(ctx)
        return when (Prefs.skin(ctx)) {
            SKIN_PAPER -> paper(light)
            SKIN_TERMINAL -> terminal(light)
            SKIN_NEON -> neon(light)
            else -> classic(light)
        }
    }

    fun typeface(ctx: Context): Typeface = try {
        Typeface.create(palette(ctx).font, Typeface.NORMAL)
    } catch (_: Exception) { Typeface.DEFAULT }

    /** 皮肤名字（设置页展示） */
    fun skinName(s: Int): String = when (s) {
        SKIN_PAPER -> "纸张"
        SKIN_TERMINAL -> "终端"
        SKIN_NEON -> "霓虹"
        else -> "经典"
    }

    fun skinDesc(s: Int): String = when (s) {
        SKIN_PAPER -> "米白纸感 · 衬线字 · 方角 · 极简分贝器"
        SKIN_TERMINAL -> "磷光绿 · 等宽字 · 40 段 LED"
        SKIN_NEON -> "紫黑 + 青 · 大圆角 · 发光分贝器"
        else -> "深灰蓝 + 绿 · 无衬线 · 24 段条形"
    }

    // ================= 应用 =================

    /** 整个 Activity 上色 + 系统栏 [在 setContentView 之后调用] */
    fun applyTo(activity: Activity, root: View?) {
        val p = palette(activity)
        current = p
        try {
            activity.window.setBackgroundDrawable(ColorDrawable(p.bg))
        } catch (_: Exception) {}
        if (root != null) recolor(root, p)
        Ui.applySystemBars(activity, p, Prefs.navHidden(activity))
    }

    /**
     * 递归重着色。规则只有一条：**认色不认控件**
     * —— 布局里写的基准色 → 调色板里同角色色；不在映射表里的（比如霓虹的强调色、
     * 标注的语义红）原样不动。
     *
     * 2026-10-24 修「换皮肤 / 换深浅后，背景不再跟着变」：
     * 「认色」的前提是 View 上还是**基准色**。但第一套皮肤应用后，颜色已经被覆盖成
     * 纸张米白之类，第二套皮肤的 mapColor 认不出 → 整个界面卡在上一套。
     * 现在每个 View 第一次过这里时，把**原始背景 / 原始字色**存进 tag（ids.xml 里
     * 的 skinOrigBg / skinOrigText），之后每次都从原始值重新映射 —— 换几套都能往返。
     */
    fun recolor(root: View, p: Palette = current) {
        // 0) 基准存档（只在首次见到这个 View 时做一次）
        if (root.getTag(R.id.skinOrigBg) == null) {
            root.background?.constantState?.let { root.setTag(R.id.skinOrigBg, it) }
        }
        val origText = (root.getTag(R.id.skinOrigText) as? Int)
            ?: (root as? TextView)?.currentTextColor?.also { root.setTag(R.id.skinOrigText, it) }

        // 1) 背景：从**原始**背景重映射（不是当前背景！），再决定要不要给 tint 类控件重上色
        var gradientBg = false
        (root.getTag(R.id.skinOrigBg) as? Drawable.ConstantState)?.let { cs ->
            val d = try { cs.newDrawable().mutate() } catch (_: Exception) { null }
            when (d) {
                is ColorDrawable -> mapColor(d.color, p)?.let { root.setBackgroundColor(it) }
                is GradientDrawable -> {
                    gradientBg = true
                    val c = try { d.color?.defaultColor } catch (_: Exception) { null }
                    val mapped = c?.let { mapColor(it, p) }
                    if (mapped != null) {
                        d.setColor(mapped)
                        // 卡片类给一条淡描边，浅色下才分得清边界
                        if (mapped == p.card || mapped == p.soft) {
                            d.setStroke(Math.max(1, p.radiusDp / 14), p.stroke)
                        }
                    }
                    root.background = d
                }
                else -> {}   // 选择器 / 涟漪等不归皮肤管
            }
        }

        // 2) 文字色（Button 单独处理，它的字色是 selector 解析出来的，认不出基准色）
        if (root is TextView && root !is Button) {
            origText?.let { mapColor(it, p) }?.let { root.setTextColor(it) }
        }

        // 3) 字体（纸张=衬线 / 终端=等宽）。想保住原字体的控件打 tag="keepFont"
        if (root is TextView && root.tag != "keepFont") {
            root.typeface = try { Typeface.create(p.font, root.typeface?.style ?: 0) }
            catch (_: Exception) { root.typeface }
        }

        // 4) 着色类控件
        when (root) {
            is Switch -> {
                try {
                    root.thumbTintList = ColorStateList.valueOf(p.accent)
                    root.trackTintList = ColorStateList.valueOf(
                        if (p.light) 0x553A4A55 else 0x66FFFFFF)
                } catch (_: Exception) {}
            }
            is CompoundButton -> {
                // 单选/复选是「透明底压在卡片上」，没有强调色底。
                // 此前误走下面的 Button 分支，字被染成 onAccent（浅色下=白）→ 白底白字看不见（2026-10-23 修）。
                val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
                root.setTextColor(ColorStateList(states, intArrayOf(p.text3, p.text)))
                root.buttonTintList = ColorStateList.valueOf(p.accent)
            }
            is Button -> {
                val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
                if (gradientBg) {
                    // 自带背景的按钮（设置页的浅色条 SetBtn 等）：底色是 card/soft 不是强调色，
                    // 文字按它**原始**的正文色映射（不能看 currentTextColor —— 那可能已被上一套覆盖），
                    // 禁用态灰掉 —— 不能套 onAccent
                    val base = origText?.let { mapColor(it, p) } ?: p.text
                    root.setTextColor(ColorStateList(states, intArrayOf(p.text3, base)))
                } else {
                    val onBg = intArrayOf(p.soft, p.accent)
                    val onFg = intArrayOf(if (p.light) p.text3 else 0xFF8FA0AC.toInt(), p.onAccent)
                    root.backgroundTintList = ColorStateList(states, onBg)
                    root.setTextColor(ColorStateList(states, onFg))
                }
            }
            is SeekBar -> {
                root.progressTintList = ColorStateList.valueOf(p.accent)
                root.thumbTintList = ColorStateList.valueOf(p.accent)
                root.progressBackgroundTintList = ColorStateList.valueOf(p.stroke)
            }
        }

        if (root is ViewGroup) {
            for (i in 0 until root.childCount) recolor(root.getChildAt(i), p)
        }
    }

    /** 基准色 → 当前调色板。返回 null 表示「这个颜色不归皮肤管，别动」。 */
    private fun mapColor(c: Int, p: Palette): Int? = when (c) {
        C_BG_A, C_BG_B -> p.bg
        C_CARD_A, C_CARD_B -> p.card
        C_SOFT -> p.soft
        C_WHITE, C_TEXT_A -> p.text
        C_LABEL, C_TEXT2 -> p.text2
        C_TEXT3_A, C_TEXT3_B, 0xFF37474F.toInt() -> p.text3
        C_ACCENT_A, C_ACCENT_B -> p.accent
        C_DIV_A, C_DIV_B -> p.stroke
        C_RED_A, C_RED_B -> p.danger
        C_OK -> p.ok
        else -> null
    }
}
