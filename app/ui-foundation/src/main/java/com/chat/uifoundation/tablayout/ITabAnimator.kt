package com.chat.uifoundation.tablayout

import android.animation.ArgbEvaluator
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Tab 切换时的样式插值器。
 * fraction：0f..1f，1f 表示该 tab 当前被完全选中。
 */
interface ITabAnimator {
    fun apply(tab: View, position: Int, fraction: Float, provider: ITabProvider)

    /**
     * 把 tab 还原到 "本 Animator 没参与过" 的初始视觉态。
     *
     * 由 MagicTabLayout 在替换 animator 时调用旧 animator 的此方法。
     * Animator 各自只动自己写过的属性（scaleX/Y / textSize / alpha / textColor 等），
     * reset 时把它们清回缺省值，避免新 Animator 的 apply 之上叠加旧的残留。
     *
     * 默认 no-op。自定义 Animator 若写了视觉属性，建议重写本方法。
     */
    fun reset(tab: View, provider: ITabProvider) {}
}

/**
 * 字号 + 颜色 + fakeBold 联合插值。
 * 不会触发 requestLayout（用 scaleX/Y 而非真正变 textSize）→ 零抖动。
 * 注意：选中状态字体会糊；如对清晰度敏感，用 [TextSizeColorAnimator]。
 */
class ScaleColorAnimator(
    private val minScale: Float = 1f,
    private val maxScale: Float = 1.18f,
    private val unselectedColor: Int = Color.parseColor("#999999"),
    private val selectedColor: Int = Color.parseColor("#333333"),
    /**
     * 加粗触发阈值。默认 1f 表示 **只在完全选中时才 fakeBold**，
     * 滑动过程中文字保持非粗体，避免在中间帧切换造成文字宽度突变 / 视觉抖动。
     * 若想恢复 "过半加粗" 的旧效果传 0.5f 即可（指示器测量已与 paint 状态解耦，不会受影响）。
     */
    private val boldThreshold: Float = 1f,
) : ITabAnimator {
    private val argb = ArgbEvaluator()

    override fun apply(tab: View, position: Int, fraction: Float, provider: ITabProvider) {
        val tv = provider.findTitleView(tab) ?: tab as? TextView ?: return
        val scale = minScale + (maxScale - minScale) * fraction
        tv.scaleX = scale
        tv.scaleY = scale
        tv.setTextColor(argb.evaluate(fraction, unselectedColor, selectedColor) as Int)
        tv.paint.isFakeBoldText = fraction > boldThreshold
    }

    override fun reset(tab: View, provider: ITabProvider) {
        val tv = provider.findTitleView(tab) ?: tab as? TextView ?: return
        tv.scaleX = 1f
        tv.scaleY = 1f
        tv.paint.isFakeBoldText = false
        // 颜色回到非选中色，避免切到不动颜色的 animator 时残留选中色
        tv.setTextColor(unselectedColor)
    }
}

/**
 * 真实字号 + 颜色插值。
 * Tab 容器需预留最大字号宽度，否则会出现宽度抖动。
 */
class TextSizeColorAnimator(
    private val minSizeSp: Float = 14f,
    private val maxSizeSp: Float = 18f,
    private val unselectedColor: Int = Color.parseColor("#999999"),
    private val selectedColor: Int = Color.parseColor("#333333"),
    /** 同 [ScaleColorAnimator.boldThreshold]，默认只在完全选中时加粗以避免中间帧抖动。 */
    private val boldThreshold: Float = 1f,
) : ITabAnimator {
    private val argb = ArgbEvaluator()

    override fun apply(tab: View, position: Int, fraction: Float, provider: ITabProvider) {
        val tv = provider.findTitleView(tab) ?: tab as? TextView ?: return
        val size = minSizeSp + (maxSizeSp - minSizeSp) * fraction
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tv.setTextColor(argb.evaluate(fraction, unselectedColor, selectedColor) as Int)
        tv.paint.isFakeBoldText = fraction > boldThreshold
    }

    override fun reset(tab: View, provider: ITabProvider) {
        val tv = provider.findTitleView(tab) ?: tab as? TextView ?: return
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, minSizeSp)
        tv.paint.isFakeBoldText = false
        tv.setTextColor(unselectedColor)
    }
}

/** 整 Tab 缩放（影响整个 View，不仅是文字） */
class ZoomAnimator(
    private val minScale: Float = 1f,
    private val maxScale: Float = 1.2f,
) : ITabAnimator {
    override fun apply(tab: View, position: Int, fraction: Float, provider: ITabProvider) {
        val s = minScale + (maxScale - minScale) * fraction
        tab.scaleX = s
        tab.scaleY = s
    }

    override fun reset(tab: View, provider: ITabProvider) {
        tab.scaleX = 1f
        tab.scaleY = 1f
    }
}

/** 仅做颜色插值，方便和 [ZoomAnimator] / [FadeShiftAnimator] 等组合 */
class ColorAnimator(
    private val unselectedColor: Int = Color.parseColor("#999999"),
    private val selectedColor: Int = Color.parseColor("#333333"),
) : ITabAnimator {
    private val argb = ArgbEvaluator()

    override fun apply(tab: View, position: Int, fraction: Float, provider: ITabProvider) {
        val tv = provider.findTitleView(tab) ?: tab as? TextView ?: return
        tv.setTextColor(argb.evaluate(fraction, unselectedColor, selectedColor) as Int)
    }

    override fun reset(tab: View, provider: ITabProvider) {
        val tv = provider.findTitleView(tab) ?: tab as? TextView ?: return
        tv.setTextColor(unselectedColor)
    }
}

/** Alpha 渐变 + 向上轻微位移，适合沉浸式 Tab */
class FadeShiftAnimator(
    private val minAlpha: Float = 0.5f,
    private val translationYpx: Float = 0f,
) : ITabAnimator {
    override fun apply(tab: View, position: Int, fraction: Float, provider: ITabProvider) {
        tab.alpha = minAlpha + (1f - minAlpha) * fraction
        tab.translationY = -translationYpx * fraction
    }

    override fun reset(tab: View, provider: ITabProvider) {
        tab.alpha = 1f
        tab.translationY = 0f
    }
}

/** 组合多个 animator，按顺序应用 */
class CompositeAnimator(private vararg val animators: ITabAnimator) : ITabAnimator {
    override fun apply(tab: View, position: Int, fraction: Float, provider: ITabProvider) {
        animators.forEach { it.apply(tab, position, fraction, provider) }
    }

    override fun reset(tab: View, provider: ITabProvider) {
        animators.forEach { it.reset(tab, provider) }
    }
}
