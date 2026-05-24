package com.chat.spanutil.span

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.annotation.ColorInt
import androidx.annotation.Px

/**
 * 在原始 Drawable 外围绘制边框的包装 Drawable。
 *
 * - bounds = 原图 bounds，边框向内绘制，不侵占外部布局空间。
 * - [Paint] / [RectF] 放在 companion object（静态）：draw() 在主线程单线程执行，
 *   多个实例共享同一组对象，避免 RecyclerView bind 时反复 new 导致内存抖动。
 * - 渐变 [LinearGradient] 缓存在实例上（不同图片尺寸不同，必须独立缓存）。
 */
class BorderedImageDrawable(
    private val inner: Drawable,
    @field:Px val borderWidth: Float,
    @field:ColorInt val borderColor: Int,
    @field:Px val cornerRadius: Float = 0f,
    val gradientColors: IntArray? = null,
    val gradientVertical: Boolean = false,
) : Drawable() {

    private var cachedShader: LinearGradient? = null
    private var cachedW = 0
    private var cachedH = 0

    override fun draw(canvas: Canvas) {
        val b = bounds
        inner.bounds = b
        inner.draw(canvas)

        val half = borderWidth / 2f
        sBorderRectF.set(b.left + half, b.top + half, b.right - half, b.bottom - half)
        sBorderPaint.strokeWidth = borderWidth

        if (gradientColors != null) {
            val w = b.width();
            val h = b.height()
            if (cachedShader == null || cachedW != w || cachedH != h) {
                cachedShader = if (gradientVertical) {
                    LinearGradient(
                        0f,
                        0f,
                        0f,
                        h.toFloat(),
                        gradientColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                } else {
                    LinearGradient(
                        0f,
                        0f,
                        w.toFloat(),
                        0f,
                        gradientColors,
                        null,
                        Shader.TileMode.CLAMP
                    )
                }
                cachedW = w; cachedH = h
            }
            sBorderPaint.shader = cachedShader
        } else {
            sBorderPaint.shader = null
            sBorderPaint.color = borderColor
        }

        if (cornerRadius > 0f) {
            canvas.drawRoundRect(sBorderRectF, cornerRadius, cornerRadius, sBorderPaint)
        } else {
            canvas.drawRect(sBorderRectF, sBorderPaint)
        }
    }

    override fun getIntrinsicWidth(): Int = inner.intrinsicWidth
    override fun getIntrinsicHeight(): Int = inner.intrinsicHeight
    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        inner.colorFilter = cf
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun onBoundsChange(bounds: Rect) {
        cachedShader = null
    }

    companion object {
        private val sBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }
        private val sBorderRectF = RectF()
    }
}
