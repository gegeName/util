package com.simple.mylibrary.span

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition


/**
 * 默认图片加载器实现（Glide）。
 * 项目依赖 Glide 时开箱即用；如需替换为其他框架，调用 [com.simple.mylibrary.utils.SpanBuilder.setImageLoader] 即可。
 *
 * **GIF 稳定播放的关键四件事**(踩过坑):
 *
 * 1. **`Glide.with(applicationContext)`** —— 不传 Activity。
 *    Glide.with(activity) 会跟 Activity 生命周期绑定:Activity onStop 时 Glide 主动 pause
 *    它管理的所有 GifDrawable;再 onStart 时 resume。我们的
 *    [com.simple.mylibrary.utils.SpanBuilder.attachAnimationLifecycle] 也在 attach/detach
 *    时管动图,**两套 lifecycle 互相干扰**,表现就是"GIF 概率性不动"。
 *
 * 2. **`skipMemoryCache(true)`** —— GIF 跳过内存缓存。
 *    Glide 内存缓存命中时,即使返回新 GifDrawable 实例,内部 [com.bumptech.glide.load.resource.gif.GifFrameLoader]
 *    可能是共享的(GifState.frameLoader),前一次离开 Activity 时 stop 留下的 subscriber 状态
 *    会让新一次 subscribe 失败(Glide 内部抛 "Cannot subscribe twice in a row"),帧永远不推进。
 *    跳过内存缓存让每次都从磁盘解码出全新 FrameLoader;磁盘缓存命中,网络不会重发,几乎零开销。
 *
 * 3. **`stop() + start()` 强制重置帧索引** —— 即使有上面两道防线,start() 内部
 *    判断"如果当前帧已是末帧就 noop",所以总是先 stop 再 start。
 *
 * 4. **`setLoopCount(LOOP_FOREVER)` + onAnimationEnd 兜底**:覆盖 GIF 文件
 *    NETSCAPE 的循环次数限制,任何 GIF 都无限播。
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
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
        Glide.with(context.applicationContext).asDrawable().load(url).apply(options)
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

