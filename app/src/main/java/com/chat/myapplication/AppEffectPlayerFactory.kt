package com.chat.myapplication

import com.chat.effect.EffectType
import com.chat.effect.IEffectPlayer
import com.chat.effect.IEffectPlayerFactory
import com.chat.effect.gif.glide.GifEffectPlayer
import com.chat.effect.mp4.vap.VapMp4EffectPlayer
import com.chat.effect.svga.SvgaEffectPlayer
import com.common.utils.effect.EffectChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * 业务侧特效播放器工厂。给 [com.common.utils.effect.EffectManager.init] 注入。
 *
 * **按 (type, channel) 缓存 Player 实例**:
 * - 同类型不同通道并发播放时,各通道需要独立的 Player 实例(view 不能共用)
 * - 同通道同类型连续播放复用 Player 实例(Player 内部又会复用 view)
 *
 * 例:`(SVGA, DEFAULT)` 和 `(SVGA, VIP)` 是两个独立 Player,各自挂自己的 SVGAImageView 到 stage。
 */
class AppEffectPlayerFactory : IEffectPlayerFactory {
    private val cache = ConcurrentHashMap<Pair<EffectType, EffectChannel>, IEffectPlayer>()

    override fun create(type: EffectType, channel: EffectChannel): IEffectPlayer =
        cache.getOrPut(type to channel) {
            when (type) {
                EffectType.SVGA -> SvgaEffectPlayer()
                EffectType.MP4 -> VapMp4EffectPlayer()
                EffectType.GIF -> GifEffectPlayer()
                else -> throw IllegalArgumentException(
                    "no player registered for type=${type.key} channel=${channel.key}"
                )
            }
        }
}