package com.chat.svgspan

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.chat.spanutil.SpanBuilder
import com.chat.spanutil.SpanLog
import com.chat.spanutil.span.LoaderType
import com.chat.spanutil.span.RoundMaskDrawable
import com.chat.spanutil.span.SpanImageLoader
import com.chat.svgspan.DefaultSvgLoader.Companion.install
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * 默认 SVG 加载器：支持 raw 资源 / 本地文件 / http(s) 远端；静态用 AndroidSVG，
 * 动态(含 <animate> 等)用 [AnimatedSvgDrawable] WebView 渲染。
 *
 * Application 中通过 [install] 一键注入：
 * ```
 * DefaultSvgLoader.install()
 * ```
 *
 * @param client 可选 OkHttpClient，默认使用内置共享实例
 */
class DefaultSvgLoader(
    private val client: OkHttpClient = sharedClient,
) : SpanImageLoader {

    override fun load(
        context: Context,
        url: Any,
        width: Int,
        height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    ) {
        val cacheKey = "$url|${width}x${height}"
        pictureCache.get(cacheKey)?.let {
            onReady(maybeWrap(it.constantState?.newDrawable() ?: it, circle))
            return
        }
        when (url) {
            is Int -> {
                ioExecutor.execute {
                    val bytes = runCatching {
                        context.resources.openRawResource(url).use { it.readBytes() }
                    }.onFailure { SpanLog.w(TAG, it) { "raw svg read failed: $url" } }
                        .getOrNull() ?: return@execute
                    handleBytes(context, cacheKey, bytes, width, height, circle, onReady)
                }
            }

            is String -> {
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    loadRemote(context, cacheKey, url, width, height, circle, onReady)
                } else {
                    val path = if (url.startsWith("file://")) url.removePrefix("file://") else url
                    ioExecutor.execute {
                        val bytes = runCatching {
                            File(path).readBytes()
                        }.onFailure { SpanLog.w(TAG, it) { "file svg read failed: $path" } }
                            .getOrNull() ?: return@execute
                        handleBytes(context, cacheKey, bytes, width, height, circle, onReady)
                    }
                }
            }
        }
    }

    private fun handleBytes(
        context: Context,
        cacheKey: String,
        bytes: ByteArray,
        width: Int,
        height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    ) {
        val animated = looksAnimated(bytes) && context is android.app.Activity
        if (animated) {
            mainHandler.post {
                val animDrawable = runCatching {
                    AnimatedSvgDrawable(bytes, width, height, context).also { it.start() }
                }.onFailure { SpanLog.w(TAG, it) { "animated svg failed, fallback to static" } }
                    .getOrNull()
                if (animDrawable != null) {
                    onReady(maybeWrap(animDrawable, circle))
                } else {
                    ioExecutor.execute {
                        val staticPic = renderStatic(bytes, width, height) ?: return@execute
                        pictureCache.put(cacheKey, staticPic)
                        mainHandler.post { onReady(maybeWrap(staticPic, circle)) }
                    }
                }
            }
            return
        }
        val staticPic = renderStatic(bytes, width, height) ?: return
        pictureCache.put(cacheKey, staticPic)
        mainHandler.post { onReady(maybeWrap(staticPic, circle)) }
    }

    private fun loadRemote(
        context: Context,
        cacheKey: String,
        url: String,
        width: Int,
        height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
            .header("Accept", "image/svg+xml,image/*;q=0.8,*/*;q=0.5")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                SpanLog.w(TAG, e) { "remote svg failed: $url" }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        SpanLog.w(TAG) { "remote svg http ${resp.code}: $url" }
                        return
                    }
                    val body = resp.body ?: run {
                        SpanLog.w(TAG) { "remote svg empty body: $url" }
                        return
                    }
                    val bytes = runCatching { body.bytes() }
                        .onFailure { SpanLog.w(TAG, it) { "remote svg body read failed: $url" } }
                        .getOrNull() ?: return
                    handleBytes(context, cacheKey, bytes, width, height, circle, onReady)
                }
            }
        })
    }

    private fun renderStatic(bytes: ByteArray, width: Int, height: Int): Drawable? =
        runCatching {
            SvgRenderer.render(bytes.inputStream(), width, height)
        }.onFailure { SpanLog.w(TAG, it) { "static svg parse failed" } }
            .getOrNull()

    private fun maybeWrap(drawable: Drawable, circle: Boolean): Drawable =
        if (circle) RoundMaskDrawable(drawable, cornerRadius = -1f) else drawable

    private fun looksAnimated(bytes: ByteArray): Boolean {
        val sample = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).lowercase()
        return sample.contains("<animate") ||
                sample.contains("<set ") ||
                sample.contains("<script") ||
                sample.contains("@keyframes") ||
                sample.contains("animation:") ||
                sample.contains("animation-name:")
    }

    companion object {
        private const val TAG = "DefaultSvgLoader"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private val mainHandler = Handler(Looper.getMainLooper())
        private val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "svg-loader").apply { isDaemon = true }
        }
        private val pictureCache = LruCache<String, Drawable>(64)
        private val sharedClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        /**
         * 把当前实现注入到 [SpanBuilder] 全局 SVG 加载器，等价于：
         * `SpanBuilder.setLoader(LoaderType.Svg, DefaultSvgLoader())`。
         */
        @JvmStatic
        fun install() {
            SpanBuilder.setLoader(LoaderType.Svg, DefaultSvgLoader())
        }
    }
}
