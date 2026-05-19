package com.simple.mylibrary.span

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.Choreographer
import android.widget.TextView
import androidx.core.graphics.createBitmap
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGADynamicEntity
import com.opensource.svgaplayer.SVGAVideoEntity
import java.lang.ref.WeakReference

/**
 * SVGA → ImageSpan 桥接 Drawable。
 *
 * **设计要点**:
 *
 * 1. 把 [SVGADrawable] 包一层,适配 [Animatable] / [Releasable] 接口。
 * 2. 用 Choreographer 抓帧:每帧推进 currentFrame 后让外层 invalidate。
 * 3. 内部 Bitmap 复用 —— 不每帧 new。
 *
 * **RecyclerView 复用注意**:
 * - 同 URL 的 [SVGAVideoEntity] 由 [SvgaCache] LRU 共享,Drawable 是轻量壳。
 * - 复用 holder 时旧实例 [release] 解引用,entity 仍在缓存中,下次 bind 同 URL 直接命中。
 * - detach 时 [stop] 暂停 Choreographer,attach 回来 [start] 续播。
 *
 * **内存抖动控制**:
 * - 每个实例只持有 1 张 [frameBitmap],bounds 不变就不重建。
 * - SVGADrawable 内部的 SpriteEntity 列表由 entity 持有(共享),壳子里不复制。
 *
 * @param entity     共享 entity,多个 Drawable 实例可指向同一个。
 * @param widthPx    显示宽度。
 * @param heightPx   显示高度。
 */
class SvgaSpanDrawable(
    private val entity: SVGAVideoEntity,
    private val widthPx: Int,
    private val heightPx: Int,
) : Drawable(), Animatable, Releasable {

    private val inner: SVGADrawable = SVGADrawable(entity, SVGADynamicEntity()).apply {
        // SVGADrawable 默认 scaleType=MATRIX,我们没传 matrix,会导致雪碧图按原始 viewBox
        // 画到 (0,0),50dp 的 bounds 只能看到左上一小块。改成 FIT_XY 让它填满 bounds
        // (礼物特效一般是正方形,等比拉伸即可)。需要保持比例改 FIT_CENTER。
        scaleType = android.widget.ImageView.ScaleType.FIT_XY
    }
    private var currentFrame: Int = 0
    private val totalFrames: Int = entity.frames
    private val fps: Int = entity.FPS.coerceAtLeast(1)
    private val frameIntervalMs: Long = (1000L / fps).coerceAtLeast(8L)

    /**
     * 反射拿到 [SVGADrawable.currentFrame] 的 setter。
     *
     * SVGA 2.6.x 字节码里实际方法名是 `setCurrentFrame$com_opensource_svgaplayer`,
     * 参数类型 `(I)V` —— Kotlin internal 在 JVM 上是 public 方法 + 名字 mangling。
     *
     * 失败时(SVGA 内部重命名)降级:不推帧 → 显示静态首帧,不会崩。
     */
    private val currentFrameSetter: java.lang.reflect.Method? by lazy {
        runCatching {
            inner.javaClass.declaredMethods.firstOrNull { m ->
                m.name.startsWith("setCurrentFrame") &&
                        m.parameterTypes.size == 1 &&
                        m.parameterTypes[0].let { it == Int::class.javaPrimitiveType || it == Integer.TYPE }
            }?.also { it.isAccessible = true }
        }.onFailure {
            android.util.Log.w("SvgaSpanDrawable", "currentFrame setter reflect failed", it)
        }.getOrNull().also {
            if (it == null) {
                android.util.Log.w("SvgaSpanDrawable", "no setCurrentFrame method found, animation will be static")
            }
        }
    }

    private var frameBitmap: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var lastFrameTimeMs: Long = 0L

    private var running = false
    @Volatile private var disposed = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }

    private var hostRef: WeakReference<TextView>? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || disposed) return
            val nowMs = frameTimeNanos / 1_000_000L
            // 控帧:SVGA 自身 fps 通常 20~30,Choreographer 60+,按 frameIntervalMs 节流
            if (nowMs - lastFrameTimeMs >= frameIntervalMs) {
                currentFrame = (currentFrame + 1) % totalFrames
                currentFrameSetter?.invoke(inner, currentFrame)
                lastFrameTimeMs = nowMs
                hostRef?.get()?.invalidate() ?: run { stop(); return }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setBounds(0, 0, widthPx, heightPx)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width()
        val h = b.height()
        if (w <= 0 || h <= 0) {
            android.util.Log.w("SvgaSpanDrawable", "draw skipped: bounds=$b")
            return
        }

        var bmp = frameBitmap
        var c = frameCanvas
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = createBitmap(w, h)
            frameBitmap = bmp
            c = Canvas(bmp)
            frameCanvas = c
        }
        c!!.drawColor(0, PorterDuff.Mode.CLEAR)
        // SVGADrawable 内部按当前 currentFrame 渲染雪碧图;bounds 直接用 0,0,w,h
        inner.setBounds(0, 0, w, h)
        inner.draw(c)
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), paint)
    }

    /**
     * 绑定宿主 TextView,并自动 start。SpanBuilder 在 registerAsyncAnimatable 中调用,
     * 此时 drawable 已经被放进 ssb 设给 textView。
     *
     * 为什么 start 放这里而不是 SvgaSpanLoader.onReady:
     * 在 onReady 里 start 时 hostRef 还没绑,Choreographer 第一次 doFrame 取不到 host
     * 会立刻 stop(),动画"加载完静止"。把 start 推迟到 bindHost,确保有 host 才跑。
     */
    fun bindHost(textView: TextView) {
        hostRef = WeakReference(textView)
        if (!running && !disposed) start()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = widthPx
    override fun getIntrinsicHeight(): Int = heightPx

    override fun start() {
        if (running || disposed || totalFrames <= 0) return
        running = true
        lastFrameTimeMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun stop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun isRunning(): Boolean = running

    override fun release() {
        disposed = true
        stop()
        hostRef = null
        frameBitmap?.recycle()
        frameBitmap = null
        frameCanvas = null
        // **不释放 entity**:它在 SvgaCache 里被多个壳子共享,LRU 自己管理生命周期。
    }
}
