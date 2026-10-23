package com.oska.loudspeak.core

import android.app.Activity
import android.os.Build
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 界面小工具：系统栏（状态栏 + 导航栏）。
 *
 * 设计约定「无顶部状态条」（§6.1 无状态条），横屏 app 顶部那条占了约 47dp，
 * 四行布局全指着这点高度，所以状态栏一直隐藏；只在主界面藏会不一致，
 * 所有 Activity 统一由 Skin.applyTo → applySystemBars 调一次。
 *
 * 导航栏（R11 新增）：
 *  - 默认**同样隐藏**（沉浸式），需要时从边缘上滑临时唤出 —— 设置页可关掉这个行为；
 *  - 显示时**底色跟界面一致**（用当前皮肤的 bg），图标亮暗也跟着浅色/深色模式走，
 *    否则浅色皮肤上会出现「白底白箭头」看不见的情况。
 */
object Ui {
    fun hideStatusBar(a: Activity) {
        try {
            WindowCompat.getInsetsController(a.window, a.window.decorView)
                .hide(WindowInsetsCompat.Type.statusBars())
        } catch (_: Exception) {
            // 隐藏失败不影响任何业务，忽略
        }
    }

    /**
     * 统一处理状态栏 / 导航栏。
     * @param hideNav true = 导航栏也隐藏（沉浸式，边缘上滑可临时唤出）
     */
    fun applySystemBars(a: Activity, p: Skin.Palette, hideNav: Boolean) {
        try {
            val w = a.window
            // 让内容铺到系统栏区域，才能做到「导航栏颜色与界面一致」
            WindowCompat.setDecorFitsSystemWindows(w, false)
            // 底色跟界面一致（不加的话系统会画一条黑/白带，浅色皮肤上特别明显）
            @Suppress("DEPRECATION")
            run {
                w.navigationBarColor = p.bg
                w.statusBarColor = p.bg
            }
            val c = WindowCompat.getInsetsController(w, w.decorView)
            c.isAppearanceLightStatusBars = p.light
            c.isAppearanceLightNavigationBars = p.light
            c.hide(WindowInsetsCompat.Type.statusBars())
            if (hideNav) {
                c.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                c.show(WindowInsetsCompat.Type.navigationBars())
            }
            if (Build.VERSION.SDK_INT >= 29) {
                w.isNavigationBarContrastEnforced = false
                w.isStatusBarContrastEnforced = false
            }
        } catch (_: Exception) {
            // 系统栏折腾失败不影响任何业务
        }
    }
}
