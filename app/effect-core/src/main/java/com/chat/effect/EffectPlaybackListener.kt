package com.chat.effect

import com.common.utils.effect.EffectChannel

/**
 * 播放事件监听器。所有回调都在 **Main 线程**，可直接更新 UI。
 */
interface EffectPlaybackListener {
    /** 一条资源开始播放（已下载完成、player 已 attach）。`resource.channel` 表示来自哪个通道。 */
    fun onStart(resource: EffectResource) {}

    /** 一条资源播放完成。 */
    fun onComplete(resource: EffectResource) {}

    /**
     * 某个通道队列消耗完(该通道本轮无更多待播)。多通道场景下每个通道空时各自触发一次。
     * 仅当所有通道都空时,才追加触发一次无参的 [onQueueFinished]。
     */
    fun onChannelFinished(channel: EffectChannel) {}

    /**
     * 一条资源出错。reason 来自 player 的 [PlayCallback.onError]，
     * 或下载失败时的 `"fetch failed"`。
     */
    fun onError(resource: EffectResource, reason: String) {}

    /** 队列消耗完，没有更多待播资源。下一次 enqueue 会重新触发 [onStart]。 */
    fun onQueueFinished() {}
}
