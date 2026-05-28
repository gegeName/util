package com.chat.effect

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 全屏特效宿主容器。
 *
 * 布局建议：放在 Activity 根布局最上层，铺满父布局：
 * ```xml
 * <com.common.utils.effect.EffectStageView
 *     android:id="@+id/effectStage"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 *
 * 关键特性：所有触摸事件透传，不拦截、不消费，UI 层正常响应。播放器添加的子 view
 * 也应保持 clickable=false，否则会"吃掉"它覆盖区域的点击。
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
