package com.lhj.spanutil.span

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * SVGA 加载器,实现 [SpanImageLoader] 接口,接入 SpanBuilder 已有的异步图片通道。
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
        Log.i("SvgaSpanLoader", "load: $key  size=${width}x${height} circle=$circle")
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
                Log.w("SvgaSpanLoader", "svga load error: $key")
            },
        )
    }
}

