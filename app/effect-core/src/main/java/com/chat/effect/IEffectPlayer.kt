package com.chat.effect

import android.view.ViewGroup
import com.common.utils.effect.EffectChannel

/**
 * 单次播放结果回调。框架在收到任一回调后会进入下一条队列项。
 *
 * 实现方需保证 [onComplete] / [onError] 至少且仅触发一次。
 */
interface PlayCallback {
    fun onComplete()
    fun onError(reason: String)
}

/**
 * 特效播放器契约。每条资源播放期间存在一个实例，播完即 [release]。
 *
 * 典型实现（SVGA 版示意）：
 * ```
 * class SvgaEffectPlayer : IEffectPlayer {
 *     private var view: SVGAImageView? = null
 *     private var stage: ViewGroup? = null
 *
 *     override fun attach(stage: ViewGroup) {
 *         this.stage = stage
 *         view = SVGAImageView(stage.context).also {
 *             it.loops = 1
 *             stage.addView(it, MATCH_PARENT, MATCH_PARENT)
 *         }
 *     }
 *
 *     override fun play(localPath: String, resource: EffectResource, callback: PlayCallback) {
 *         // 解析本地 svga 并启动，结束回调 callback.onComplete()
 *     }
 *
 *     override fun release() {
 *         view?.stopAnimation(true)
 *         stage?.removeView(view)
 *         view = null
 *         stage = null
 *     }
 * }
 * ```
 *
 * 调用顺序：[attach] → [play] → callback → [release]。
 */
interface IEffectPlayer {

    /**
     * 把播放视图挂到 stage 上。stage 已是全屏容器，子 view 推荐 match_parent。
     */
    fun attach(stage: ViewGroup)

    /**
     * 开始播放本地文件。完成或失败时必须回调 [callback]。
     *
     * @param localPath 已下载到本地的资源路径，文件已存在
     * @param resource 原始资源信息，含 [EffectResource.extras] 等业务透传字段
     */
    fun play(localPath: String, resource: EffectResource, callback: PlayCallback)

    /**
     * 停止播放、移除视图、释放资源。框架在播放结束、出错、强制中断时调用。
     */
    fun release()
}

/**
 * 播放器工厂，由业务方在 [EffectManager.init] 时注入。
 *
 * 每条资源播放时框架调用 [create] 拿到一个 [IEffectPlayer]。
 * 多通道并发播放时,**同类型不同 channel 不能共用同一个 Player 实例**(view 会冲突),
 * 因此 [create] 接收 channel 参数,实现按 (type, channel) 缓存。
 *
 * 实现可按需复用单例或每次 new，框架不假设。
 */
interface IEffectPlayerFactory {
    /**
     * @param type 资源类型,决定走哪个 Player 实现
     * @param channel 播放通道,实现应按 (type, channel) 缓存,确保跨通道并发不冲突
     */
    fun create(type: EffectType, channel: EffectChannel): IEffectPlayer
}
