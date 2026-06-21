package com.chat.uifoundation.nestedheader

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.graphics.ColorUtils
import com.chat.uifoundation.R

/**
 * 模仿 CollapsingToolbarLayout 里 collapsing title 的一个简化版：
 *
 *   - expanded（headerOffset = 0）：大字号、靠左下角的"自然"位置
 *   - collapsed（headerOffset = maxOffset）：小字号、回到自然左上对齐
 *   - 中间状态对 textSize、translationX/Y、textColor 做线性插值
 *
 * 通过 scaleX/scaleY + translation 实现，**不实时改 textSize**，避免每帧 measure 抖动。
 *
 * 自动注册到上层 NestedHeaderLayout 的 offset listener，不需要业务手写绑定。
 * 一般作为 BEHAVIOR_PIN 子放在 NestedHeaderLayout 里；也可以单独放到 Activity 任何位置，
 * 然后调用 [bindTo] 手动绑。
 */
class CollapsingTitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val expandedTextSizePx: Float
    private val collapsedTextSizePx: Float
    private val expandedColor: Int
    private val collapsedColor: Int

    /** expanded 状态相对自然布局位置的偏移（X 正向 = 向右，Y 正向 = 向下） */
    var expandedMarginStart: Int
        private set
    var expandedMarginTop: Int
        private set

    /** collapsed 状态相对自然布局位置的偏移（默认 0 = 用 layout 的 gravity/padding 决定的自然位置） */
    var collapsedMarginStart: Int
        private set
    var collapsedMarginTop: Int
        private set

    private var hostLayout: NestedHeaderLayout? = null
    private val listener = NestedHeaderLayout.OnHeaderOffsetChangedListener { _, offset, max ->
        val ratio = if (max > 0) offset.toFloat() / max else 0f
        applyState(ratio)
    }

    init {
        val ta = context.obtainStyledAttributes(attrs, R.styleable.CollapsingTitleView)
        expandedTextSizePx = ta.getDimension(
            R.styleable.CollapsingTitleView_ct_expandedTextSize,
            sp(24f)
        )
        collapsedTextSizePx = ta.getDimension(
            R.styleable.CollapsingTitleView_ct_collapsedTextSize,
            sp(16f)
        )
        expandedColor = ta.getColor(
            R.styleable.CollapsingTitleView_ct_expandedTextColor,
            currentTextColor
        )
        collapsedColor = ta.getColor(
            R.styleable.CollapsingTitleView_ct_collapsedTextColor,
            currentTextColor
        )
        expandedMarginStart = ta.getDimensionPixelSize(
            R.styleable.CollapsingTitleView_ct_expandedMarginStart,
            0
        )
        expandedMarginTop = ta.getDimensionPixelSize(
            R.styleable.CollapsingTitleView_ct_expandedMarginTop,
            0
        )
        collapsedMarginStart = ta.getDimensionPixelSize(
            R.styleable.CollapsingTitleView_ct_collapsedMarginStart,
            0
        )
        collapsedMarginTop = ta.getDimensionPixelSize(
            R.styleable.CollapsingTitleView_ct_collapsedMarginTop,
            0
        )
        ta.recycle()

        // 用 collapsed 尺寸作为基准 textSize（不变），靠 scale 表达 expanded 的"放大"效果
        setTextSize(TypedValue.COMPLEX_UNIT_PX, collapsedTextSizePx)
        // 缩放轴心放在左上角；这样 scale 时左对齐保持不变，往右下放大
        pivotX = 0f
        pivotY = 0f
        setTextColor(collapsedColor)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val parent = findHost()
        if (parent != null) bindTo(parent)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hostLayout?.removeOnOffsetChangedListener(listener)
        hostLayout = null
    }

    private fun findHost(): NestedHeaderLayout? {
        var p: View? = parent as? View
        while (p != null) {
            if (p is NestedHeaderLayout) return p
            p = p.parent as? View
        }
        return null
    }

    /** 手动指定要挂的 NestedHeaderLayout（自动发现失败或跨容器场景）。 */
    fun bindTo(layout: NestedHeaderLayout) {
        hostLayout?.removeOnOffsetChangedListener(listener)
        hostLayout = layout
        layout.addOnOffsetChangedListener(listener)
        // 程序化添加 / include 异步 inflate 等场景下 NHL 自己 onFinishInflate 早就跑完，
        // 这里主动通知一下，把祖先链 clipChildren 关掉，避免 expanded 标题被裁。
        layout.disableClipChildrenAlongAncestorsOf(this)
        // 注册时立刻刷一次当前状态
        val ratio = if (layout.maxOffset > 0) layout.headerOffset.toFloat() / layout.maxOffset else 0f
        applyState(ratio)
    }

    /**
     * ratio = 0 → expanded（大字号 + expanded margin）
     * ratio = 1 → collapsed（小字号 + collapsed margin）
     * 中间状态对 scale、translationX/Y、color 都做线性插值。
     *
     * 自动 cap：把 translationY 上拉到不让"视觉 bottom"超过 host.firstScrollChildBottom，
     * 避免 expanded 状态字体本体延伸进下面 innerRv 等子的 Y 段。
     */
    private fun applyState(ratio: Float) {
        val r = ratio.coerceIn(0f, 1f)
        val maxScale = if (collapsedTextSizePx > 0) expandedTextSizePx / collapsedTextSizePx else 1f
        val scale = lerp(maxScale, 1f, r)
        scaleX = scale
        scaleY = scale

        val rawTransX = lerp(expandedMarginStart.toFloat(), collapsedMarginStart.toFloat(), r)
        var rawTransY = lerp(expandedMarginTop.toFloat(), collapsedMarginTop.toFloat(), r)

        // 自动 cap：保证 view 在 host 中的视觉 bottom <= firstScrollChildBottom
        val host = hostLayout
        if (host != null && host.firstScrollChildBottom > 0) {
            val topInHost = computeTopInHost(host)
            val visualBottom = topInHost + rawTransY + height * scale
            val maxBottom = host.firstScrollChildBottom.toFloat()
            if (visualBottom > maxBottom) {
                rawTransY -= visualBottom - maxBottom
            }
        }

        translationX = rawTransX
        translationY = rawTransY

        setTextColor(ColorUtils.blendARGB(expandedColor, collapsedColor, r))
    }

    /**
     * 从 this 一路向上累加 view.top 直到 host，返回 layout 锚点在 host 中的 Y。
     * 不计入 this 自身和中间祖先的 translationY ——
     * 那部分是 pin/scrim 等 behavior 用来跟 offset 联动的"运动"，不属于"层级关系"。
     */
    private fun computeTopInHost(host: View): Float {
        var top = 0f
        var v: View = this
        while (true) {
            val p = v.parent as? View ?: return top
            top += v.top.toFloat()
            if (p === host) return top
            v = p
        }
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
