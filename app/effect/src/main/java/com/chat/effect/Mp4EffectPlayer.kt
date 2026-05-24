package com.chat.effect


import android.view.ViewGroup
import com.tencent.qgame.animplayer.AnimConfig
import com.tencent.qgame.animplayer.AnimView
import com.tencent.qgame.animplayer.inter.IAnimListener
import com.tencent.qgame.animplayer.util.ScaleType
import java.io.File

/**
 * MP4 (alpha 通道) 特效播放器实现，基于腾讯 VAP（[com.tencent.qgame.animplayer.AnimView]）。
 *
 * VAP 工作流：把 MP4 视频左右两半中的右半作为 alpha mask，运行期合成出带透明通道的特效，
 * 适合礼物 / 入场动画等场景，相比 SVGA 资源更小、表现力更强。
 */
class Mp4EffectPlayer : IEffectPlayer {

    private companion object {
        const val TAG = "Mp4EffectPlayer"
    }

    private var stage: ViewGroup? = null
    private var view: AnimView? = null

    override fun attach(stage: ViewGroup) {
        if (this.stage === stage && view != null) {
            view?.visibility = android.view.View.VISIBLE
            return
        }
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        this.stage = stage
        view = AnimView(stage.context).apply {
            isClickable = false
            isFocusable = false
            setScaleType(ScaleType.CENTER_CROP)
            setLoop(1)
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
        val v = view ?: return callback.onError("vap view not attached")
        val file = File(localPath)
        if (!file.exists() || file.length() == 0L) {
            return callback.onError("mp4 file not exists: $localPath")
        }
        v.setAnimListener(object : IAnimListener {
            override fun onVideoConfigReady(config: AnimConfig): Boolean = true

            override fun onVideoStart() {}

            override fun onVideoRender(frameIndex: Int, config: AnimConfig?) {}

            override fun onVideoComplete() {
                callback.onComplete()
            }

            override fun onFailed(errorType: Int, errorMsg: String?) {
                EffectLog.e(TAG) { "mp4 play failed url=${resource.url} code=$errorType msg=$errorMsg" }
                callback.onError("vap $errorType:${errorMsg.orEmpty()}")
            }

            override fun onVideoDestroy() {}
        })
        v.startPlay(file)
    }

    override fun release() {
        view?.let { v ->
            v.stopPlay()
            v.visibility = android.view.View.GONE
        }
    }
}
