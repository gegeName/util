package com.simple.mylibrary.weiget.builder

import android.content.res.TypedArray
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.simple.mylibrary.R

/**
 * 通用 Shadow 构建器（仿 CardView 阴影体系）
 *
 * 兼容策略：
 * 1. API ≥ 28：使用系统 elevation + outlineSpotShadowColor / outlineAmbientShadowColor
 * 2. API < 28 且设置了 shadowSpotColor/shadowAmbientColor：自绘彩色阴影（SelfDrawnShadowDrawable）
 * 3. API < 28 仅设置了 shadowElevation：仍用系统 elevation（黑色阴影）
 * 4. 自动解除父容器 clipChildren / clipToPadding，避免阴影被裁剪
 */
class ShadowDrawableBuilder(
    private val view: View,
    ta: TypedArray,
    private val styleable: IntArray,
    /** 圆角 provider，通常返回 ShapeDrawableBuilder.radius */
    private val radiusProvider: () -> Float = { 0f }
) {

    private var elevation: Float = 0f
    private var maxElevation: Float = 0f
    private var useCompatPadding: Boolean = false
    private var preventCornerOverlap: Boolean = true
    private var spotColor: Int = 0
    private var ambientColor: Int = 0

    private var contentPaddingLeft: Int = -1
    private var contentPaddingTop: Int = -1
    private var contentPaddingRight: Int = -1
    private var contentPaddingBottom: Int = -1

    /** 是否走自绘阴影路径（API<28 且需要彩色阴影时） */
    private var selfDrawShadow: Boolean = false

    init {
        readAttr(ta, R.attr.shape_shadowElevation_L) { elevation = ta.getDimension(it, 0f) }
        readAttr(ta, R.attr.shape_shadowMaxElevation_L) { maxElevation = ta.getDimension(it, elevation) }
        if (maxElevation < elevation) maxElevation = elevation

        readAttr(ta, R.attr.shape_shadowUseCompatPadding_L) { useCompatPadding = ta.getBoolean(it, false) }
        readAttr(ta, R.attr.shape_shadowPreventCornerOverlap_L) {
            preventCornerOverlap = ta.getBoolean(it, true)
        }
        readAttr(ta, R.attr.shape_shadowSpotColor_L) { spotColor = ta.getColor(it, 0) }
        readAttr(ta, R.attr.shape_shadowAmbientColor_L) { ambientColor = ta.getColor(it, 0) }

        var defaultPadding = 0
        readAttr(ta, R.attr.shape_shadowContentPadding_L) {
            defaultPadding = ta.getDimensionPixelSize(it, 0)
        }
        contentPaddingLeft = defaultPadding
        contentPaddingTop = defaultPadding
        contentPaddingRight = defaultPadding
        contentPaddingBottom = defaultPadding
        readAttr(ta, R.attr.shape_shadowContentPaddingLeft_L) {
            contentPaddingLeft = ta.getDimensionPixelSize(it, defaultPadding)
        }
        readAttr(ta, R.attr.shape_shadowContentPaddingTop_L) {
            contentPaddingTop = ta.getDimensionPixelSize(it, defaultPadding)
        }
        readAttr(ta, R.attr.shape_shadowContentPaddingRight_L) {
            contentPaddingRight = ta.getDimensionPixelSize(it, defaultPadding)
        }
        readAttr(ta, R.attr.shape_shadowContentPaddingBottom_L) {
            contentPaddingBottom = ta.getDimensionPixelSize(it, defaultPadding)
        }

        // 是否需要自绘彩色阴影
        selfDrawShadow = (spotColor != 0 || ambientColor != 0) && Build.VERSION.SDK_INT < 28
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

    /** 应用阴影 */
    fun intoShadow() {
        if (elevation <= 0f) {
            applyContentPadding(extraPad = 0)
            registerParentClipDisable()
            return
        }

        if (selfDrawShadow) {
            applySelfDrawnShadow()
        } else {
            applySystemShadow()
        }

        registerParentClipDisable()
    }

    /** 系统阴影（API≥21 都支持，但 API<28 颜色仅为默认黑） */
    private fun applySystemShadow() {
        view.elevation = elevation
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusProvider())
            }
        }
        view.clipToOutline = true

        if (Build.VERSION.SDK_INT >= 28) {
            if (spotColor != 0) view.outlineSpotShadowColor = spotColor
            if (ambientColor != 0) view.outlineAmbientShadowColor = ambientColor
        }

        applyContentPadding(extraPad = 0)
    }

    /** API<28 自绘彩色阴影 */
    private fun applySelfDrawnShadow() {
        view.elevation = 0f

        val color = if (spotColor != 0) spotColor else ambientColor
        val shadowPad = elevation.toInt()
        val shadow: Drawable = SelfDrawnShadowDrawable(
            radius = radiusProvider(),
            shadowSize = elevation,
            shadowColor = color
        )

        val current = view.background
        val combined: Drawable = if (current != null) {
            LayerDrawable(arrayOf(shadow, current)).apply {
                // 第二层（真正背景）内缩阴影厚度
                setLayerInset(1, shadowPad, shadowPad, shadowPad, shadowPad)
            }
        } else {
            shadow
        }
        view.background = combined

        // setShadowLayer 在硬件加速下可能失效，强制软件渲染
        // AS 预览中 LAYER_TYPE_SOFTWARE 可能导致 NPE，跳过
        if (!view.isInEditMode) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        applyContentPadding(extraPad = shadowPad)
    }

    private fun applyContentPadding(extraPad: Int) {
        if (contentPaddingLeft < 0) return
        view.setPadding(
            contentPaddingLeft + extraPad,
            contentPaddingTop + extraPad,
            contentPaddingRight + extraPad,
            contentPaddingBottom + extraPad
        )
    }

    /**
     * 自动解除父容器 clipChildren / clipToPadding，避免阴影被裁剪。
     *
     * 三重保险：
     * 1. view.post：下一帧立即执行（同时覆盖 AS 预览场景）
     * 2. isAttachedToWindow：已 attach 的复用场景立即生效
     * 3. addOnAttachStateChangeListener：兜底，appear-disappear-appear 也能恢复
     */
    private fun registerParentClipDisable() {
        view.post { disableParentClipping(view) }

        if (view.isAttachedToWindow) {
            disableParentClipping(view)
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                disableParentClipping(v)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun disableParentClipping(start: View) {
        var parent: ViewGroup? = start.parent as? ViewGroup
        var depth = MAX_CLIP_DISABLE_DEPTH
        while (parent != null && depth > 0) {
            if (parent.clipChildren) parent.clipChildren = false
            if (parent.clipToPadding) parent.clipToPadding = false
            parent = parent.parent as? ViewGroup
            depth--
        }
    }

    fun setShadowElevation(e: Float) = apply {
        elevation = e
        if (!selfDrawShadow) view.elevation = e else intoShadow()
    }

    fun setShadowSpotColor(color: Int) = apply {
        spotColor = color
        selfDrawShadow = (spotColor != 0 || ambientColor != 0) && Build.VERSION.SDK_INT < 28
        if (Build.VERSION.SDK_INT >= 28) view.outlineSpotShadowColor = color else intoShadow()
    }

    fun setShadowAmbientColor(color: Int) = apply {
        ambientColor = color
        selfDrawShadow = (spotColor != 0 || ambientColor != 0) && Build.VERSION.SDK_INT < 28
        if (Build.VERSION.SDK_INT >= 28) view.outlineAmbientShadowColor = color else intoShadow()
    }

    fun setContentPadding(left: Int, top: Int, right: Int, bottom: Int) = apply {
        contentPaddingLeft = left
        contentPaddingTop = top
        contentPaddingRight = right
        contentPaddingBottom = bottom
        val extra = if (selfDrawShadow) elevation.toInt() else 0
        applyContentPadding(extra)
    }

    fun getShadowElevation(): Float = elevation
    fun getUseCompatPadding(): Boolean = useCompatPadding
    fun getPreventCornerOverlap(): Boolean = preventCornerOverlap

    companion object {
        private const val MAX_CLIP_DISABLE_DEPTH = 3
    }
}
