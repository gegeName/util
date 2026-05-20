package com.lhj.spanutil.span

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * **静态 vs 动态**:加载到字节流后会先用 [looksAnimated] 探测是否包含
 * SMIL `<animate*>` / `<set>` / CSS `animation:` / `@keyframes` / `<script>`。
 * 含动画特征则走 [AnimatedSvgDrawable](WebView 引擎,逐帧抓帧),否则走
 * [SvgRenderer](AndroidSVG → 单帧 PictureDrawable)。
 *
 * 解析失败 / 文件不存在 / 网络失败时静默丢弃 onReady,但会通过 [Log] 打 warn,
 * 便于排查;SpanBuilder 占位符会保留为透明区域,不破坏文字布局。
 *
 * `circle` 参数会在加载完成后用 [RoundMaskDrawable] 包装,与 GIF 走相同流程。
 *
 * 远程下载共享一个 OkHttpClient 单例(预设浏览器风格 UA + 跟随重定向),
 * 避免每次新建连接池造成内存抖动,同时避开部分服务器对默认 okhttp UA 的 403。
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

    /**
     * 拿到完整 SVG 字节后:静态走 AndroidSVG;动态走 WebView。
     * 动态路径需要 Activity Context 用来挂 WebView,如果传进来的不是 Activity,降级成静态。
     */
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

    /**
     * 朴素文本探测:看头部 4KB 是否含 SVG 动画 / 脚本 / CSS 动画特征。
     * 这是个快速判断,只用来路由到 WebView;误判成动画最多只是性能浪费,
     * 误判成静态会丢掉动画,所以宁可宽松一点。
     */
    private fun looksAnimated(bytes: ByteArray): Boolean {
        val sample = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8).lowercase()
        return sample.contains("<animate") ||           // <animate>, <animateTransform>, <animateMotion>
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
    }
}
