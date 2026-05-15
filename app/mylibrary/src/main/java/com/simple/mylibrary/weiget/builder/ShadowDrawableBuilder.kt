package com.simple.mylibrary.weiget.builder

import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Outline
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.graphics.drawable.LayerDrawable
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

    /**
     * 用户期望的「白色内容矩形」尺寸。-1 表示未设置。
     * 自绘阴影路径下 layoutParams 会被自动调整为 contentSize + 2 × shadowPadding，
     * 系统阴影路径下直接使用 contentSize（阴影本来就向 View 外扩散）。
     */
    private var contentWidth: Int = -1
    private var contentHeight: Int = -1

    /**
     * 首次调整 layoutParams 之前缓存用户原始 width / height，
     * 之后任何重新调整都基于这两个原始值算偏移，避免反复累加。
     * 哨兵值 LP_UNCAPTURED 表示尚未捕获。
     */
    private var originalLpWidth: Int = LP_UNCAPTURED
    private var originalLpHeight: Int = LP_UNCAPTURED

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

        readAttr(ta, R.attr.shape_shadowContentSize_L) {
            val size = ta.getDimensionPixelSize(it, -1)
            contentWidth = size
            contentHeight = size
        }

        readAttr(ta, R.attr.shape_shadowContentWidth_L) {
            contentWidth = ta.getDimensionPixelSize(it, contentWidth)
        }

        readAttr(ta, R.attr.shape_shadowContentHeight_L) {
            contentHeight = ta.getDimensionPixelSize(it, contentHeight)
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

    private fun applySystemShadow() {

        view.elevation = elevation

        val padLeft = contentPaddingLeft
        val padTop = contentPaddingTop
        val padRight = contentPaddingRight
        val padBottom = contentPaddingBottom
        val hasContentPad = padLeft > 0 || padTop > 0 || padRight > 0 || padBottom > 0

        if (hasContentPad) {
            val bg = view.background
            if (bg != null && bg !is LayerDrawable) {
                val layered = LayerDrawable(arrayOf(bg))
                layered.setLayerInset(0, padLeft, padTop, padRight, padBottom)
                view.background = layered
            }
        }

        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                if (v.width <= padLeft + padRight || v.height <= padTop + padBottom) {
                    return
                }
                outline.setRoundRect(
                    padLeft,
                    padTop,
                    v.width - padRight,
                    v.height - padBottom,
                    radiusProvider()
                )
                outline.alpha = 1.0f
            }
        }

        view.clipToOutline = false

        if (Build.VERSION.SDK_INT >= 28) {

            when {
                spotColor != 0 && ambientColor != 0 -> {
                    view.outlineSpotShadowColor = spotColor
                    view.outlineAmbientShadowColor = ambientColor
                }
                spotColor != 0 -> {
                    view.outlineSpotShadowColor = spotColor
                    view.outlineAmbientShadowColor =
                        fadeAlpha(spotColor, COMPANION_ALPHA_FACTOR)
                }
                ambientColor != 0 -> {
                    view.outlineSpotShadowColor =
                        fadeAlpha(ambientColor, COMPANION_ALPHA_FACTOR)
                    view.outlineAmbientShadowColor = ambientColor
                }
                else -> {
                    view.outlineAmbientShadowColor = Color.TRANSPARENT
                }
            }
        }

        view.setPadding(padLeft, padTop, padRight, padBottom)
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
            shadowColor = color,
            contentInset = shadowPadding.toFloat()
        )

        val existingBackground = view.background

        if (existingBackground != null) {
            val layerDrawable = LayerDrawable(arrayOf(shadowDrawable, existingBackground))
            layerDrawable.setLayerInset(
                1,
                shadowPadding,
                shadowPadding,
                shadowPadding,
                shadowPadding
            )
            view.background = layerDrawable
        } else {
            view.background = shadowDrawable
        }

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

        /**
         * 一路向上关闭所有祖先的 clipChildren / clipToPadding，
         * 否则系统 elevation 阴影会在某一层祖先的矩形边界被裁剪，
         * 留下一条硬边（看起来像「阴影外面包了一层边框」）。
         */
        var parent = start.parent as? ViewGroup

        while (parent != null) {

            if (parent.clipChildren) {
                parent.clipChildren = false
            }

            if (parent.clipToPadding) {
                parent.clipToPadding = false
            }

            parent = parent.parent as? ViewGroup
        }
    }

    /**
     * 注册 layoutParams 自动调整：
     *
     * - 自绘阴影路径下，阴影是画在 View 自身 bitmap 内的，
     *   如果 layout_width / layout_height 是固定 dp 值，
     *   直接渲染会让白色内容矩形被 shadowPadding 侵占而缩小。
     *   这里把 layoutParams 自动加上 2 × shadowPadding，
     *   让用户写多大、白色内容矩形就有多大。
     *
     * - 系统阴影路径下，阴影本来就向 View 外扩散，无需扩张 layoutParams；
     *   仅当用户显式指定 shape_shadowContentSize_L / Width / Height 时，
     *   才用这些值覆盖 layoutParams（语义：白色内容矩形 == 这个尺寸）。
     */
    private fun registerLayoutSizeAdjustment() {

        view.post {
            applyLayoutSizeAdjustment()
        }

        if (view.isAttachedToWindow) {
            applyLayoutSizeAdjustment()
        }
    }

    private fun applyLayoutSizeAdjustment() {

        val lp = view.layoutParams ?: return

        if (originalLpWidth == LP_UNCAPTURED) {
            originalLpWidth = lp.width
        }

        if (originalLpHeight == LP_UNCAPTURED) {
            originalLpHeight = lp.height
        }

        val shadowPadding =
            if (selfDrawShadow && elevation > 0f) calculateShadowPadding() else 0

        val targetWidth = resolveTargetSize(
            explicitContent = contentWidth,
            originalLp = originalLpWidth,
            shadowPadding = shadowPadding
        )

        val targetHeight = resolveTargetSize(
            explicitContent = contentHeight,
            originalLp = originalLpHeight,
            shadowPadding = shadowPadding
        )

        var changed = false

        if (lp.width != targetWidth) {
            lp.width = targetWidth
            changed = true
        }

        if (lp.height != targetHeight) {
            lp.height = targetHeight
            changed = true
        }

        if (changed) {
            view.layoutParams = lp
        }
    }

    /**
     * 单一维度的尺寸推导：
     *
     * 1. 显式给了 contentSize：以它为「内容矩形」尺寸；
     *    自绘阴影要再加 2 × shadowPadding，系统阴影直接用。
     * 2. 没显式给但用户写了固定 dp 值（>0）且走自绘阴影：
     *    把原始 dp 视为「内容矩形」尺寸，自动加 2 × shadowPadding。
     * 3. 其它情况（WRAP_CONTENT / MATCH_PARENT / 系统阴影 + 用户没给 content size）：
     *    保持原值，不动。
     */
    private fun resolveTargetSize(
        explicitContent: Int,
        originalLp: Int,
        shadowPadding: Int
    ): Int {
        return when {
            explicitContent > 0 -> explicitContent + 2 * shadowPadding
            selfDrawShadow && originalLp > 0 -> originalLp + 2 * shadowPadding
            else -> originalLp
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

        val shadowPadding = if (selfDrawShadow) calculateShadowPadding() else 0

        applyContentPadding(shadowPadding)
    }

    fun setContentSize(size: Int) = apply {
        contentWidth = size
        contentHeight = size
        applyLayoutSizeAdjustment()
    }

    fun setContentWidth(width: Int) = apply {
        contentWidth = width
        applyLayoutSizeAdjustment()
    }

    fun setContentHeight(height: Int) = apply {
        contentHeight = height
        applyLayoutSizeAdjustment()
    }

    fun getShadowElevation(): Float = elevation

    fun getUseCompatPadding(): Boolean = useCompatPadding

    fun getPreventCornerOverlap(): Boolean = preventCornerOverlap

    /**
     * 把颜色的 alpha 通道按比例缩放，RGB 保持不变。
     *
     * 例如 fadeAlpha(0xFF000000.toInt(), 0.9f) 得到 0xE6000000。
     */
    private fun fadeAlpha(color: Int, factor: Float): Int {
        val a = (((color ushr 24) and 0xFF) * factor)
            .toInt()
            .coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    companion object {
        /** layoutParams 原始 width / height 尚未捕获的哨兵值 */
        private const val LP_UNCAPTURED = Int.MIN_VALUE

        /**
         * spot / ambient 缺失时用对方颜色补齐时,对 alpha 应用的衰减系数。
         * 0f = 完全透明,即另一边不再贡献阴影,
         * 阴影只由用户显式配置的那一侧产生,避免系统默认黑色叠出硬边。
         */
        private const val COMPANION_ALPHA_FACTOR = 0f
    }
}
