package com.simple.mylibrary.weiget.builder

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * API < 28 自绘彩色阴影 Drawable
 *
 * 通过 Paint.setShadowLayer 在透明背景上绘制一个圆角矩形的阴影，
 * 由上层 LayerDrawable 叠加真正的 shape 背景，从而实现 colored shadow
 * 在 API 21~27 上的兼容。
 *
 * 使用方实例化该 Drawable 时，必须给 View 设置 LAYER_TYPE_SOFTWARE，
 * 否则在硬件加速场景下 setShadowLayer 可能不生效。
 */
class SelfDrawnShadowDrawable(
    private val radius: Float,
    private val shadowSize: Float,
    private val shadowColor: Int,
    /**
     * 内容矩形距 drawable bounds 边缘的距离（即 shadowPadding）。
     * 阴影从内容矩形边缘向外扩散，到 bounds 边缘时自然衰减到透明，
     * 不再借助 InsetDrawable 裁切，从而消除阴影外侧的硬边。
     */
    private val contentInset: Float = shadowSize,
    private val offsetX: Float = 0f,
    private val offsetY: Float = 0f
) : Drawable() {

    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 255, 255, 255)
        style = Paint.Style.FILL
        setShadowLayer(shadowSize, offsetX, offsetY, shadowColor)
    }

    private val rectF = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        rectF.set(
            b.left + contentInset,
            b.top + contentInset,
            b.right - contentInset,
            b.bottom - contentInset
        )
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
