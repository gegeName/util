package com.chat.svgaspan

import android.content.Context
import android.graphics.drawable.Drawable
import com.chat.spanutil.SpanBuilder
import com.chat.spanutil.SpanLog
import com.chat.spanutil.span.LoaderType
import com.chat.spanutil.span.RoundMaskDrawable
import com.chat.spanutil.span.SpanImageLoader

/**
 * SpanBuilder 的 SVGA 加载器实现。在 Application 中通过 [install] 一键注入：
 * ```
 * SvgaSpanLoader.install()
 * ```
 * 之后即可在 [SpanBuilder] 上调用 svga(...) 链式 API。
 */
class SvgaSpanLoader : SpanImageLoader {

    override fun load(
        context: Context,
        url: Any,
        width: Int,
        height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    ) {
        val key = url.toString()
        SpanLog.i("SvgaSpanLoader") { "load: $key  size=${width}x${height} circle=$circle" }
        SvgaCache.load(
            context,
            key,
            onReady = { entity ->
                val drawable = SvgaSpanDrawable(entity, width, height, context)
                val finalDrawable: Drawable =
                    if (circle) RoundMaskDrawable(drawable, cornerRadius = -1f) else drawable
                onReady(finalDrawable)
            },
            onError = {
                SpanLog.w("SvgaSpanLoader") { "svga load error: $key" }
            },
        )
    }

    companion object {
        /**
         * 把当前实现注入到 [SpanBuilder] 全局 svga 加载器，等价于：
         * `SpanBuilder.setLoader(LoaderType.Svga, SvgaSpanLoader())`。
         */
        @JvmStatic
        fun install() {
            SpanBuilder.setLoader(LoaderType.Svga, SvgaSpanLoader())
        }
    }
}
