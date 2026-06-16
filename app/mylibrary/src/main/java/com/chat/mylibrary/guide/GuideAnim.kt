package com.chat.mylibrary.guide

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View

sealed class GuideAnim {
    object None : GuideAnim()
    object Fade : GuideAnim()
    object Scale : GuideAnim()
    object FadeScale : GuideAnim()

    /** 完全自定义入场/出场 Animator。 */
    class Custom(
        val enter: (View) -> Animator,
        val exit: (View) -> Animator,
    ) : GuideAnim()

    internal fun buildEnter(view: View): Animator? = when (this) {
        None -> null
        Fade -> ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f).setDuration(ENTER_DURATION)
        Scale -> AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.6f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.6f, 1f),
            )
            duration = ENTER_DURATION
        }
        FadeScale -> AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, 0.6f, 1f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, 0.6f, 1f),
            )
            duration = ENTER_DURATION
        }
        is Custom -> enter.invoke(view)
    }

    internal fun buildExit(view: View): Animator? = when (this) {
        None -> null
        Fade -> ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f).setDuration(EXIT_DURATION)
        Scale -> AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, 0.6f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, 0.6f),
            )
            duration = EXIT_DURATION
        }
        FadeScale -> AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.ALPHA, view.alpha, 0f),
                ObjectAnimator.ofFloat(view, View.SCALE_X, view.scaleX, 0.6f),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, view.scaleY, 0.6f),
            )
            duration = EXIT_DURATION
        }
        is Custom -> exit.invoke(view)
    }

    private companion object {
        const val ENTER_DURATION = 250L
        const val EXIT_DURATION = 180L
    }
}
