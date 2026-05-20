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
 *
 * **实现思路**:用一个**离屏的 SVGAImageView** 作为渲染引擎,每帧 stepToFrame 后
 * imageView.draw(离屏 Canvas) 抓帧到 frameBitmap,再 drawBitmap 到外层。
 * 这样所有 SVGA 内部状态(sprites、drawer、sharedValues、Matrix 等)
 * 都跟正常 SVGAImageView 一致,避免 internal API 重构带来的破坏。
 *
 * **RecyclerView 复用 + 内存抖动控制**:
 *
 * 1. **Entity 共享**:同 URL entity 由 [SvgaCache] LRU 共享,壳子轻量。
 * 2. **三态生命周期**:
 *    - `start` / `pause` 可恢复:detach 走 pause(保留 imageView 和 entity);attach 回 start。
 *    - `stop` / `release` 终态:bind 新数据走 release,disposed=true 后续 start 拒绝。
 *      WeakReference + Application context 避免 Activity 泄漏。
 * 3. **disposed 锁**:防止 RecyclerView 快速复用时 stale runnable 让旧 driver 复活。
 * 4. **Bitmap 复用**:bounds 不变不重建;detach 时主动 recycle 节省 native 内存。
 * 5. **Choreographer 严格管理**:doFrame 检测 hostRef 失效立即 stop,不空跑。
 *
 * **Activity 引用保护**:SVGAImageView 内部需要 Context 拿资源,这里我们尽量取
 * applicationContext;实在拿不到才回退到传入的 host(用户自己保证传 Activity 时能管理)。
 * 实际持有的 SVGAImageView 在 [release] 时设为 null,允许 GC 释放 Activity。
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

    /**
     * 离屏 SVGAImageView。`var` 是为了 release 时置 null 让 Activity 引用可被 GC。
     * 用 applicationContext 优先,避免持有 Activity 的强引用导致复用泄漏。
     */
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

    /**
     * 终态锁。stop 或 release 后置 true,后续 start 直接 noop。
     * 解决:RecyclerView 快速滚动时 attach listener 的 onAttached 回调可能晚到,
     * 而 release 已先执行,stale start 不能让 disposed 实例复活。
     */
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
                    // host 已被 GC(holder 回收)→ 主动 pause,等 attach 回来或 release
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
            // 解 entity 关联,避免 SVGADrawable 持有 entity 强引用阻止 LRU 驱逐时回收
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
            // applicationContext 在大多数 Activity 下能拿到 Application
            val app = applicationContext
            if (app != null) return app
            // 一层 wrapper 兜底
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
