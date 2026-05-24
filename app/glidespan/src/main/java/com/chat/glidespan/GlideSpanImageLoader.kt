package com.chat.glidespan

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.chat.spanutil.SpanBuilder
import com.chat.spanutil.span.LoaderType
import com.chat.spanutil.span.RoundMaskDrawable
import com.chat.spanutil.span.SpanImageLoader

/**
 * 基于 Glide 的普通图片加载器（位图）。Application 中通过 [install] 一键注入：
 * ```
 * GlideSpanImageLoader.install()
 * ```
 *
 * 注意：此实现走 `asDrawable()` 通用通道，**不专门处理 GIF 动画循环**；
 * GIF / WebP 动图请用 [GlideSpanGifLoader]（[LoaderType.Gif]）。
 */
class GlideSpanImageLoader : SpanImageLoader {

    override fun load(
        context: Context,
        url: Any,
        width: Int,
        height: Int,
        circle: Boolean,
        onReady: (Drawable) -> Unit,
    ) {
        val options = RequestOptions()
            .override(width, height)
            .format(DecodeFormat.PREFER_RGB_565)
        Glide.with(context).asDrawable().load(url).apply(options)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(
                    resource: Drawable,
                    transition: Transition<in Drawable>?,
                ) {
                    val finalDrawable: Drawable =
                        if (circle) RoundMaskDrawable(resource, cornerRadius = -1f)
                        else resource
                    onReady(finalDrawable)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    companion object {
        /**
         * 把当前实现注入到 [SpanBuilder] 全局图片加载器，等价于：
         * `SpanBuilder.setLoader(LoaderType.Image, GlideSpanImageLoader())`。
         */
        @JvmStatic
        fun install() {
            SpanBuilder.setLoader(LoaderType.Image, GlideSpanImageLoader())
        }
    }
}
