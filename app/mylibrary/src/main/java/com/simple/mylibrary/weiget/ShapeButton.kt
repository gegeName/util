package com.simple.mylibrary.weiget

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import com.simple.mylibrary.R
import com.simple.mylibrary.weiget.builder.ShadowDrawableBuilder
import com.simple.mylibrary.weiget.builder.ShapeDrawableBuilder

class ShapeButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatButton(context, attrs, defStyleAttr) {

    val shapeDrawableBuilder: ShapeDrawableBuilder
    val shadowDrawableBuilder: ShadowDrawableBuilder

    init {
        val shapeTa = context.obtainStyledAttributes(attrs, R.styleable.ShapeWidget)
        shapeDrawableBuilder = ShapeDrawableBuilder(this, shapeTa, R.styleable.ShapeWidget)
        shapeTa.recycle()

        val shadowTa = context.obtainStyledAttributes(attrs, R.styleable.ShadowWidget)
        shadowDrawableBuilder = ShadowDrawableBuilder(
            this, shadowTa, R.styleable.ShadowWidget
        ) { shapeDrawableBuilder.radius }
        shadowTa.recycle()

        shapeDrawableBuilder.intoBackground()
        shadowDrawableBuilder.intoShadow()

        /**
         * Material/Material3 主题下，Button 会自带两层会覆盖 StateListDrawable 切换效果的东西：
         *
         * 1. foreground 上的 ripple drawable —— 按下时盖一层水波动画，把背景色的变化遮住。
         * 2. stateListAnimator 上的「按下抬升 translationZ」动画 —— 视觉上让人误以为
         *    只动了 elevation 而背景没动。
         *
         * 这里把它们一并清掉，让用户在 XML 里配的 solidPressedColor / solidSelectedColor
         * 等状态色真正显示出来。
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            foreground = null
        }
        stateListAnimator = null

        /**
         * 触发一次 drawable state 计算，让刚刚装上的 StateListDrawable
         * 立刻对照当前 view 的 drawableState 选中默认那条 item，
         * 避免首帧停留在 StateListDrawable 内部默认（index=0 = state_pressed）的项。
         */
        refreshDrawableState()
    }
}
