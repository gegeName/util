package com.simple.mylibrary.span

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log

/**
 * SVGA 加载器,实现 [SpanImageLoader] 接口,接入 SpanBuilder 已有的异步图片通道。
 *
 * 走通用通道的好处:
 * - 占位符、border、transformer、circle mask 都自动复用。
 * - RecyclerView 复用、生命周期管理由 SpanBuilder 统一处理。
 * - 异步回调失效检测(tag 校验)直接生效。
 *
 * **circle 选项**:circle = true 时用 [RoundMaskDrawable] 包一层,与 GIF / SVG 路径一致。
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
                Log.i("SvgaSpanLoader", "entity ready, build drawable: $key")
                val drawable = SvgaSpanDrawable(entity, width, height)
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

