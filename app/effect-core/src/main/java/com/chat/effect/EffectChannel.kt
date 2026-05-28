package com.common.utils.effect

/**
 * 播放通道。**多通道并发播放**用,同一 stage 上可以有多个通道同时在播,
 * 各通道有独立的队列与播放槽,互不阻塞。
 *
 * 不传 channel 的入队默认走 [DEFAULT],行为与单通道时代完全一致。
 *
 * 业务自定义通道:
 * ```kotlin
 * object VipChannel : EffectChannel(key = "vip")
 * object GiftChannel : EffectChannel(key = "gift")
 *
 * EffectManager.enqueue(vipUrl, channel = VipChannel, priority = HIGH)
 * EffectManager.enqueue(giftUrl, channel = GiftChannel)
 * // 上面两条会"同屏"并行播,而不是 VIP 等礼物播完
 * ```
 *
 * @param key 通道唯一标识,用于内部状态映射、Factory 缓存键、日志
 */
open class EffectChannel(val key: String) {

    /** 默认通道。不传 channel 参数的 enqueue 走这条。 */
    object DEFAULT : EffectChannel(key = "default")

    override fun equals(other: Any?): Boolean = other is EffectChannel && other.key == key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "EffectChannel($key)"
}
