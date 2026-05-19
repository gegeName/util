package com.simple.mylibrary.span

import android.content.Context
import android.graphics.drawable.Drawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition


/**
 * 默认图片加载器实现（Glide）。
 * 项目依赖 Glide 时开箱即用；如需替换为其他框架，调用 [com.simple.mylibrary.utils.SpanBuilder.setImageLoader] 即可。
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
        var options = RequestOptions().override(width, height)
        Glide.with(context).asDrawable().load(url).apply(options)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(
                    resource: Drawable,
                    transition: Transition<in Drawable>?
                ) {
                    val finalDrawable: Drawable =
                        if (circle) RoundMaskDrawable(resource, cornerRadius = -1f)
                        else resource
                    onReady(finalDrawable)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }
}