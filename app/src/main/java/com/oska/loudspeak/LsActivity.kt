package com.oska.loudspeak

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.oska.loudspeak.core.Skin

/**
 * 所有界面的基类（R11）。
 *
 * 为什么要一个基类：换皮肤/换深色浅色要「所有页面一起换」，包括代码里 new 出来的控件。
 * 与其在 10 个 Activity 里各写一遍，不如把重着色挂在这里的 setContentView 上。
 *
 * 顺序很重要：`onCreate` 里先把窗口底色刷成皮肤的 bg，再 setContentView，
 * 否则浅色皮肤下会先闪一下系统默认的黑底；setContentView 之后再统一重着色 + 处理系统栏。
 */
open class LsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.setBackgroundDrawable(ColorDrawable(Skin.palette(this).bg))
        } catch (_: Exception) {}
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        Skin.applyTo(this, findViewById(android.R.id.content))
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        Skin.applyTo(this, view)
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        Skin.applyTo(this, view)
    }
}
