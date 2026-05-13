package com.simple.mylibrary.weiget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import com.simple.mylibrary.R
import com.simple.mylibrary.weiget.builder.ShapeDrawableBuilder

class ShapeImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    val shapeDrawableBuilder: ShapeDrawableBuilder

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.ShapeWidget)
        shapeDrawableBuilder = ShapeDrawableBuilder(this, ta, R.styleable.ShapeWidget)
        ta.recycle()
        shapeDrawableBuilder.intoBackground()
    }
}
