package com.simple.mylibrary.floatmsg

import android.view.View

/**
 * 默认浮屏动画:从屏幕左侧飞入,飞回左侧。
 * 飞行距离取 view 在屏幕上的左边缘 + 宽度,保证完全飞出可见区域,
 * 不依赖 view.left(避免父容器有偏移时计算错)。
 *
 * - durationIn: 入场耗时,默认 500ms
 * - durationOut: 出场耗时,默认 500ms
 */
class SlideInLeftAnimator(
    private val durationIn: Long = 500L,
    private val durationOut: Long = 500L
) : FloatMessageAnimator {

    override fun animateIn(view: View, onEnd: () -> Unit) {
        // alpha=0 期间完成测量,避免 view 在自然位置闪一帧
        view.alpha = 0f
        view.translationX = 0f
        view.post {
            val flyDistance = computeFlyDistance(view)
            view.translationX = -flyDistance
            view.alpha = 1f
            view.animate()
                .translationX(0f)
                .setDuration(durationIn)
                .withEndAction(onEnd)
                .start()
        }
    }

    override fun animateOut(view: View, onEnd: () -> Unit) {
        val flyDistance = computeFlyDistance(view)
        view.animate()
            .translationX(-flyDistance)
            .setDuration(durationOut)
            .withEndAction(onEnd)
            .start()
    }

    override fun cancel(view: View) {
        view.animate().cancel()
    }

    private fun computeFlyDistance(view: View): Float {
        val coords = IntArray(2)
        view.getLocationOnScreen(coords)
        // coords 已包含当前 translationX,需要反推自然位置
        val naturalX = coords[0] - view.translationX
        return (naturalX + view.width).coerceAtLeast(1f)
    }
}
