package com.simple.mylibrary.weiget.builder

import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.simple.mylibrary.R
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 通用 Shadow 构建器
 */
class ShadowDrawableBuilder(
    private val view: View,
    ta: TypedArray,
    private val styleable: IntArray,
    private val radiusProvider: () -> Float = { 0f }
) {

    private var elevation: Float = 0f
    private var maxElevation: Float = 0f

    private var useCompatPadding: Boolean = false
    private var preventCornerOverlap: Boolean = true

    private var spotColor: Int = 0
    private var ambientColor: Int = 0
    private var shadowAlpha: Float = 1f

    private var originalLpWidth: Int = LP_UNCAPTURED
    private var originalLpHeight: Int = LP_UNCAPTURED

    /** 有颜色配置时走自绘路径（所有 API 版本） */
    private var selfDrawShadow: Boolean = false

    private val basePaddingLeft: Int = view.paddingLeft
    private val basePaddingTop: Int = view.paddingTop
    private val basePaddingRight: Int = view.paddingRight
    private val basePaddingBottom: Int = view.paddingBottom

    init {
        readAttr(ta, R.attr.shape_shadowElevation_L) { elevation = ta.getDimension(it, 0f) }
        readAttr(ta, R.attr.shape_shadowMaxElevation_L) { maxElevation = ta.getDimension(it, elevation) }
        if (maxElevation < elevation) maxElevation = elevation

        readAttr(ta, R.attr.shape_shadowUseCompatPadding_L) { useCompatPadding = ta.getBoolean(it, false) }
        readAttr(ta, R.attr.shape_shadowPreventCornerOverlap_L) { preventCornerOverlap = ta.getBoolean(it, true) }
        readAttr(ta, R.attr.shape_shadowSpotColor_L) { spotColor = ta.getColor(it, 0) }
        readAttr(ta, R.attr.shape_shadowAmbientColor_L) { ambientColor = ta.getColor(it, 0) }
        readAttr(ta, R.attr.shape_shadowAlpha_L) { shadowAlpha = ta.getFloat(it, 1f).coerceIn(0f, 1f) }

        selfDrawShadow = (spotColor != 0 || ambientColor != 0) && Build.VERSION.SDK_INT < 28
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
        if (elevation <= 0f) {
            unwrapIfNeeded()
            applyContentPadding(0)
            registerLayoutSizeAdjustment()
            return
        }
        if (selfDrawShadow) {
            applySelfDrawShadow()
        } else {
            applySystemShadow()
        }
        registerParentClipDisable()
        registerLayoutSizeAdjustment()
    }

    private fun unwrapIfNeeded() {
        val bg = view.background
        if (bg is OpaqueWrapper) view.background = bg.drawable
        else if (bg is ShadowLayerDrawable) view.background = bg.contentDrawable
    }


    private fun applySystemShadow() {
        unwrapIfNeeded()
        view.elevation = elevation

        val bg = view.background
        if (bg != null && bg !is OpaqueWrapper) {
            view.background = OpaqueWrapper(bg)
        }

        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusProvider())
            }
        }
        view.clipToOutline = false

        if (Build.VERSION.SDK_INT >= 28) {
            val spotC  = if (spotColor != 0) applyAlpha(spotColor, shadowAlpha) else 0
            val ambC   = if (ambientColor != 0) applyAlpha(ambientColor, shadowAlpha) else 0
            when {
                spotC != 0 && ambC != 0 -> {
                    view.outlineSpotShadowColor = spotC
                    view.outlineAmbientShadowColor = ambC
                }
                spotC != 0 -> {
                    view.outlineSpotShadowColor = spotC
                    view.outlineAmbientShadowColor = Color.TRANSPARENT
                }
                ambC != 0 -> {
                    view.outlineSpotShadowColor = Color.TRANSPARENT
                    view.outlineAmbientShadowColor = ambC
                }
                else -> {
                    view.outlineAmbientShadowColor = Color.TRANSPARENT
                }
            }
        }

        applyContentPadding(0)
    }


    private fun applySelfDrawShadow() {
        view.elevation = 0f
        unwrapIfNeeded()

        val shadowPadding = calculateShadowPadding()
        val rawColor = if (spotColor != 0) spotColor else ambientColor
        val color = applyAlpha(rawColor, shadowAlpha)

        val shadowDrawable = SelfDrawnShadowDrawable(
            radius = radiusProvider(),
            shadowSize = elevation,
            shadowColor = color,
            contentInset = shadowPadding.toFloat()
        )

        val existing = view.background
        val content: Drawable? = when (existing) {
            is ShadowLayerDrawable -> existing.contentDrawable
            null -> null
            else -> existing
        }

        view.background = if (content != null) {
            ShadowLayerDrawable(shadowDrawable, content, shadowPadding)
        } else {
            shadowDrawable
        }

        if (!view.isInEditMode) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        applyContentPadding(shadowPadding)
    }

    // ── Drawable 工具类 ───────────────────────────────────────────────────────

    /**
     * 让系统把被包装的 background 视为不透明，防止渐变 GradientDrawable 的
     * TRANSLUCENT opacity 导致 outline alpha=0 进而阴影消失。
     */
    private class OpaqueWrapper(drawable: Drawable) : DrawableWrapper(drawable) {
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }

    private class ShadowLayerDrawable(
        shadow: Drawable,
        val contentDrawable: Drawable,
        inset: Int,
    ) : LayerDrawable(arrayOf(shadow, contentDrawable)) {
        init { setLayerInset(1, inset, inset, inset, inset) }
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────────

    private fun calculateShadowPadding(): Int = ceil(elevation).toInt()

    /** 把 shadowAlpha 叠加到颜色 alpha 通道：finalAlpha = colorAlpha × shadowAlpha */
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

    private fun registerLayoutSizeAdjustment() {
        view.post { applyLayoutSizeAdjustment() }
        if (view.isAttachedToWindow) applyLayoutSizeAdjustment()
    }

    private fun applyLayoutSizeAdjustment() {
        val lp = view.layoutParams ?: return
        if (originalLpWidth == LP_UNCAPTURED) originalLpWidth = lp.width
        if (originalLpHeight == LP_UNCAPTURED) originalLpHeight = lp.height

        val shadowPadding = if (selfDrawShadow && elevation > 0f) calculateShadowPadding() else 0
        val targetWidth = if (selfDrawShadow && originalLpWidth > 0) originalLpWidth + 2 * shadowPadding else originalLpWidth
        val targetHeight = if (selfDrawShadow && originalLpHeight > 0) originalLpHeight + 2 * shadowPadding else originalLpHeight

        var changed = false
        if (lp.width != targetWidth) { lp.width = targetWidth; changed = true }
        if (lp.height != targetHeight) { lp.height = targetHeight; changed = true }
        if (changed) view.layoutParams = lp
    }

    // ── 公开 Setter ───────────────────────────────────────────────────────────

    fun setShadowElevation(e: Float) = apply { elevation = e; intoShadow() }

    fun setShadowSpotColor(color: Int) = apply {
        spotColor = color
        selfDrawShadow = (spotColor != 0 || ambientColor != 0) && Build.VERSION.SDK_INT < 28
        intoShadow()
    }

    fun setShadowAmbientColor(color: Int) = apply {
        ambientColor = color
        selfDrawShadow = (spotColor != 0 || ambientColor != 0) && Build.VERSION.SDK_INT < 28
        intoShadow()
    }

    fun setShadowAlpha(alpha: Float) = apply {
        shadowAlpha = alpha.coerceIn(0f, 1f)
        intoShadow()
    }

    fun getShadowElevation(): Float = elevation
    fun getUseCompatPadding(): Boolean = useCompatPadding
    fun getPreventCornerOverlap(): Boolean = preventCornerOverlap

    companion object {
        private const val LP_UNCAPTURED = Int.MIN_VALUE
    }
}
