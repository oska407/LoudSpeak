package com.oska.loudspeak.core

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * 红晕提醒（§7）：只在屏幕四周边缘（各一条向内渐隐的红色渐变带），中心完全干净；
 * 淡入 200ms → 保持 600ms → 淡出 400ms，共 1.2s；不拦截触控。
 */
class GlowOverlay(private val v: View) {
    private var alpha = 0f
    private var animator: AnimatorSet? = null
    private val paint = Paint()

    init { v.setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    fun fire() {
        animator?.cancel()
        val fadeIn = ObjectAnimator.ofFloat(this, "alpha", 0f, 1f).setDuration(200)
        fadeIn.interpolator = LinearInterpolator()
        val hold = ObjectAnimator.ofFloat(this, "alpha", 1f, 1f).setDuration(600)
        val fadeOut = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).setDuration(400)
        animator = AnimatorSet().apply {
            playSequentially(fadeIn, hold, fadeOut)
            start()
        }
    }

    /** 点击屏幕任意处立即消退（§7.3） */
    fun clearNow() {
        animator?.cancel()
        alpha = 0f
        v.invalidate()
    }

    @Suppress("unused") // 由 ObjectAnimator 反射调用
    fun setAlpha(a: Float) { alpha = a; v.invalidate() }
    @Suppress("unused")
    fun getAlpha() = alpha

    fun onDraw(c: Canvas) {
        if (alpha <= 0f) return
        val w = v.width.toFloat(); val h = v.height.toFloat()
        val band = (w * 0.06f).coerceAtLeast(40f)
        val color = 0x66FF3B30
        val transparent = 0x00000000
        paint.shader = LinearGradient(0f, 0f, band, 0f, color, transparent, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, band, h, paint)
        paint.shader = LinearGradient(w, 0f, w - band, 0f, color, transparent, Shader.TileMode.CLAMP)
        c.drawRect(w - band, 0f, w, h, paint)
        paint.shader = LinearGradient(0f, 0f, 0f, band, color, transparent, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, band, paint)
        paint.shader = LinearGradient(0f, h, 0f, h - band, color, transparent, Shader.TileMode.CLAMP)
        c.drawRect(0f, h - band, w, h, paint)
        paint.shader = null
    }
}
