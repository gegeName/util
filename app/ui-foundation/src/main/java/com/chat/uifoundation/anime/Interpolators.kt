package com.chat.uifoundation.anime

import android.animation.TimeInterpolator
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.AnticipateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * 常用 [TimeInterpolator] 预设集合。
 *
 * 用法：
 * ```
 * Anime.on(view).translateX(200f).interpolator(Interpolators.OVERSHOOT).start()
 * Anime.on(view).scale(1.2f).interpolator(Interpolators.bounce()).start()
 * Anime.on(view).alpha(1f).interpolator(Interpolators.cubic(.25f,.1f,.25f,1f))
 * ```
 *
 * 常量都是无状态单例，可放心跨动画复用。带参数的工厂每次返回新实例。
 */
object Interpolators {

    /** 匀速 */
    val LINEAR: TimeInterpolator = LinearInterpolator()

    /** 加速：慢→快 */
    val ACCELERATE: TimeInterpolator = AccelerateInterpolator()

    /** 减速：快→慢，UI 出现/进入常用 */
    val DECELERATE: TimeInterpolator = DecelerateInterpolator()

    /** 加速-减速：两端慢中间快，Material 默认 */
    val STANDARD: TimeInterpolator = AccelerateDecelerateInterpolator()

    /** 末段回弹，类似掉落 */
    val BOUNCE: TimeInterpolator = BounceInterpolator()

    /** 过冲：超出目标后回到目标（默认 tension=2） */
    val OVERSHOOT: TimeInterpolator = OvershootInterpolator()

    /** 蓄力：先反向退一下再加速到目标 */
    val ANTICIPATE: TimeInterpolator = AnticipateInterpolator()

    /** 蓄力 + 过冲：先退后冲再回弹 */
    val ANTICIPATE_OVERSHOOT: TimeInterpolator = AnticipateOvershootInterpolator()

    /** 自定义 OVERSHOOT 强度。tension 越大回弹越夸张，默认是 2f */
    fun overshoot(tension: Float): TimeInterpolator = OvershootInterpolator(tension)

    /** 自定义 ANTICIPATE 强度 */
    fun anticipate(tension: Float): TimeInterpolator = AnticipateInterpolator(tension)

    /** CSS cubic-bezier 等价：两个控制点 (x1,y1) (x2,y2)，分量取值 0..1 */
    fun cubic(x1: Float, y1: Float, x2: Float, y2: Float): TimeInterpolator =
        PathInterpolator(x1, y1, x2, y2)

    /** 同 BOUNCE，仅为风格统一提供函数化别名 */
    fun bounce(): TimeInterpolator = BOUNCE
}
