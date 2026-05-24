package com.lhj.effect


import android.view.ViewGroup
import android.widget.ImageView
import com.opensource.svgaplayer.SVGACallback
import com.opensource.svgaplayer.SVGADrawable
import com.opensource.svgaplayer.SVGAImageView
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import java.io.File
import java.io.FileInputStream

/**
 * SVGA 特效播放器实现。
 */
class SvgaEffectPlayer : IEffectPlayer {

    private companion object {
        const val TAG = "SvgaEffectPlayer"
    }

    private var stage: ViewGroup? = null
    private var view: SVGAImageView? = null
    private val parser by lazy {
        val ctx = EffectIO.appContext() ?: error("EffectManager.init(context) 未调用")
        SVGAParser(ctx)
    }

    override fun attach(stage: ViewGroup) {
        if (this.stage === stage && view != null) {
            view?.visibility = android.view.View.VISIBLE
            return
        }
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        this.stage = stage
        view = SVGAImageView(stage.context).apply {
            loops = 1
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
        val v = view ?: return callback.onError("svga view not attached")
        val file = File(localPath)
        if (!file.exists() || file.length() == 0L) {
            return callback.onError("svga file not exists: $localPath")
        }
        try {
            val inputStream = FileInputStream(file)
            parser.decodeFromInputStream(
                inputStream,
                resource.url,
                object : SVGAParser.ParseCompletion {
                    override fun onComplete(videoItem: SVGAVideoEntity) {
                        if (view !== v) {
                            return
                        }
                        v.setImageDrawable(SVGADrawable(videoItem))
                        v.callback = object : SVGACallback {
                            override fun onFinished() = callback.onComplete()
                            override fun onPause() {}
                            override fun onRepeat() {}
                            override fun onStep(frame: Int, percentage: Double) {}
                        }
                        v.startAnimation()
                    }

                    override fun onError() {
                        EffectLog.e(TAG) { "svga parse error url=${resource.url}" }
                        callback.onError("svga parse error")
                    }
                },
                true,
                null,
            )
        } catch (e: Exception) {
            EffectLog.e(TAG, e) { "svga play exception url=${resource.url}" }
            callback.onError(e.message ?: "svga play exception")
        }
    }

    override fun release() {
        view?.let { v ->
            v.stopAnimation(true)
            v.callback = null
            v.setImageDrawable(null)
            v.visibility = android.view.View.GONE
        }
    }
}
