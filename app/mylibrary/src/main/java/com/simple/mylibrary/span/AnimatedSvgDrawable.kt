package com.simple.mylibrary.span

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.createBitmap

/**
 * 动态 SVG 渲染:用一个屏外 [WebView] 当 SVG 渲染引擎,逐帧抓帧成 [Bitmap] 输出。
 *
 * **为什么不用 AndroidSVG**:`com.caverock:androidsvg` 解析 SMIL `<animate>` /
 * CSS `@keyframes` / `<script>` 都只取静态首帧。要让动画真正动起来,必须有一个
 * 跑动画时间线的引擎 —— WebView 是最现成的方案,没有 fork 一个 SVG 库的成本。
 *
 * **生命周期**:
 * - 构造时把 WebView 挂到 [host] 的 decor view 下(1×1px、透明、移到 -10000 屏外),
 *   保证它有 attach 的渲染时钟,但用户不可见、不挡触摸。
 * - [start] 在主线程开 Choreographer,每帧 `webView.draw()` 到内部 Bitmap,
 *   再 `invalidateSelf()` 让宿主 TextView 重绘。
 * - [stop] 取消 Choreographer 回调,WebView 不再接受时钟。
 * - [release] 从父 view 卸下并 destroy WebView,recycle 内部 Bitmap。
 *   RecyclerView 复用 / TextView detach 时由 SpanBuilder 统一调用。
 *
 * **同步加载语义**:WebView `loadDataWithBaseURL` 异步,首帧未必立即可用,
 * 因此 [draw] 在 bitmap 还没准备好时画透明,等到第一帧后再正常输出。
 *
 * @param svgBytes 原始 SVG 字节流,会被 base64 包成 data: URI 注入 WebView。
 * @param widthPx  绘制宽度,与外层 ImageSpan bounds 一致。
 * @param heightPx 绘制高度。
 * @param host     用于挂载 WebView 的宿主 Context;必须是 Activity Context,
 *                 因为需要拿到 decor view。
 */
@SuppressLint("SetJavaScriptEnabled")
class AnimatedSvgDrawable(
    svgBytes: ByteArray,
    private val widthPx: Int,
    private val heightPx: Int,
    host: Context,
) : Drawable(), Animatable, Releasable {

    private var webView: WebView? = null
    private var hostContainer: ViewGroup? = null
    private var frameBitmap: Bitmap? = null
    private var frameCanvas: Canvas? = null
    private var running = false
    private var firstFrameRendered = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val choreographer: Choreographer = Choreographer.getInstance()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val wv = webView ?: return
            val bmp = frameBitmap ?: return
            val canvas = frameCanvas ?: return
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            // wv.draw 跑在主线程,WebView 内部把当前帧合成到 canvas
            wv.draw(canvas)
            firstFrameRendered = true
            invalidateSelf()
            if (running) choreographer.postFrameCallback(this)
        }
    }

    init {
        setBounds(0, 0, widthPx, heightPx)
        // 仅 Activity Context 能拿到 decor view 来挂 WebView。
        // 拿不到就降级:WebView 仍然能渲染但没有 attach,渲染时钟可能不动 —— 业务侧应保证传 Activity。
        val decor = (host as? android.app.Activity)?.window?.decorView as? ViewGroup
        if (decor != null) {
            val wv = WebView(host)
            val container = FrameLayout(host).apply {
                // 移到屏外,避免遮挡 / 触摸影响
                translationX = -10000f
                alpha = 0f
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            container.addView(
                wv,
                FrameLayout.LayoutParams(widthPx, heightPx)
            )
            decor.addView(
                container,
                ViewGroup.LayoutParams(widthPx, heightPx)
            )
            wv.setBackgroundColor(0)
            wv.settings.apply {
                javaScriptEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            webView = wv
            hostContainer = container

            val html = buildHtml(svgBytes, widthPx, heightPx)
            wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val bmp = frameBitmap ?: ensureBitmap() ?: return
        if (!firstFrameRendered) return  // 等首帧
        canvas.drawBitmap(bmp, b.left.toFloat(), b.top.toFloat(), null)
    }

    private fun ensureBitmap(): Bitmap? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val bmp = createBitmap(widthPx, heightPx)
        frameBitmap = bmp
        frameCanvas = Canvas(bmp)
        return bmp
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = widthPx
    override fun getIntrinsicHeight(): Int = heightPx

    override fun start() {
        if (running) return
        running = true
        ensureBitmap()
        // WebView 自己的动画时钟也要恢复(被 stop 时调过 onPause)
        webView?.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frameCallback)
        // pause WebView 内部的 JS 时钟和渲染:RecyclerView 滚出屏幕时
        // 即使我们停了抓帧,WebView 仍在内部跑 SMIL/CSS 动画,持续耗 CPU。
        // 必须 onPause 才能让浏览器内核停下来。
        webView?.onPause()
    }

    override fun isRunning(): Boolean = running

    override fun release() {
        stop()
        // WebView 必须在主线程 destroy
        mainHandler.post {
            val wv = webView
            val container = hostContainer
            webView = null
            hostContainer = null
            if (wv != null) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
            container?.let {
                (it.parent as? ViewGroup)?.removeView(it)
            }
            frameBitmap?.recycle()
            frameBitmap = null
            frameCanvas = null
        }
    }

    companion object {
        private fun buildHtml(svgBytes: ByteArray, w: Int, h: Int): String {
            val b64 = Base64.encodeToString(svgBytes, Base64.NO_WRAP)
            // 直接把 svg 内联进 HTML,避免 data:image/svg+xml 在某些 WebView 下被当成静态图
            val svgText = String(svgBytes, Charsets.UTF_8)
            // 优先内联,渲染失败再退回 base64 img
            val safe = if (svgText.contains("<svg", ignoreCase = true)) svgText else "<img src=\"data:image/svg+xml;base64,$b64\" width=\"$w\" height=\"$h\"/>"
            return """
                <!DOCTYPE html>
                <html><head>
                <meta charset="utf-8">
                <style>
                    html,body{margin:0;padding:0;background:transparent;width:${w}px;height:${h}px;overflow:hidden;}
                    svg,img{display:block;width:${w}px;height:${h}px;}
                </style>
                </head><body>$safe</body></html>
            """.trimIndent()
        }
    }
}
