package com.simple.mylibrary.span

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
 * 项目依赖 Glide 时开箱即用；如需替换为其他框架，调用 [com.simple.mylibrary.utils.SpanBuilder.setImageLoader] 即可。
 *
 * **GIF 强制无限循环**:Glide 的 [GifDrawable] 默认按 GIF 文件里编码的 NETSCAPE
 * loopCount 跑,大多数 GIF 编码时只写 1~10 次循环,**跑完就停**。
 *
 * `setLoopCount(LOOP_FOREVER)` 在部分 GIF 上不可靠(取决于 NETSCAPE 块解析),
 * 这里再加一道**onAnimationEnd 监听**,动画结束就 `start()` 一次,等价于无限循环兜底。
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
                    if (resource is GifDrawable) {
                        resource.setLoopCount(GifDrawable.LOOP_FOREVER)
                        // 兜底:某些 GIF 的 NETSCAPE 块缺失或解析丢失,setLoopCount 不生效。
                        // 监听 end → 手动 start,等价于无限循环。
                        resource.registerAnimationCallback(object : Animatable2Compat.AnimationCallback() {
                            override fun onAnimationEnd(drawable: Drawable) {
                                if (drawable is GifDrawable) drawable.start()
                            }
                        })
                    }
                    val finalDrawable: Drawable =
                        if (circle) RoundMaskDrawable(resource, cornerRadius = -1f)
                        else resource
                    onReady(finalDrawable)
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }
}

