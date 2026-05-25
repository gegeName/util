package com.hifylive.myapplication

import com.chat.effect.EffectType
import com.chat.effect.IEffectPlayer
import com.chat.effect.IEffectPlayerFactory
import com.chat.effect.gif.glide.GifEffectPlayer
import com.chat.effect.mp4.vap.VapMp4EffectPlayer
import com.chat.effect.svga.SvgaEffectPlayer

/**
 * 业务侧特效播放器工厂示例。给 [com.chat.effect.EffectManager.init] 注入。
 *
 * 拆分后框架本身不再带任何 player 实现，业务方按引入了哪些子模块，自己组装 when 分支。
 * 这里只是 demo：同时引入了 effect-svga / effect-mp4-vap / effect-gif-glide 三个 lib。
 *
 * 只用其中一种时，删掉对应分支与 import 即可。比如只用 SVGA：
 * ```
 * class MyFactory : IEffectPlayerFactory {
 *     private val svga by lazy { SvgaEffectPlayer() }
 *     override fun create(type: EffectType) = when (type) {
 *         EffectType.SVGA -> svga
 *         else -> throw IllegalStateException("unsupported $type")
 *     }
 * }
 * ```
 */
class AppEffectPlayerFactory : IEffectPlayerFactory {

    private val svga by lazy { SvgaEffectPlayer() }
    private val mp4 by lazy { VapMp4EffectPlayer() }
    private val gif by lazy { GifEffectPlayer() }

    override fun create(type: EffectType): IEffectPlayer = when (type) {
        EffectType.SVGA -> svga
        EffectType.MP4 -> mp4
        EffectType.GIF -> gif
        else -> throw IllegalArgumentException("no player registered for type=${type.key}")
    }
}
