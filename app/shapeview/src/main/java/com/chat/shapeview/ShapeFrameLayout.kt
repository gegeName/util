package com.chat.shapeview

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import com.chat.shapeview.builder.ShadowDrawableBuilder
import com.chat.shapeview.builder.ShapeDrawableBuilder

class ShapeFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

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
    }
}
