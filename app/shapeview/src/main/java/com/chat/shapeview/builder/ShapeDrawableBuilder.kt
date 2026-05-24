package com.chat.shapeview.builder

import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.core.view.ViewCompat
import com.chat.shapeview.R

/**
 * 通用 Shape Drawable 构建器
 *
 * 用法：
 * ```
 * val ta = context.obtainStyledAttributes(attrs, R.styleable.ShapeWidget)
 * mShapeBuilder = ShapeDrawableBuilder(this, ta, R.styleable.ShapeWidget)
 * ta.recycle()
 * mShapeBuilder.intoBackground()
 * ```
 */
class ShapeDrawableBuilder(
    private val view: View,
    ta: TypedArray,
    private val styleable: IntArray
) {

    private var shape: Int = GradientDrawable.RECTANGLE
    private var width: Int = -1
    private var height: Int = -1

    private var solidColor: Int = 0
    private var solidPressedColor: Int = 0
    private var solidCheckedColor: Int = 0
    private var solidDisabledColor: Int = 0
    private var solidFocusedColor: Int = 0
    private var solidSelectedColor: Int = 0

    private var solidGradientStartColor: Int = 0
    private var solidGradientCenterColor: Int = 0
    private var solidGradientEndColor: Int = 0
    private var solidGradientOrientation: Int = 0
    private var solidGradientType: Int = 0
    private var solidGradientRadius: Float = 0f
    private var solidGradientCenterX: Float = 0.5f
    private var solidGradientCenterY: Float = 0.5f
    private var solidGradientUseLevel: Boolean = false

    var radius: Float = 0f
        private set
    private var topLeftRadius: Float = -1f
    private var topRightRadius: Float = -1f
    private var bottomLeftRadius: Float = -1f
    private var bottomRightRadius: Float = -1f

    private var strokeColor: Int = 0
    private var strokePressedColor: Int = 0
    private var strokeCheckedColor: Int = 0
    private var strokeDisabledColor: Int = 0
    private var strokeFocusedColor: Int = 0
    private var strokeSelectedColor: Int = 0
    private var strokeSize: Float = 0f
    private var strokeDashSize: Float = 0f
    private var strokeDashGap: Float = 0f

    private var strokeGradientStartColor: Int = 0
    private var strokeGradientCenterColor: Int = 0
    private var strokeGradientEndColor: Int = 0
    private var strokeGradientOrientation: Int = 0

    private var innerRadius: Int = -1
    private var innerRadiusRatio: Float = 3f
    private var thickness: Int = -1
    private var thicknessRatio: Float = 9f
    private var ringUseLevel: Boolean = false

    private var lineGravity: Int = 0x11

    init {
        readAttr(ta, R.attr.shape_L) { shape = ta.getInt(it, GradientDrawable.RECTANGLE) }
        readAttr(ta, R.attr.shape_width_L) { width = ta.getDimensionPixelSize(it, -1) }
        readAttr(ta, R.attr.shape_height_L) { height = ta.getDimensionPixelSize(it, -1) }

        readAttr(ta, R.attr.shape_solidColor_L) { solidColor = ta.getColor(it, 0) }
        readAttr(ta, R.attr.shape_solidPressedColor_L) {
            solidPressedColor = ta.getColor(it, solidColor)
        }
        readAttr(ta, R.attr.shape_solidCheckedColor_L) {
            solidCheckedColor = ta.getColor(it, solidColor)
        }
        readAttr(ta, R.attr.shape_solidDisabledColor_L) {
            solidDisabledColor = ta.getColor(it, solidColor)
        }
        readAttr(ta, R.attr.shape_solidFocusedColor_L) {
            solidFocusedColor = ta.getColor(it, solidColor)
        }
        readAttr(ta, R.attr.shape_solidSelectedColor_L) {
            solidSelectedColor = ta.getColor(it, solidColor)
        }
        if (solidPressedColor == 0) solidPressedColor = solidColor
        if (solidCheckedColor == 0) solidCheckedColor = solidColor
        if (solidDisabledColor == 0) solidDisabledColor = solidColor
        if (solidFocusedColor == 0) solidFocusedColor = solidColor
        if (solidSelectedColor == 0) solidSelectedColor = solidColor

        readAttr(ta, R.attr.shape_solidGradientStartColor_L) {
            solidGradientStartColor = ta.getColor(it, 0)
        }
        readAttr(ta, R.attr.shape_solidGradientCenterColor_L) {
            solidGradientCenterColor = ta.getColor(it, 0)
        }
        readAttr(ta, R.attr.shape_solidGradientEndColor_L) {
            solidGradientEndColor = ta.getColor(it, 0)
        }
        readAttr(ta, R.attr.shape_solidGradientOrientation_L) {
            solidGradientOrientation = ta.getInt(it, 0)
        }
        readAttr(ta, R.attr.shape_solidGradientType_L) { solidGradientType = ta.getInt(it, 0) }
        readAttr(ta, R.attr.shape_solidGradientRadius_L) {
            solidGradientRadius = ta.getDimension(it, 0f)
        }
        readAttr(ta, R.attr.shape_solidGradientCenterX_L) {
            solidGradientCenterX = ta.getFloat(it, 0.5f)
        }
        readAttr(ta, R.attr.shape_solidGradientCenterY_L) {
            solidGradientCenterY = ta.getFloat(it, 0.5f)
        }
        readAttr(ta, R.attr.shape_solidGradientUseLevel_L) {
            solidGradientUseLevel = ta.getBoolean(it, false)
        }

        readAttr(ta, R.attr.shape_radius_L) { radius = ta.getDimension(it, 0f) }
        readAttr(ta, R.attr.shape_topLeftRadius_L) { topLeftRadius = ta.getDimension(it, -1f) }
        readAttr(ta, R.attr.shape_topRightRadius_L) { topRightRadius = ta.getDimension(it, -1f) }
        readAttr(ta, R.attr.shape_bottomLeftRadius_L) {
            bottomLeftRadius = ta.getDimension(it, -1f)
        }
        readAttr(ta, R.attr.shape_bottomRightRadius_L) {
            bottomRightRadius = ta.getDimension(it, -1f)
        }

        readAttr(ta, R.attr.shape_strokeColor_L) { strokeColor = ta.getColor(it, 0) }
        readAttr(ta, R.attr.shape_strokePressedColor_L) {
            strokePressedColor = ta.getColor(it, strokeColor)
        }
        readAttr(ta, R.attr.shape_strokeCheckedColor_L) {
            strokeCheckedColor = ta.getColor(it, strokeColor)
        }
        readAttr(ta, R.attr.shape_strokeDisabledColor_L) {
            strokeDisabledColor = ta.getColor(it, strokeColor)
        }
        readAttr(ta, R.attr.shape_strokeFocusedColor_L) {
            strokeFocusedColor = ta.getColor(it, strokeColor)
        }
        readAttr(ta, R.attr.shape_strokeSelectedColor_L) {
            strokeSelectedColor = ta.getColor(it, strokeColor)
        }
        if (strokePressedColor == 0) strokePressedColor = strokeColor
        if (strokeCheckedColor == 0) strokeCheckedColor = strokeColor
        if (strokeDisabledColor == 0) strokeDisabledColor = strokeColor
        if (strokeFocusedColor == 0) strokeFocusedColor = strokeColor
        if (strokeSelectedColor == 0) strokeSelectedColor = strokeColor
        readAttr(ta, R.attr.shape_strokeSize_L) { strokeSize = ta.getDimension(it, 0f) }
        readAttr(ta, R.attr.shape_strokeDashSize_L) { strokeDashSize = ta.getDimension(it, 0f) }
        readAttr(ta, R.attr.shape_strokeDashGap_L) { strokeDashGap = ta.getDimension(it, 0f) }

        readAttr(ta, R.attr.shape_strokeGradientStartColor_L) {
            strokeGradientStartColor = ta.getColor(it, 0)
        }
        readAttr(ta, R.attr.shape_strokeGradientCenterColor_L) {
            strokeGradientCenterColor = ta.getColor(it, 0)
        }
        readAttr(ta, R.attr.shape_strokeGradientEndColor_L) {
            strokeGradientEndColor = ta.getColor(it, 0)
        }
        readAttr(ta, R.attr.shape_strokeGradientOrientation_L) {
            strokeGradientOrientation = ta.getInt(it, 0)
        }

        readAttr(ta, R.attr.shape_innerRadius_L) { innerRadius = ta.getDimensionPixelSize(it, -1) }
        readAttr(ta, R.attr.shape_innerRadiusRatio_L) { innerRadiusRatio = ta.getFloat(it, 3f) }
        readAttr(ta, R.attr.shape_thickness_L) { thickness = ta.getDimensionPixelSize(it, -1) }
        readAttr(ta, R.attr.shape_thicknessRatio_L) { thicknessRatio = ta.getFloat(it, 9f) }
        readAttr(ta, R.attr.shape_useLevel_L) { ringUseLevel = ta.getBoolean(it, false) }

        readAttr(ta, R.attr.shape_lineGravity_L) { lineGravity = ta.getInt(it, 0x11) }
    }

    private inline fun readAttr(ta: TypedArray, attrResId: Int, action: (Int) -> Unit) {
        val index = findAttrIndex(attrResId)
        if (index >= 0 && ta.hasValue(index)) action(index)
    }

    private fun findAttrIndex(attrResId: Int): Int {
        for (i in styleable.indices) {
            if (styleable[i] == attrResId) return i
        }
        return -1
    }

    /** 构造并应用为 View 背景 */
    fun intoBackground() {
        view.background = buildDrawable()
        ViewCompat.setBackgroundTintList(view, null)
        if (strokeSize > 0 && strokeDashSize > 0 && !view.isInEditMode) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }

    private fun buildDrawable(): Drawable {
        val hasStateColor = solidColor != solidPressedColor ||
                solidColor != solidCheckedColor ||
                solidColor != solidSelectedColor ||
                solidColor != solidFocusedColor ||
                solidColor != solidDisabledColor ||
                strokeColor != strokePressedColor ||
                strokeColor != strokeCheckedColor ||
                strokeColor != strokeSelectedColor ||
                strokeColor != strokeFocusedColor ||
                strokeColor != strokeDisabledColor

        if (!hasStateColor) return buildSingle(solidColor, strokeColor)

        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                buildSingle(solidPressedColor, strokePressedColor)
            )
            addState(
                intArrayOf(android.R.attr.state_checked),
                buildSingle(solidCheckedColor, strokeCheckedColor)
            )
            addState(
                intArrayOf(android.R.attr.state_selected),
                buildSingle(solidSelectedColor, strokeSelectedColor)
            )
            addState(
                intArrayOf(android.R.attr.state_focused),
                buildSingle(solidFocusedColor, strokeFocusedColor)
            )
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                buildSingle(solidDisabledColor, strokeDisabledColor)
            )
            addState(intArrayOf(), buildSingle(solidColor, strokeColor))
        }
    }

    private fun buildSingle(solid: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = this@ShapeDrawableBuilder.shape.coerceAtLeast(GradientDrawable.RECTANGLE)

            if (this@ShapeDrawableBuilder.width > 0 || this@ShapeDrawableBuilder.height > 0) {
                setSize(this@ShapeDrawableBuilder.width, this@ShapeDrawableBuilder.height)
            }

            if (topLeftRadius >= 0 || topRightRadius >= 0 ||
                bottomLeftRadius >= 0 || bottomRightRadius >= 0
            ) {
                val tl = if (topLeftRadius >= 0) topLeftRadius else radius
                val tr = if (topRightRadius >= 0) topRightRadius else radius
                val br = if (bottomRightRadius >= 0) bottomRightRadius else radius
                val bl = if (bottomLeftRadius >= 0) bottomLeftRadius else radius
                cornerRadii = floatArrayOf(tl, tl, tr, tr, br, br, bl, bl)
            } else {
                cornerRadius = radius
            }

            if (solidGradientStartColor != 0 && solidGradientEndColor != 0) {
                gradientType = solidGradientType
                orientation = mapOrientation(solidGradientOrientation)
                colors = if (solidGradientCenterColor != 0)
                    intArrayOf(
                        solidGradientStartColor,
                        solidGradientCenterColor,
                        solidGradientEndColor
                    )
                else
                    intArrayOf(solidGradientStartColor, solidGradientEndColor)
                if (gradientType == GradientDrawable.RADIAL_GRADIENT) {
                    gradientRadius = if (solidGradientRadius > 0) solidGradientRadius else 100f
                }
                setGradientCenter(solidGradientCenterX, solidGradientCenterY)
                useLevel = solidGradientUseLevel
            } else {
                setColor(solid)
            }

            if (strokeSize > 0) {
                val finalStrokeColor =
                    if (strokeGradientStartColor != 0 && strokeGradientEndColor != 0) {
                        strokeGradientStartColor
                    } else {
                        stroke
                    }
                setStroke(strokeSize.toInt(), finalStrokeColor, strokeDashSize, strokeDashGap)
            }

            if (this@ShapeDrawableBuilder.shape == GradientDrawable.RING) {
                applyRingAttrs(this)
            }
        }
    }

    private fun applyRingAttrs(drawable: GradientDrawable) {
        runCatching {
            val stateField =
                drawable.javaClass.getDeclaredField("mGradientState").apply { isAccessible = true }
            val state = stateField.get(drawable)
            val cls = state.javaClass
            if (innerRadius >= 0) {
                cls.getDeclaredField("mInnerRadius").apply { isAccessible = true }
                    .setInt(state, innerRadius)
            }
            cls.getDeclaredField("mInnerRadiusRatio").apply { isAccessible = true }
                .setFloat(state, innerRadiusRatio)
            if (thickness >= 0) {
                cls.getDeclaredField("mThickness").apply { isAccessible = true }
                    .setInt(state, thickness)
            }
            cls.getDeclaredField("mThicknessRatio").apply { isAccessible = true }
                .setFloat(state, thicknessRatio)
            cls.getDeclaredField("mUseLevelForShape").apply { isAccessible = true }
                .setBoolean(state, ringUseLevel)
        }

        if (!ringUseLevel) {
            drawable.level = 10000
        }
    }

    private fun mapOrientation(o: Int): GradientDrawable.Orientation = when (o) {
        0 -> GradientDrawable.Orientation.LEFT_RIGHT
        1 -> GradientDrawable.Orientation.TL_BR
        2 -> GradientDrawable.Orientation.TOP_BOTTOM
        3 -> GradientDrawable.Orientation.TR_BL
        4 -> GradientDrawable.Orientation.RIGHT_LEFT
        5 -> GradientDrawable.Orientation.BR_TL
        6 -> GradientDrawable.Orientation.BOTTOM_TOP
        7 -> GradientDrawable.Orientation.BL_TR
        else -> GradientDrawable.Orientation.LEFT_RIGHT
    }

    fun setSolidColor(color: Int) = apply { solidColor = color }
    fun setSolidPressedColor(color: Int) = apply { solidPressedColor = color }
    fun setSolidSelectedColor(color: Int) = apply { solidSelectedColor = color }
    fun setSolidDisabledColor(color: Int) = apply { solidDisabledColor = color }
    fun setRadius(r: Float) = apply { radius = r }
    fun setTopLeftRadius(r: Float) = apply { topLeftRadius = r }
    fun setTopRightRadius(r: Float) = apply { topRightRadius = r }
    fun setBottomLeftRadius(r: Float) = apply { bottomLeftRadius = r }
    fun setBottomRightRadius(r: Float) = apply { bottomRightRadius = r }
    fun setStrokeColor(c: Int) = apply { strokeColor = c }
    fun setStrokeSize(s: Float) = apply { strokeSize = s }
    fun setStrokeDash(size: Float, gap: Float) = apply {
        strokeDashSize = size; strokeDashGap = gap
    }

    fun setSolidGradient(start: Int, end: Int) = apply {
        solidGradientStartColor = start; solidGradientEndColor = end
    }

    fun setSolidGradient(start: Int, center: Int, end: Int) = apply {
        solidGradientStartColor = start
        solidGradientCenterColor = center
        solidGradientEndColor = end
    }

    fun setSolidGradientOrientation(orientation: Int) = apply {
        solidGradientOrientation = orientation
    }

    fun setShape(shapeType: Int) = apply { shape = shapeType }
}
