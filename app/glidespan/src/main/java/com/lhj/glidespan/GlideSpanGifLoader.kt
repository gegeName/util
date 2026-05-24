package com.lhj.glidespan

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.lhj.spanutil.SpanBuilder
import com.lhj.spanutil.span.LoaderType
import com.lhj.spanutil.span.RoundMaskDrawable
import com.lhj.spanutil.span.SpanImageLoader

/**
 * 基于 Glide 的 GIF / WebP 动图加载器，与 [GlideSpanImageLoader] 分离避免相互干扰。
 *
 * 与普通 Image 加载器的差异：
 * - 走 `asGif()` 强制 GIF 解码通道（普通通道偶尔会把 GIF 当成静态首帧返回）
 * - 关闭磁盘缓存的 transformation 副本（保留资源缓存）
 * - `setLoopCount(LOOP_FOREVER)` + `Animatable2Compat` 监听 onAnimationEnd 自动重启
 *   （某些 GIF 第一轮播完后 Glide 不会自动循环，导致"看起来不动了"）
 *
 * Application 中通过 [install] 一键注入：
 * ```
 * GlideSpanGifLoader.install()
 * ```
 */
class GlideSpanGifLoader : SpanImageLoader {

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
            .diskCacheStrategy(DiskCacheStrategy.DATA)
        Glide.with(context).asGif().load(url).apply(options)
            .into(object : CustomTarget<GifDrawable>() {
                override fun onResourceReady(
                    resource: GifDrawable,
                    transition: Transition<in GifDrawable>?,
                ) {
                    configureGif(resource)
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
        if (gif.isRunning) gif.stop()
        gif.start()
    }

    companion object {
        /**
         * 把当前实现注入到 [SpanBuilder] 全局 GIF 加载器，等价于：
         * `SpanBuilder.setLoader(LoaderType.Gif, GlideSpanGifLoader())`。
         */
        @JvmStatic
        fun install() {
            SpanBuilder.setLoader(LoaderType.Gif, GlideSpanGifLoader())
        }
    }
}
