package com.simple.mylibrary.weiget.builder

import android.content.res.TypedArray
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import com.simple.mylibrary.R
import kotlin.math.ceil

/**
 * 通用 Shadow 构建器（仿 CardView 阴影体系）
 *
 * 兼容策略：
 * 1. API ≥ 28：使用系统 elevation + outlineSpotShadowColor / outlineAmbientShadowColor
 * 2. API < 28 且设置了 shadowSpotColor/shadowAmbientColor：自绘彩色阴影（SelfDrawnShadowDrawable）
 * 3. API < 28 仅设置了 shadowElevation：仍用系统 elevation（黑色阴影）
 * 4. 自动解除父容器 clipChildren / clipToPadding，避免阴影被裁剪
 */


/**
 * 通用 Shadow 构建器（CardView/CardLayout 风格）
 *
 * 实现目标：
 * 1. 阴影始终向外扩散，而不是向内容内部侵占
 * 2. 内容区域尺寸保持不变
 * 3. 自动给 View 增加外部阴影占位
 * 4. 自动关闭父布局 clipChildren / clipToPadding
 *
 * 实现策略：
 *
 * API >= 28:
 * - 使用系统 elevation + 彩色阴影
 *
 * API 21~27:
 * - 使用 elevation（黑色系统阴影）
 *
 * API < 21 或低版本彩色阴影:
 * - 自绘阴影
 * - 使用 InsetDrawable 给背景留出外部阴影区域
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

    private var contentPaddingLeft: Int = 0
    private var contentPaddingTop: Int = 0
    private var contentPaddingRight: Int = 0
    private var contentPaddingBottom: Int = 0

    /** 是否走自绘阴影 */
    private var selfDrawShadow: Boolean = false

    init {

        readAttr(ta, R.attr.shape_shadowElevation_L) {
            elevation = ta.getDimension(it, 0f)
        }

        readAttr(ta, R.attr.shape_shadowMaxElevation_L) {
            maxElevation = ta.getDimension(it, elevation)
        }

        if (maxElevation < elevation) {
            maxElevation = elevation
        }

        readAttr(ta, R.attr.shape_shadowUseCompatPadding_L) {
            useCompatPadding = ta.getBoolean(it, false)
        }

        readAttr(ta, R.attr.shape_shadowPreventCornerOverlap_L) {
            preventCornerOverlap = ta.getBoolean(it, true)
        }

        readAttr(ta, R.attr.shape_shadowSpotColor_L) {
            spotColor = ta.getColor(it, 0)
        }

        readAttr(ta, R.attr.shape_shadowAmbientColor_L) {
            ambientColor = ta.getColor(it, 0)
        }

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

        selfDrawShadow =
            (spotColor != 0 || ambientColor != 0) &&
                    Build.VERSION.SDK_INT < 28
    }

    private inline fun readAttr(
        ta: TypedArray,
        attrResId: Int,
        action: (Int) -> Unit
    ) {
        val index = findAttrIndex(attrResId)

        if (index >= 0 && ta.hasValue(index)) {
            action(index)
        }
    }

    private fun findAttrIndex(attrResId: Int): Int {
        for (i in styleable.indices) {
            if (styleable[i] == attrResId) {
                return i
            }
        }
        return -1
    }

    /**
     * 应用阴影
     */
    fun intoShadow() {

        if (elevation <= 0f) {
            applyContentPadding(0)
            return
        }

        if (selfDrawShadow) {
            applySelfDrawShadow()
        } else {
            applySystemShadow()
        }

        registerParentClipDisable()
    }

    /**
     * 系统阴影
     */
    private fun applySystemShadow() {

        view.elevation = elevation

        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    v.width,
                    v.height,
                    radiusProvider()
                )
            }
        }

        view.clipToOutline = false

        if (Build.VERSION.SDK_INT >= 28) {

            if (spotColor != 0) {
                view.outlineSpotShadowColor = spotColor
            }

            if (ambientColor != 0) {
                view.outlineAmbientShadowColor = ambientColor
            }
        }

        val shadowPadding = calculateShadowPadding()

        applyContentPadding(shadowPadding)
    }

    /**
     * 自绘阴影（CardView 风格）
     */
    private fun applySelfDrawShadow() {

        view.elevation = 0f

        val shadowPadding = calculateShadowPadding()

        val color =
            if (spotColor != 0) spotColor
            else ambientColor

        val shadowDrawable = SelfDrawnShadowDrawable(
            radius = radiusProvider(),
            shadowSize = elevation,
            shadowColor = color
        )

        /**
         * 关键：
         *
         * 不再缩小 background
         *
         * 而是：
         * 给整个 drawable 留出外围阴影区域
         *
         * 类似 CardView
         */
        val insetDrawable = InsetDrawable(
            shadowDrawable,
            shadowPadding,
            shadowPadding,
            shadowPadding,
            shadowPadding
        )

        view.background = insetDrawable

        /**
         * setShadowLayer 需要软件渲染
         */
        if (!view.isInEditMode) {
            view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }

        applyContentPadding(shadowPadding)
    }

    /**
     * 计算 CardView 风格阴影 padding
     */
    private fun calculateShadowPadding(): Int {

        /**
         * CardView 的经验值：
         *
         * 阴影高度大约 = elevation * 1.5
         */
        val extra =
            ceil(elevation * 1.5f).toInt()

        return extra
    }

    /**
     * 内容 padding
     *
     * 这里只增加外围空间
     * 不侵占内容
     */
    private fun applyContentPadding(shadowPadding: Int) {

        view.setPadding(
            contentPaddingLeft + shadowPadding,
            contentPaddingTop + shadowPadding,
            contentPaddingRight + shadowPadding,
            contentPaddingBottom + shadowPadding
        )
    }

    /**
     * 防止阴影被裁剪
     */
    private fun registerParentClipDisable() {

        view.post {
            disableParentClip(view)
        }

        if (view.isAttachedToWindow) {
            disableParentClip(view)
        }

        view.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {

                override fun onViewAttachedToWindow(v: View) {
                    disableParentClip(v)
                }

                override fun onViewDetachedFromWindow(v: View) {
                }
            }
        )
    }

    private fun disableParentClip(start: View) {

        var parent = start.parent as? ViewGroup

        var depth = MAX_CLIP_DISABLE_DEPTH

        while (parent != null && depth > 0) {

            if (parent.clipChildren) {
                parent.clipChildren = false
            }

            if (parent.clipToPadding) {
                parent.clipToPadding = false
            }

            parent = parent.parent as? ViewGroup

            depth--
        }
    }

    fun setShadowElevation(e: Float) = apply {

        elevation = e

        if (!selfDrawShadow) {
            view.elevation = e
        } else {
            intoShadow()
        }
    }

    fun setShadowSpotColor(color: Int) = apply {

        spotColor = color

        selfDrawShadow =
            (spotColor != 0 || ambientColor != 0) &&
                    Build.VERSION.SDK_INT < 28

        intoShadow()
    }

    fun setShadowAmbientColor(color: Int) = apply {

        ambientColor = color

        selfDrawShadow =
            (spotColor != 0 || ambientColor != 0) &&
                    Build.VERSION.SDK_INT < 28

        intoShadow()
    }

    fun setContentPadding(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = apply {

        contentPaddingLeft = left
        contentPaddingTop = top
        contentPaddingRight = right
        contentPaddingBottom = bottom

        applyContentPadding(calculateShadowPadding())
    }

    fun getShadowElevation(): Float = elevation

    fun getUseCompatPadding(): Boolean = useCompatPadding

    fun getPreventCornerOverlap(): Boolean = preventCornerOverlap

    companion object {

        /**
         * 最多向上关闭 3 层父布局裁剪
         */
        private const val MAX_CLIP_DISABLE_DEPTH = 3
    }
}
