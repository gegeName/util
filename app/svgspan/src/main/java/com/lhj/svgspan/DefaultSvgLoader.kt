package com.lhj.svgspan

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.lhj.spanutil.SpanBuilder
import com.lhj.spanutil.span.LoaderType
import com.lhj.spanutil.span.RoundMaskDrawable
import com.lhj.spanutil.span.SpanImageLoader
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

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
        when (url) {
            is Int -> {
                runCatching {
                    context.resources.openRawResource(url).use { it.readBytes() }
                }.onFailure { Log.w(TAG, "raw svg read failed: $url", it) }
                    .getOrNull()?.let { bytes ->
                        renderBytes(context, bytes, width, height, circle, onReady)
                    }
            }

            is String -> {
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    loadRemote(context, url, width, height, circle, onReady)
                } else {
                    val path = if (url.startsWith("file://")) url.removePrefix("file://") else url
                    runCatching {
                        File(path).readBytes()
                    }.onFailure { Log.w(TAG, "file svg read failed: $path", it) }
                        .getOrNull()?.let { bytes ->
                            renderBytes(context, bytes, width, height, circle, onReady)
                        }
                }
            }
        }
    }

    private fun loadRemote(
        context: Context,
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
                Log.w(TAG, "remote svg failed: $url", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "remote svg http ${resp.code}: $url")
                        return
                    }
                    val body = resp.body ?: run {
                        Log.w(TAG, "remote svg empty body: $url")
                        return
                    }
                    val bytes = runCatching { body.bytes() }
                        .onFailure { Log.w(TAG, "remote svg body read failed: $url", it) }
                        .getOrNull() ?: return
                    mainHandler.post {
                        renderBytes(context, bytes, width, height, circle, onReady)
                    }
                }
            }
        })
    }

    private fun renderBytes(
        context: Context,
        bytes: ByteArray,
        width: Int,
        height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    ) {
        val animated = looksAnimated(bytes) && context is android.app.Activity
        val drawable: Drawable? = if (animated) {
            runCatching {
                AnimatedSvgDrawable(bytes, width, height, context).also {
                    it.start()
                }
            }.onFailure { Log.w(TAG, "animated svg failed, fallback to static", it) }
                .getOrNull()
                ?: renderStatic(bytes, width, height)
        } else {
            renderStatic(bytes, width, height)
        }
        drawable?.let { onReady(maybeWrap(it, circle)) }
    }

    private fun renderStatic(bytes: ByteArray, width: Int, height: Int): Drawable? =
        runCatching {
            SvgRenderer.render(bytes.inputStream(), width, height)
        }.onFailure { Log.w(TAG, "static svg parse failed", it) }
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
