package com.lhj.shapeview

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import com.lhj.shapeview.builder.ShadowDrawableBuilder
import com.lhj.shapeview.builder.ShapeDrawableBuilder

class ShapeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    val shapeDrawableBuilder: ShapeDrawableBuilder
    val shadowDrawableBuilder: ShadowDrawableBuilder

    init {
        ShapePaddingManager.applyDefaultZeroPadding(this, context, attrs)

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
