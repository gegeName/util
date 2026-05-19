package com.simple.mylibrary.span

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException

/**
 * 默认 SVG 加载器。url 参数支持三种形式:
 *
 * - `Int`: `@RawRes` 资源 ID,从 res/raw 读取。同步加载。
 * - `String` 以 `http://` / `https://` 开头: OkHttp 异步下载,主线程回调。
 * - `String` 其他形式(本地文件绝对路径或 `file://` URI): 同步读文件并解析。
 *
 * 解析失败 / 文件不存在 / 网络失败时静默丢弃,onReady 不会被回调,
 * SpanBuilder 占位符会保留为透明区域,不会破坏文字布局。
 *
 * `circle` 参数会在加载完成后用 [RoundMaskDrawable] 包装,与 GIF 走相同流程。
 *
 * 远程下载共享一个 OkHttpClient 单例,避免每次新建连接池造成内存抖动。
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
                    context.resources.openRawResource(url).use { stream ->
                        SvgRenderer.render(stream, width, height)
                    }
                }.getOrNull()?.let { drawable ->
                    onReady(maybeWrap(drawable, circle))
                }
            }

            is String -> {
                if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                    loadRemote(url, width, height, circle, onReady)
                } else {
                    val path = if (url.startsWith("file://")) url.removePrefix("file://") else url
                    runCatching {
                        File(path).inputStream().use { stream ->
                            SvgRenderer.render(stream, width, height)
                        }
                    }.getOrNull()?.let { drawable ->
                        onReady(maybeWrap(drawable, circle))
                    }
                }
            }
        }
    }

    private fun loadRemote(
        url: String,
        width: Int,
        height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    ) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) return
                    val body = resp.body ?: return
                    val drawable = runCatching {
                        body.byteStream().use { stream ->
                            SvgRenderer.render(stream, width, height)
                        }
                    }.getOrNull() ?: return
                    val finalDrawable = maybeWrap(drawable, circle)
                    mainHandler.post { onReady(finalDrawable) }
                }
            }
        })
    }

    private fun maybeWrap(drawable: Drawable, circle: Boolean): Drawable =
        if (circle) RoundMaskDrawable(drawable, cornerRadius = -1f) else drawable

    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
        private val sharedClient by lazy { OkHttpClient() }
    }
}
