package com.chat.effect


import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 全屏特效宿主容器。
 */
class EffectStageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false

    override fun onTouchEvent(event: MotionEvent?): Boolean = false
}
