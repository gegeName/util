package com.lhj.spanutil.span

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition


/**
 * 默认图片加载器实现（Glide）。
 * 项目依赖 Glide 时开箱即用；如需替换为其他框架，调用 [com.lhj.spanutil.SpanBuilder.setImageLoader] 即可。
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
        Glide.with(context).asDrawable().load(url).apply(options)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(
                    resource: Drawable,
                    transition: Transition<in Drawable>?
                ) {
                    if (resource is GifDrawable) {
                        configureGif(resource)
                    }
                    val finalDrawable: Drawable =
                        if (circle) RoundMaskDrawable(resource, cornerRadius = -1f)
                        else resource
                    onReady(finalDrawable)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    private fun configureGif(gif: GifDrawable) {
        gif.setLoopCount(GifDrawable.LOOP_FOREVER)
        gif.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable) {
                if (drawable is GifDrawable && !drawable.isRunning) drawable.start()
            }
        })
        gif.stop()
        gif.start()
    }
}

