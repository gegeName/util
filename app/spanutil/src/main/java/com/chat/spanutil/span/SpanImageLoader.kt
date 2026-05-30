package com.chat.spanutil.span

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.annotation.Px
import com.chat.spanutil.SpanBuilder
/**
 * 网络图片加载接口。通过 [SpanBuilder.setLoader] 全局注册实现类，
 * 可自由选择 Glide / Coil / Picasso 等框架。spanutil 自身不绑任何具体实现，
 * 项目侧通过独立的 glidespan 库（或自定义实现）注入。
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
        url: Any,
        @Px width: Int,
        @Px height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    )
}
