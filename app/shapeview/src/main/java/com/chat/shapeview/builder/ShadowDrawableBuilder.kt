package com.chat.shapeview.builder

import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import com.chat.shapeview.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 通用 Shadow 构建器（CardView 风格）
 *
 * 策略：统一走自绘阴影（SelfDrawnShadowDrawable + BlurMaskFilter），所有 API 一致。
 * 系统 elevation 在 View 控件（非容器）上无法向外扩散阴影区域，故放弃使用。
 *
 * 行为：
 * - 声明的 width/height = 白色内容矩形大小
 * - 自动给 View 增加 shadowPadding（四周），阴影在 padding 区扩散
 * - 若 layout_width/height 是固定值，自动扩大 2×shadowPadding 以保证内容区不变小
 * - spotColor/ambientColor 设置阴影颜色；未设置时使用默认半透明黑色
 * - shadowAlpha 叠加控制整体透明度
 */
class ShadowDrawableBuilder(
    private val view: View,
    ta: TypedArray,
    private val styleable: IntArray,
    private val radiusProvider: () -> Float = { 0f }
) {

    private var elevation: Float = 0f
    private var spotColor: Int = 0
    private var ambientColor: Int = 0
    private var shadowAlpha: Float = 1f
    private var shadowOffsetX: Float = 0f
    private var shadowOffsetY: Float = 0f

    private var originalLpWidth: Int = LP_UNCAPTURED
    private var originalLpHeight: Int = LP_UNCAPTURED

    private val basePaddingLeft: Int = view.paddingLeft
    private val basePaddingTop: Int = view.paddingTop
    private val basePaddingRight: Int = view.paddingRight
    private val basePaddingBottom: Int = view.paddingBottom

    init {
        readAttr(ta, R.attr.shape_shadowElevation_L) { elevation = ta.getDimension(it, 0f) }
        readAttr(ta, R.attr.shape_shadowSpotColor_L) { spotColor = ta.getColor(it, 0) }
        readAttr(ta, R.attr.shape_shadowAmbientColor_L) { ambientColor = ta.getColor(it, 0) }
        readAttr(ta, R.attr.shape_shadowAlpha_L) {
            shadowAlpha = ta.getFloat(it, 1f).coerceIn(0f, 1f)
        }
        readAttr(ta, R.attr.shape_shadowOffsetX_L) { shadowOffsetX = ta.getDimension(it, 0f) }
        readAttr(ta, R.attr.shape_shadowOffsetY_L) { shadowOffsetY = ta.getDimension(it, 0f) }
    }

    private inline fun readAttr(ta: TypedArray, attrResId: Int, action: (Int) -> Unit) {
        val index = findAttrIndex(attrResId)
        if (index >= 0 && ta.hasValue(index)) action(index)
    }

    private fun findAttrIndex(attrResId: Int): Int {
        for (i in styleable.indices) if (styleable[i] == attrResId) return i
        return -1
    }

    fun intoShadow() {
        unwrapIfNeeded()
        if (elevation <= 0f) {
            applyContentPadding(0)
            registerLayoutSizeAdjustment(0)
            return
        }
        applySelfDrawShadow()
        registerParentClipDisable()
    }

    private fun unwrapIfNeeded() {
        val bg = view.background
        if (bg is ShadowLayerDrawable) view.background = bg.contentDrawable
    }

    private fun applySelfDrawShadow() {
        view.elevation = 0f

        val shadowPadding = calculateShadowPadding()
        val rawColor = when {
            spotColor != 0 -> spotColor
            ambientColor != 0 -> ambientColor
            else -> 0x44000000
        }
        val color = applyAlpha(rawColor, shadowAlpha)

        val shadowDrawable = SelfDrawnShadowDrawable(
            radius = radiusProvider(),
            shadowSize = elevation,
            shadowColor = color,
            contentInset = shadowPadding.toFloat(),
            offsetX = shadowOffsetX,
            offsetY = shadowOffsetY,
        )

        val existing = view.background
        val content: Drawable? = when (existing) {
            is ShadowLayerDrawable -> existing.contentDrawable
            null -> null
            else -> existing
        }

        val insetL = shadowPadding + (-shadowOffsetX).coerceAtLeast(0f).toInt()
        val insetR = shadowPadding + shadowOffsetX.coerceAtLeast(0f).toInt()
        val insetT = shadowPadding + (-shadowOffsetY).coerceAtLeast(0f).toInt()
        val insetB = shadowPadding + shadowOffsetY.coerceAtLeast(0f).toInt()

        val finalBg = if (content != null) {
            ShadowLayerDrawable(shadowDrawable, content, insetL, insetT, insetR, insetB)
        } else {
            shadowDrawable
        }
        view.background = finalBg

        if (!view.isInEditMode) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        applyContentPadding(shadowPadding)
        registerLayoutSizeAdjustment(insetL, insetT, insetR, insetB)
    }

    private class ShadowLayerDrawable(
        shadow: Drawable,
        val contentDrawable: Drawable,
        insetL: Int, insetT: Int, insetR: Int, insetB: Int,
    ) : LayerDrawable(arrayOf(shadow, contentDrawable)) {
        init {
            setLayerInset(1, insetL, insetT, insetR, insetB)
        }
    }

    private fun calculateShadowPadding(): Int = ceil(elevation).toInt()

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val originalAlpha = (color ushr 24) and 0xFF
        val newAlpha = (originalAlpha * alpha).roundToInt().coerceIn(0, 255)
        return (newAlpha shl 24) or (color and 0x00FFFFFF)
    }

    private fun applyContentPadding(shadowPadding: Int) {
        view.setPadding(
            basePaddingLeft + shadowPadding,
            basePaddingTop + shadowPadding,
            basePaddingRight + shadowPadding,
            basePaddingBottom + shadowPadding
        )
    }

    private fun registerParentClipDisable() {
        view.post { disableParentClip(view) }
        if (view.isAttachedToWindow) disableParentClip(view)
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = disableParentClip(v)
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    private fun disableParentClip(start: View) {
        var parent = start.parent as? ViewGroup
        while (parent != null) {
            if (parent.clipChildren) parent.clipChildren = false
            if (parent.clipToPadding) parent.clipToPadding = false
            parent = parent.parent as? ViewGroup
        }
    }

    private fun registerLayoutSizeAdjustment(shadowPadding: Int) {
        view.post {
            applyLayoutSizeAdjustment(
                shadowPadding,
                shadowPadding,
                shadowPadding,
                shadowPadding
            )
        }
        if (view.isAttachedToWindow) applyLayoutSizeAdjustment(
            shadowPadding,
            shadowPadding,
            shadowPadding,
            shadowPadding
        )
    }

    private fun registerLayoutSizeAdjustment(padL: Int, padT: Int, padR: Int, padB: Int) {
        view.post { applyLayoutSizeAdjustment(padL, padT, padR, padB) }
        if (view.isAttachedToWindow) applyLayoutSizeAdjustment(padL, padT, padR, padB)
    }

    private fun applyLayoutSizeAdjustment(padL: Int, padT: Int, padR: Int, padB: Int) {
        val lp = view.layoutParams ?: return
        if (originalLpWidth == LP_UNCAPTURED) originalLpWidth = lp.width
        if (originalLpHeight == LP_UNCAPTURED) originalLpHeight = lp.height

        var changed = false
        val tw = if (originalLpWidth > 0) originalLpWidth + padL + padR else originalLpWidth
        val th = if (originalLpHeight > 0) originalLpHeight + padT + padB else originalLpHeight
        if (lp.width != tw) {
            lp.width = tw; changed = true
        }
        if (lp.height != th) {
            lp.height = th; changed = true
        }
        if (changed) view.layoutParams = lp
    }


    fun setShadowElevation(e: Float) = apply { elevation = e; intoShadow() }

    fun setShadowSpotColor(color: Int) = apply { spotColor = color; intoShadow() }

    fun setShadowAmbientColor(color: Int) = apply { ambientColor = color; intoShadow() }

    fun setShadowAlpha(alpha: Float) = apply { shadowAlpha = alpha.coerceIn(0f, 1f); intoShadow() }

    fun setShadowOffset(x: Float, y: Float) =
        apply { shadowOffsetX = x; shadowOffsetY = y; intoShadow() }

    fun getShadowElevation(): Float = elevation

    companion object {
        private const val LP_UNCAPTURED = Int.MIN_VALUE
    }
}
