package com.simple.mylibrary.span

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.Px
/**
 * 网络图片加载接口。通过 [com.simple.mylibrary.utils.SpanBuilder.setImageLoader] 全局注册实现类，
 * 可自由选择 Glide / Coil / Picasso 等框架。
 *
 * 库默认提供 [GlideSpanImageLoader]（需项目依赖 Glide）。
 *
 * 示例（Coil）：
 * ```
 * SpanBuilder.setImageLoader { context, url, w, h, circle, onReady ->
 *     val request = ImageRequest.Builder(context)
 *         .data(url).size(w, h)
 *         .apply { if (circle) transformations(CircleCropTransformation()) }
 *         .target { onReady(it.toDrawable(context.resources)) }
 *         .build()
 *     context.imageLoader.enqueue(request)
 * }
 * ```
 */
fun interface SpanImageLoader {
    /**
     * 加载 [url] 对应的图片，完成后在**主线程**回调 [onReady]，传入已加载好的 Drawable。
     */
    fun load(
        context: Context,
        url: String,
        @Px width: Int,
        @Px height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    )
}