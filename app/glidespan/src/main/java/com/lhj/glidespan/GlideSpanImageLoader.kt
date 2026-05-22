package com.lhj.glidespan

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.lhj.spanutil.SpanBuilder
import com.lhj.spanutil.span.LoaderType
import com.lhj.spanutil.span.RoundMaskDrawable
import com.lhj.spanutil.span.SpanImageLoader

/**
 * 基于 Glide 的 [SpanImageLoader] 实现。Application 中通过 [install] 一键注入：
 * ```
 * GlideSpanImageLoader.install()
 * ```
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
        val options = RequestOptions().override(width, height)
        Glide.with(context).asDrawable().load(url).apply(options)
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(
                    resource: Drawable,
                    transition: Transition<in Drawable>?,
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
