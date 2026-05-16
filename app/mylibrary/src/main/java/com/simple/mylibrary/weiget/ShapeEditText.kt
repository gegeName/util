package com.simple.mylibrary.weiget

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatEditText
import com.simple.mylibrary.R
import com.simple.mylibrary.weiget.builder.ShadowDrawableBuilder
import com.simple.mylibrary.weiget.builder.ShapeDrawableBuilder

class ShapeEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    val shapeDrawableBuilder: ShapeDrawableBuilder
    val shadowDrawableBuilder: ShadowDrawableBuilder

    init {
        // 必须先把 padding 归零（覆盖主题默认 padding），
        // ShadowDrawableBuilder 构造时会读取 view.paddingXxx 作为 basePadding，
        // 若此时 padding 未归零，阴影叠加后会把内容往里推，表现为阴影显示在内部。
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
