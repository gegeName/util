package com.lhj.effect


/**
 * 播放事件监听器。所有回调都在 **Main 线程**，可直接更新 UI。
 */
interface EffectPlaybackListener {
    /** 一条资源开始播放（已下载完成、player 已 attach）。 */
    fun onStart(resource: EffectResource) {}

    /** 一条资源播放完成。 */
    fun onComplete(resource: EffectResource) {}

    /**
     * 一条资源出错。reason 来自 player 的 [PlayCallback.onError]，
     * 或下载失败时的 `"fetch failed"`。
     */
    fun onError(resource: EffectResource, reason: String) {}

    /** 队列消耗完，没有更多待播资源。下一次 enqueue 会重新触发 [onStart]。 */
    fun onQueueFinished() {}
}
