package com.lhj.spanutil.span

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
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAVideoEntity
import java.lang.ref.WeakReference

/**
 * SVGA → ImageSpan 桥接 Drawable。
 */
class SvgaSpanDrawable(
    private val entity: SVGAVideoEntity,
    private val widthPx: Int,
    private val heightPx: Int,
    host: Context,
) : Drawable(), Animatable, Releasable {

    private val totalFrames: Int = entity.frames
    private val fps: Int = entity.FPS.coerceAtLeast(1)
    private val frameIntervalMs: Long = (1000L / fps).coerceAtLeast(8L)

    private var imageView: SVGAImageView? = SVGAImageView(host.applicationContextOrSelf()).apply {
        loops = 0
        scaleType = ImageView.ScaleType.FIT_XY
        setVideoItem(entity)
        measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, widthPx, heightPx)
    }

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

        var bmp = frameBitmap
        var c = frameCanvas
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = createBitmap(w, h)
            frameBitmap = bmp
            c = Canvas(bmp)
            frameCanvas = c
            if (iv.width != w || iv.height != h) {
                iv.measure(
                    View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                )
                iv.layout(0, 0, w, h)
            }
        }
        c!!.drawColor(0, PorterDuff.Mode.CLEAR)
        iv.draw(c)
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), paint)
    }

    /**
     * SpanBuilder 在 registerAsyncAnimatable 调,把宿主 TextView 的 WeakReference 存下,
     * Choreographer 自己 invalidate textView。bindHost 时如果尚未 start 且未 disposed
     * 自动 start。
     */
    fun bindHost(textView: TextView) {
        if (disposed) return
        hostRef = WeakReference(textView)
        start()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = widthPx
    override fun getIntrinsicHeight(): Int = heightPx

    /**
     * 启动渲染。disposed / 已 running / 无帧 直接忽略。
     * 跟 [pause] 配对使用:detach pause、attach start 可来回切。
     */
    override fun start() {
        if (running || disposed || totalFrames <= 0) return
        if (imageView == null) return
        running = true
        lastFrameTimeMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Animatable.stop 默认走 pause 语义(可恢复);终态用 [release]。
     * 这样 SpanBuilder.attachAnimationLifecycle 中 detach 时调 stop 不会破坏可恢复状态。
     */
    override fun stop() {
        pause()
    }

    override fun isRunning(): Boolean = running

    /**
     * 暂停渲染,可通过 start 恢复。同时 recycle frameBitmap 释放 native 内存。
     * RecyclerView 滚出屏幕(detach)→ pause → bitmap 释放;再滚回来 → start → bitmap 按需重建。
     */
    fun pause() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameBitmap?.recycle()
        frameBitmap = null
        frameCanvas = null
    }

    /**
     * 终态释放。bind 新数据 / TextView 永久销毁时调用。
     * - disposed=true 之后任何 start/draw 都 noop。
     * - imageView 置 null,断开 Activity 引用,允许 GC。
     * - **不释放 entity**:它在 SvgaCache 里被多个壳子共享,LRU 自己管生命周期。
     */
    override fun release() {
        if (disposed) return
        disposed = true
        running = false
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
        /**
         * 优先取 applicationContext,避免离屏 SVGAImageView 持有 Activity 引用。
         * 拿不到(罕见,如 ContextWrapper 包了 mock)就退回原 context。
         */
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
