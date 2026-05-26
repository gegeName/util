package com.chat.mylibrary.floatmsg

import android.view.View

/**
 * 浮屏消息的入/出场动画策略。视图由 [FloatMessageQueue] 动态 addView,
 * 所以实现:
 * - animateIn 内部需触发首次 layout(view.post)再读取尺寸/位置,避免刚 addView 时 width=0
 * - animateOut 完成后回调 onEnd,容器会随后 removeView,无需自己处理可见性
 * - cancel 在 release 时调用,需立即停掉所有正在跑的动画
 */
interface FloatMessageAnimator {
    fun animateIn(view: View, onEnd: () -> Unit)
    fun animateOut(view: View, onEnd: () -> Unit)
    fun cancel(view: View)
}
