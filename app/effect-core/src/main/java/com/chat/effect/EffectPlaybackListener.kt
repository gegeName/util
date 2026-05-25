package com.chat.effect


/**
 * 播放事件监听器。所有回调都在 **Main 线程**，可直接更新 UI。
 */
interface EffectPlaybackListener {
    fun onStart(resource: EffectResource) {}

    fun onComplete(resource: EffectResource) {}

    fun onError(resource: EffectResource, reason: String) {}

    fun onQueueFinished() {}
}
