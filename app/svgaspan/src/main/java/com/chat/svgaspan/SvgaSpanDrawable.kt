package com.chat.svgaspan

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.createBitmap
import com.chat.spanutil.span.HostAware
import com.chat.spanutil.span.Releasable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAVideoEntity
import java.lang.ref.WeakReference

/**
 * 把 SVGA Entity 渲染成 ImageSpan 可用的 Drawable。
 *
 * @param entity   已解析好的 SVGA Entity（建议来自 [SvgaCache]，跨 TextView 共享）
 * @param widthPx  显示宽度（px）
 * @param heightPx 显示高度（px）
 * @param host     用于构建离屏 SVGAImageView 的 Context；内部会优先取 applicationContext
 */
class SvgaSpanDrawable(
    private val entity: SVGAVideoEntity,
    private val widthPx: Int,
    private val heightPx: Int,
    host: Context,
) : Drawable(), Animatable, Releasable, HostAware {

    private val totalFrames: Int = entity.frames
    private val fps: Int = entity.FPS.coerceAtLeast(1)
    private val frameIntervalMs: Long = (1000L / fps).coerceAtLeast(8L)

    private var imageView: SVGAImageView? = SVGAImageView(host.applicationContextOrSelf()).apply {
        loops = 0
        scaleType = ImageView.ScaleType.FIT_XY
        setVideoItem(entity)
    }
    @Volatile private var prepared = false

    private var currentFrame: Int = 0
    private var frameBitmap: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var lastFrameTimeMs: Long = 0L

    @Volatile private var running = false

    @Volatile private var disposed = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private var hostRef: WeakReference<TextView>? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || disposed) return
            val iv = imageView ?: run { stop(); return }
            val nowMs = frameTimeNanos / 1_000_000L
            if (nowMs - lastFrameTimeMs >= frameIntervalMs) {
                currentFrame = (currentFrame + 1) % totalFrames
                runCatching { iv.stepToFrame(currentFrame, false) }
                lastFrameTimeMs = nowMs
                val tv = hostRef?.get()
                if (tv == null) {
                    pause()
                    return
                }
                tv.invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setBounds(0, 0, widthPx, heightPx)
    }

    override fun draw(canvas: Canvas) {
        if (disposed) return
        val iv = imageView ?: return
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) return
        if (!prepared) return
        val bmp = frameBitmap ?: return
        val c = frameCanvas ?: return
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        iv.draw(c)
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), paint)
    }

    private fun prepareIfNeeded() {
        if (prepared || disposed) return
        val iv = imageView ?: return
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        iv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        iv.layout(0, 0, w, h)
        val bmp = createBitmap(w, h)
        frameBitmap = bmp
        frameCanvas = Canvas(bmp)
        prepared = true
    }

    /**
     * 绑定宿主 [TextView]，保存其 WeakReference 用于 Choreographer 帧驱动 invalidate。
     * 若尚未 disposed 且未 running，会自动 [start]。
     *
     * @param textView 承载该 SVGA Drawable 的 TextView
     */
    override fun bindHost(textView: TextView) {
        if (disposed) return
        hostRef = WeakReference(textView)
        textView.post {
            if (disposed) return@post
            prepareIfNeeded()
            start()
            textView.invalidate()
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = widthPx
    override fun getIntrinsicHeight(): Int = heightPx

    /**
     * 启动渲染。disposed / 已 running / 无帧 直接忽略。
     */
    override fun start() {
        if (running || disposed || totalFrames <= 0) return
        if (imageView == null) return
        running = true
        lastFrameTimeMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * 走 [pause] 语义，可通过 [start] 恢复；终态用 [release]。
     */
    override fun stop() {
        pause()
    }

    override fun isRunning(): Boolean = running

    /**
     * 暂停渲染并 recycle frameBitmap，可由 [start] 恢复。
     */
    fun pause() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameBitmap?.recycle()
        frameBitmap = null
        frameCanvas = null
        prepared = false
    }

    /**
     * 终态释放：清掉 imageView / frameBitmap / 回调，之后 start/draw 全部 noop。
     * 注意不释放 [entity]：它由 [SvgaCache] LRU 共享。
     */
    override fun release() {
        if (disposed) return
        disposed = true
        running = false
        prepared = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        hostRef = null
        frameBitmap?.recycle()
        frameBitmap = null
        frameCanvas = null
        imageView?.let { iv ->
            runCatching { iv.stopAnimation() }
            runCatching { iv.setImageDrawable(null) }
        }
        imageView = null
    }

    private companion object {
        private fun Context.applicationContextOrSelf(): Context {
            val app = applicationContext
            if (app != null) return app
            var c: Context = this
            while (c is ContextWrapper) {
                val base = c.baseContext ?: break
                if (base.applicationContext != null) return base.applicationContext
                c = base
            }
            return this
        }
    }
}
