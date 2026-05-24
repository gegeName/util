package com.chat.svgspan

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
import com.chat.spanutil.span.Releasable

/**
 * 动态 SVG 渲染：用屏外 [WebView] 当 SVG 引擎，逐帧抓帧成 [Bitmap] 输出。
 *
 * @param svgBytes 原始 SVG 字节流（会以 base64 注入 WebView）
 * @param widthPx  绘制宽度（px）
 * @param heightPx 绘制高度（px）
 * @param host     用于挂载 WebView 的宿主 Context；必须是 Activity Context
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
            wv.draw(canvas)
            firstFrameRendered = true
            invalidateSelf()
            if (running) choreographer.postFrameCallback(this)
        }
    }

    init {
        setBounds(0, 0, widthPx, heightPx)
        val decor = (host as? android.app.Activity)?.window?.decorView as? ViewGroup
        if (decor != null) {
            val wv = WebView(host)
            val container = FrameLayout(host).apply {
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
        if (!firstFrameRendered) return
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
        webView?.onResume()
        choreographer.postFrameCallback(frameCallback)
    }

    override fun stop() {
        if (!running) return
        running = false
        choreographer.removeFrameCallback(frameCallback)
        webView?.onPause()
    }

    override fun isRunning(): Boolean = running

    override fun release() {
        stop()
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
            val svgText = String(svgBytes, Charsets.UTF_8)
            val safe = if (svgText.contains("<svg", ignoreCase = true)) svgText
            else "<img src=\"data:image/svg+xml;base64,$b64\" width=\"$w\" height=\"$h\"/>"
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
