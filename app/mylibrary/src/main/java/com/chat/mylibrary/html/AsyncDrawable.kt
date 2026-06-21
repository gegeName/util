package com.chat.mylibrary.html

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * HTML 图片 / 视频首帧的占位包装 Drawable。
 *
 * 解析阶段先以占位形式插入文本（[wrapped] 为空，bounds 为 1x1）；
 * 媒体加载完成后由 HtmlTextView 设置 [wrapped] 与最终 bounds 并重排文本。
 *
 * 当 [isVideo] 为 true 时，会在内容中心叠加一个播放按钮：
 * 优先使用外部传入的 [playButton]，否则用 Canvas 绘制默认按钮（半透明圆 + 白色三角）。
 *
 * @param url      原始媒体地址（图片或视频）
 * @param isVideo  是否为视频（决定是否叠加播放按钮、点击回调走向）
 */
class AsyncDrawable(
    val url: String,
    val isVideo: Boolean,
    val isFormula: Boolean = false
) : Drawable() {

    /** 真实内容 drawable，加载完成后由外部赋值 */
    var wrapped: Drawable? = null
        set(value) {
            field = value
            value?.bounds = bounds
            roundedBitmap = null
            invalidateSelf()
        }

    /** 外部可指定的播放按钮图标；为空时使用默认绘制 */
    var playButton: Drawable? = null

    /** 是否为独占一行、撑满宽度的大图。由 HtmlTextView 计算后设置。 */
    var isBlock: Boolean = false

    /** 圆角半径（px），> 0 时把内容裁剪为圆角。 */
    var cornerRadius: Float = 0f

    private var roundedBitmap: Bitmap? = null
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderRect = RectF()

    private val circlePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
            style = Paint.Style.FILL
        }
    }
    private val trianglePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
    }
    private val trianglePath = Path()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        wrapped?.bounds = bounds
        roundedBitmap = null
    }

    override fun draw(canvas: Canvas) {
        val content = wrapped ?: return
        val r = cornerRadius
        val w = bounds.width()
        val h = bounds.height()
        if (r > 0f && w > 0 && h > 0) {
            obtainRoundedShader(content, w, h)
            shaderRect.set(
                bounds.left.toFloat(), bounds.top.toFloat(),
                bounds.right.toFloat(), bounds.bottom.toFloat()
            )
            canvas.drawRoundRect(shaderRect, r, r, bitmapPaint)
        } else {
            content.draw(canvas)
        }
        if (isVideo) {
            drawPlayButton(canvas)
        }
    }

    /** 把内容渲染进与 bounds 等大的 bitmap，并据此构建 BitmapShader（带尺寸缓存）。 */
    private fun obtainRoundedShader(content: Drawable, w: Int, h: Int) {
        val cached = roundedBitmap
        if (cached != null && cached.width == w && cached.height == h) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        content.setBounds(0, 0, w, h)
        content.draw(Canvas(bmp))
        roundedBitmap = bmp
        bitmapPaint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private fun drawPlayButton(canvas: Canvas) {
        val b = bounds
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()

        val external = playButton
        if (external != null) {
            val size = (min(b.width(), b.height()) * 0.3f).toInt().coerceAtLeast(1)
            val half = size / 2
            external.setBounds(
                (cx - half).toInt(), (cy - half).toInt(),
                (cx + half).toInt(), (cy + half).toInt()
            )
            external.draw(canvas)
            return
        }

        val radius = min(b.width(), b.height()) * 0.14f
        if (radius <= 0f) return
        canvas.drawCircle(cx, cy, radius, circlePaint)

        val side = radius * 1.1f
        trianglePath.reset()
        val left = cx - side * 0.35f
        trianglePath.moveTo(left, cy - side * 0.55f)
        trianglePath.lineTo(left, cy + side * 0.55f)
        trianglePath.lineTo(cx + side * 0.6f, cy)
        trianglePath.close()
        canvas.drawPath(trianglePath, trianglePaint)
    }

    override fun setAlpha(alpha: Int) {
        wrapped?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        wrapped?.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
