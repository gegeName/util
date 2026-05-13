package com.simple.mylibrary.weiget

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.simple.mylibrary.R
import com.simple.mylibrary.weiget.builder.ShapeDrawableBuilder

class ShapeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val shapeDrawableBuilder: ShapeDrawableBuilder

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.ShapeWidget)
        shapeDrawableBuilder = ShapeDrawableBuilder(this, ta, R.styleable.ShapeWidget)
        ta.recycle()
        shapeDrawableBuilder.intoBackground()
    }
}
