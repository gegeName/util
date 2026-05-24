package com.chat.shapeview

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.View

/**
 * 处理 ShapeTextView / ShapeEditText 的默认 padding 问题。
 *
 * AppCompatTextView / AppCompatEditText 通过 defStyleAttr（textViewStyle / editTextStyle）
 * 从主题继承了默认上下 padding，导致控件高度固定时文字被遮挡。
 *
 * 策略：用户在 XML 显式设置了 android:padding* 时尊重用户值；
 *       未设置时覆盖为 0，消除主题默认 padding。
 */
internal object ShapePaddingManager {

    private val PADDING_ATTRS = intArrayOf(
        android.R.attr.padding,
        android.R.attr.paddingStart,
        android.R.attr.paddingEnd,
        android.R.attr.paddingTop,
        android.R.attr.paddingBottom,
    )

    @SuppressLint("ResourceType")
    fun applyDefaultZeroPadding(view: View, context: Context, attrs: AttributeSet?) {
        val ta = context.obtainStyledAttributes(attrs, PADDING_ATTRS)
        val allPad     = ta.getDimensionPixelSize(0, -1)
        val userStart  = ta.getDimensionPixelSize(1, -1)
        val userEnd    = ta.getDimensionPixelSize(2, -1)
        val userTop    = ta.getDimensionPixelSize(3, -1)
        val userBottom = ta.getDimensionPixelSize(4, -1)
        ta.recycle()

        val base = if (allPad >= 0) allPad else 0
        view.setPaddingRelative(
            if (userStart  >= 0) userStart  else base,
            if (userTop    >= 0) userTop    else base,
            if (userEnd    >= 0) userEnd    else base,
            if (userBottom >= 0) userBottom else base,
        )
    }
}
