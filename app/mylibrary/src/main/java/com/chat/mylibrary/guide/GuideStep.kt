package com.chat.mylibrary.guide

import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes

/**
 * 单个引导步骤的链式配置。
 *
 * 命名约定：
 *  - `content*`        操作引导内容（图片 / SVGA / 自定义 View 及其尺寸、定位、动画）
 *  - `highlight`       追加高亮挖洞区域（同一步多次调用 → 多按钮高亮）
 *  - `onContentClick`  点击内容时的回调
 *  - `onHighlightClick` 点击具体某高亮区域（非点穿）时的回调
 *  - `autoNextAfter`   N 毫秒后自动进入下一步
 */
class GuideStep internal constructor() {

    internal val highlights = mutableListOf<HighlightTarget>()
    internal var content: Content = Content.Empty
    internal var anchorView: View? = null
    internal var position: Position = Position.Center
    internal var offsetX: Int = 0
    internal var offsetY: Int = 0
    internal var anim: GuideAnim = GuideAnim.FadeScale
    internal var contentClickListener: ((GuideController) -> Unit)? = null
    internal var autoNextDelayMs: Long = 0L
    internal var contentWidth: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    internal var contentHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * 追加一个高亮挖洞区域。一个 step 可多次调用以同时高亮多个 View。
     * @param clickThrough        true 时点击该区域直接穿透到底层真实控件
     * @param onHighlightClick    非穿透模式下，该区域被点击时的回调
     */
    fun highlight(
        view: View,
        shape: HighlightShape = HighlightShape.RoundRect(radiusPx = 24f, paddingPx = 8),
        clickThrough: Boolean = false,
        onHighlightClick: (() -> Unit)? = null,
    ): GuideStep = apply {
        highlights += HighlightTarget(view, shape, clickThrough, onHighlightClick)
    }

    /** 引导内容：图片资源 */
    fun contentImage(@DrawableRes resId: Int): GuideStep = apply {
        content = Content.Image(resId)
    }

    /** 引导内容：SVGA。必须先通过 [GuideView.setSvgaProvider] 注入 provider */
    fun contentSvga(source: String, loops: Int = 0): GuideStep = apply {
        content = Content.Svga(source, loops)
    }

    /** 引导内容：自定义 View */
    fun contentView(view: View): GuideStep = apply {
        content = Content.Custom(view)
    }

    /** 强制指定内容宽高（默认 WRAP_CONTENT） */
    fun contentSize(width: Int, height: Int): GuideStep = apply {
        contentWidth = width
        contentHeight = height
    }

    /** 内容相对哪个 View 定位；不设则默认相对第一个 highlight；都没有则居中全屏 */
    fun contentAnchor(view: View): GuideStep = apply { anchorView = view }

    /** 内容相对 anchor 的方位 */
    fun contentPosition(p: Position): GuideStep = apply { position = p }

    /** 在算出的位置上再平移 (dx, dy) */
    fun contentOffset(dx: Int = 0, dy: Int = 0): GuideStep = apply {
        offsetX = dx
        offsetY = dy
    }

    /** 内容入场/出场动画 */
    fun contentAnim(a: GuideAnim): GuideStep = apply { anim = a }

    /** 点击内容时回调；默认行为是 next() */
    fun onContentClick(block: (GuideController) -> Unit): GuideStep = apply {
        contentClickListener = block
    }

    /** 进入该 step 后 delayMs 毫秒自动 next；<=0 表示不自动 */
    fun autoNextAfter(delayMs: Long): GuideStep = apply { autoNextDelayMs = delayMs }
}

internal data class HighlightTarget(
    val view: View,
    val shape: HighlightShape,
    val clickThrough: Boolean,
    val onClick: (() -> Unit)?,
)

internal sealed class Content {
    object Empty : Content()
    data class Image(val resId: Int) : Content()
    data class Svga(val source: String, val loops: Int) : Content()
    data class Custom(val view: View) : Content()
}

/** 内容相对 anchor 的位置 */
sealed class Position {
    object Center : Position()
    object Above : Position()
    object Below : Position()
    object LeftOf : Position()
    object RightOf : Position()

    /**
     * 完全自定义位置。
     * @param resolver 入参：anchor 在 GuideView 中的矩形 + 内容已 measure 的尺寸；
     *                 返回：内容左上角在 GuideView 坐标系中的位置。
     */
    class Custom(
        val resolver: (anchorRect: Rect, contentWidth: Int, contentHeight: Int) -> Point,
    ) : Position()
}
