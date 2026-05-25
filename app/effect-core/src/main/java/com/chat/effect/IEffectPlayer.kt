package com.chat.effect


import android.view.ViewGroup

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
 * 调用顺序：[attach] → [play] → callback → [release]。
 */
interface IEffectPlayer {

    fun attach(stage: ViewGroup)

    fun play(localPath: String, resource: EffectResource, callback: PlayCallback)

    fun release()
}

/**
 * 播放器工厂，由业务方在 [EffectManager.init] 时注入。
 *
 * 每条资源播放时框架调用 [create] 拿到一个新的 [IEffectPlayer]，
 * 实现可按需复用单例或每次 new，框架不假设。
 */
interface IEffectPlayerFactory {
    fun create(type: EffectType): IEffectPlayer
}
