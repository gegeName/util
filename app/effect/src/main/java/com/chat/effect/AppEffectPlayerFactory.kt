package com.chat.effect

/**
 * 业务侧特效播放器工厂。给 [com.chat.effect.EffectManager.init] 注入。
 */
class AppEffectPlayerFactory : IEffectPlayerFactory {

    private val svga by lazy { SvgaEffectPlayer() }
    private val mp4 by lazy { Mp4EffectPlayer() }
    private val gif by lazy { GifEffectPlayer() }

    override fun create(type: EffectType): IEffectPlayer = when (type) {
        EffectType.SVGA -> svga
        EffectType.MP4 -> mp4
        EffectType.GIF -> gif
        else -> throw IllegalArgumentException("no player registered for type=${type.key}")
    }
}
