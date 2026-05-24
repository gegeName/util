package com.chat.spanutil.span

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import kotlin.math.min
import androidx.core.graphics.withTranslation

/**
 * 给任意 Drawable 套一层圆形 / 圆角 mask 的包装。
 *
 * 与 Glide 的 `circleCrop()` 区别:Glide 那个是 `BitmapTransformation`,
 * 会把 GifDrawable / WebpDrawable 强制解码成静态 Bitmap,导致动图变成第一帧。
 * 这里改用绘制阶段的 mask:
 *
 * - 内部 [inner] 仍然是 Animatable / GifDrawable,逐帧能正常播放。
 * - [draw] 时把 inner 画到内部 bitmap,再用 BitmapShader 圆形/圆角输出,抗锯齿正确。
 * - 实现 [Animatable]:`start/stop/isRunning` 代理给 inner,SpanBuilder 的动画
 *   生命周期管理(attach 启动 / detach 停止)能直接作用到本对象。
 * - 实现 [Drawable.Callback] 把 inner 的 `invalidateSelf` 转发为 `invalidateSelf()`,
 *   宿主 TextView 通过 SpanBuilder.wireAnimatable 挂在外层的 callback 就能逐帧刷新。
 *
 * @param inner 被包装的真实 Drawable,可能是 GifDrawable、BitmapDrawable 等。
 * @param cornerRadius 圆角半径 px;传 -1 表示完全圆形(取 min(w,h)/2)。
 */
class RoundMaskDrawable(
    private val inner: Drawable,
    private val cornerRadius: Float = -1f,
) : Drawable(), Animatable, Drawable.Callback {

    private var sourceBitmap: Bitmap? = null
    private var sourceCanvas: Canvas? = null
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private val rectF = RectF()

    init {
        inner.callback = this
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return

        var bmp = sourceBitmap
        var srcCanvas = sourceCanvas
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = createBitmap(w, h)
            sourceBitmap = bmp
            srcCanvas = Canvas(bmp)
            sourceCanvas = srcCanvas
            sourcePaint.shader =
                BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }

        srcCanvas!!.drawColor(0, PorterDuff.Mode.CLEAR)
        inner.setBounds(0, 0, w, h)
        inner.draw(srcCanvas)

        canvas.withTranslation(b.left.toFloat(), b.top.toFloat()) {
            rectF.set(0f, 0f, w.toFloat(), h.toFloat())
            if (cornerRadius < 0f) {
                val r = min(w, h) / 2f
                canvas.drawCircle(w / 2f, h / 2f, r, sourcePaint)
            } else {
                canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, sourcePaint)
            }
        }
    }

    /**
     * 主动释放内部 Bitmap。SpanBuilder 在 RecyclerView 复用 / TextView detach 时调用,
     * 避免 native 内存堆积。释放后下次 [draw] 会按需重建。
     */
    fun release() {
        sourceBitmap?.recycle()
        sourceBitmap = null
        sourceCanvas = null
        sourcePaint.shader = null
    }

    override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
        super.setBounds(left, top, right, bottom)
        inner.setBounds(left, top, right, bottom)
    }

    override fun setAlpha(alpha: Int) {
        inner.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        inner.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = inner.intrinsicWidth

    override fun getIntrinsicHeight(): Int = inner.intrinsicHeight

    override fun start() {
        (inner as? Animatable)?.start()
    }

    override fun stop() {
        (inner as? Animatable)?.stop()
    }

    override fun isRunning(): Boolean =
        (inner as? Animatable)?.isRunning == true

    override fun invalidateDrawable(who: Drawable) {
        invalidateSelf()
    }

    override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
        callback?.scheduleDrawable(this, what, time)
    }

    override fun unscheduleDrawable(who: Drawable, what: Runnable) {
        callback?.unscheduleDrawable(this, what)
    }
}
