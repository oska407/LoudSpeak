package com.oska.loudspeak.core

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * 红晕提醒（§7 / §R9）：像游戏里「缺血」那样，只在屏幕四周边缘泛起一阵呼吸般的红晕，
 * 中心完全干净 —— 不影响看画面，也不拦截任何点击（画在 dispatchDraw 里，全屏盖层不挂监听）。
 *
 * 触发即「充血」：alpha 0→1→0 为一个呼吸，连做 3 次（约 3 秒）后自动消退；
 * 点屏幕任意处可立即消退（dispatchTouchEvent 里顺带调用 clearNow）。
 */
class GlowOverlay(private val v: View) {
    private var alpha = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint()

    init { v.setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

    /** 触发一次缺血式呼吸（连 3 次，约 3 秒） */
    fun fire() {
        animator?.cancel()
        // 0→1→0 一个来回 = 一次「充血/消退」；repeat 2 次 = 共 3 次呼吸
        animator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
            duration = 1100L
            repeatCount = 2
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { setAlpha(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { alpha = 0f; v.invalidate() }
            })
            start()
        }
    }

    /** 点击屏幕任意处立即消退（§7.3） */
    fun clearNow() {
        animator?.cancel()
        alpha = 0f
        v.invalidate()
    }

    @Suppress("unused") // 由 ValueAnimator 反射调用
    fun setAlpha(a: Float) { alpha = a; v.invalidate() }

    fun onDraw(c: Canvas) {
        if (alpha <= 0f) return
        val w = v.width.toFloat(); val h = v.height.toFloat()
        val band = (w * 0.07f).coerceAtLeast(46f)   // 缺血带略加宽，更有「边缘渗血」感
        // 边缘红（缺血红）透明渐隐；alpha 由呼吸动画整体控制强弱
        val color = (0xCCFF2E26 and 0x00FFFFFF) or ((alpha * 0.8f * 255).toInt() shl 24)
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
