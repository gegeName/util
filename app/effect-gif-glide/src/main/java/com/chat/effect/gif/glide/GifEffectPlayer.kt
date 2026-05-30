package com.chat.effect.gif.glide


import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.gif.GifDrawable
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.chat.effect.EffectLog
import com.chat.effect.EffectResource
import com.chat.effect.IEffectPlayer
import com.chat.effect.PlayCallback
import java.io.File

/**
 * GIF 特效播放器实现，基于 Glide 5.0.5。
 */
class GifEffectPlayer : IEffectPlayer {

    private companion object {
        const val TAG = "GifEffectPlayer"
    }

    private var stage: ViewGroup? = null
    private var view: ImageView? = null
    private var endCallback: Animatable2Compat.AnimationCallback? = null
    private var gifDrawable: GifDrawable? = null

    override fun attach(stage: ViewGroup) {
        if (this.stage === stage && view != null) {
            view?.visibility = View.VISIBLE
            return
        }
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        this.stage = stage
        view = ImageView(stage.context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = false
            isFocusable = false
        }
        stage.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun play(localPath: String, resource: EffectResource, callback: PlayCallback) {
        val v = view ?: return callback.onError("gif view not attached")
        val file = File(localPath)
        if (!file.exists() || file.length() == 0L) {
            return callback.onError("gif file not exists: $localPath")
        }
        Glide.with(v.context)
            .asGif()
            .load(file)
            .listener(object : RequestListener<GifDrawable> {
                override fun onResourceReady(
                    resourceReady: GifDrawable,
                    model: Any,
                    target: Target<GifDrawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    if (view !== v) return false
                    gifDrawable = resourceReady
                    resourceReady.setLoopCount(1)
                    val cb = object : Animatable2Compat.AnimationCallback() {
                        override fun onAnimationEnd(drawable: Drawable) {
                            callback.onComplete()
                        }
                    }
                    endCallback = cb
                    resourceReady.registerAnimationCallback(cb)
                    return false
                }

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<GifDrawable>,
                    isFirstResource: Boolean,
                ): Boolean {
                    EffectLog.e(TAG, e) { "gif load failed url=${resource.url}" }
                    callback.onError(e?.message ?: "gif load failed")
                    return true
                }
            })
            .into(v)
    }

    override fun release() {
        endCallback?.let { cb -> gifDrawable?.unregisterAnimationCallback(cb) }
        gifDrawable?.stop()
        view?.let { v ->
            Glide.with(v.context).clear(v)
            v.setImageDrawable(null)
            v.visibility = View.GONE
        }
        endCallback = null
        gifDrawable = null
    }
}
