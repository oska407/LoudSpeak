package com.oska.loudspeak.core

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 界面小工具。
 *
 * hideStatusBar：设计约定「无顶部状态条」（§6.1 无状态条），横屏 app 顶部那条占了
 * 约 47dp（本机实测 94px / 720），四行布局全指着这点高度。只在主界面藏会不一致，
 * 所有 Activity 的 onCreate 里统一调一次。
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
}
