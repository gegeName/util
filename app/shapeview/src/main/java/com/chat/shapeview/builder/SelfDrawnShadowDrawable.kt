package com.chat.shapeview.builder

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * 自绘彩色阴影 Drawable，兼容所有 API。
 */
class SelfDrawnShadowDrawable(
    private val radius: Float,
    private val shadowSize: Float,
    private val shadowColor: Int,
    private val contentInset: Float = shadowSize,
    private val offsetX: Float = 0f,
    private val offsetY: Float = 0f,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val a = (shadowColor ushr 24) and 0xFF
        color = if (a == 255) (shadowColor and 0x00FFFFFF) or (0xFE shl 24) else shadowColor
        style = Paint.Style.STROKE
        strokeWidth = shadowSize / 4f
        maskFilter = BlurMaskFilter(shadowSize.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }

    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val l = b.left + contentInset + offsetX.coerceAtLeast(0f)
        val t = b.top + contentInset + offsetY.coerceAtLeast(0f)
        val r = b.right - contentInset + offsetX.coerceAtMost(0f)
        val bot = b.bottom - contentInset + offsetY.coerceAtMost(0f)
        if (r <= l || bot <= t) return
        rectF.set(l, t, r, bot)
        canvas.drawRoundRect(rectF, radius, radius, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

