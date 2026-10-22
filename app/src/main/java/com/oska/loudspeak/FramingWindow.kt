package com.oska.loudspeak

import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 「取景窗口」：内层子 View 的宽度被撑到「容器高 × 16/9」，比容器宽很多，
 * 然后整体左右平移，等于在一个宽画幅里挑一段竖条显示。
 *
 * 为什么需要它：前置摄像头在手机上的位置各家不同（有的靠左、有的靠右），
 * 而孩子是照着**屏幕**坐的，不是照着**镜头**坐的 —— 结果人脸总是偏在画面一边。
 * Android 没有任何 API 能查「摄像头在屏幕哪一侧」，所以这里不猜硬件，
 * 而是看**画面里肤色（脸）的质心落在哪**，再把窗口平移过去，让脸回到中间。
 * 见 [FaceFramer]。
 */
class FramingWindow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** -1 = 露出画面最右一段；0 = 正中；+1 = 露出画面最左一段（画面内容整体右移） */
    var bias: Float = 0f
        set(value) {
            field = value.coerceIn(-1f, 1f)
            applyBias()
        }

    init {
        clipChildren = true
        clipToPadding = false
    }

    /** 子 View 应有的宽度：按 16:9 撑满容器高度 */
    fun childWidth(): Int = (height * 16f / 9f).toInt()

    /** 可平移的余量（像素）：越大越能往两边挪 */
    fun slack(): Int = (childWidth() - width).coerceAtLeast(0)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        applyBias()
    }

    private fun applyBias() {
        if (width == 0 || height == 0) return
        val tw = childWidth()
        val sl = (tw - width).coerceAtLeast(0)
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val lp = c.layoutParams as? LayoutParams ?: continue
            if (lp.width != tw) {
                lp.width = tw
                c.layoutParams = lp
            }
            c.translationX = if (sl == 0) 0f else -(sl / 2f) * (1f - bias)
        }
    }
}

/**
 * 极轻的「脸在哪」估计：按经典肤色规则统计像素，返回肤色质心的水平位置（0..1）。
 * 判不出来返回 null —— 宁可不动，也不要把画面甩到墙上去。
 */
object FaceFramer {

    fun skinCenterX(bmp: Bitmap): Float? {
        if (bmp.width <= 0 || bmp.height <= 0) return null
        val sw = 48
        val sh = (bmp.height.toFloat() / bmp.width * sw).toInt().coerceIn(8, 96)
        val small = try { Bitmap.createScaledBitmap(bmp, sw, sh, false) } catch (e: Exception) { return null }
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== bmp) small.recycle()

        var sumX = 0.0
        var n = 0
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            // 经典肤色规则（Kovac 简化版），对室内暖光/灰墙都还稳
            if (r > 95 && g > 40 && b > 20 && mx - mn > 15 && r - g > 15 && r > b) {
                sumX += (i % sw).toDouble()
                n++
            }
        }
        if (n < sw * sh / 40) return null     // 肤色像素不足 2.5%，不可信
        return (sumX / n / (sw - 1)).toFloat()
    }
}
