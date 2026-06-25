package com.chat.uifoundation.widget

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.TypedArray
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.content.withStyledAttributes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.chat.uifoundation.R


/**
 * 通用标题栏控件。
 */
class TitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        const val ICON_POS_START = 0
        const val ICON_POS_END = 1
        const val ICON_POS_TOP = 2
        const val ICON_POS_BOTTOM = 3
    }

    /** 公开 API 的目标定位：三段（左/中/右）× 两级（主/副），共 6 个槽位。 */
    enum class Target { LEFT, LEFT_SUB, CENTER, CENTER_SUB, RIGHT, RIGHT_SUB }

    /** 自定义 view 的段位（左/中/右），粒度比 [Target] 粗 —— 整个段一起被自定义 view 替换。 */
    enum class Section { LEFT, CENTER, RIGHT }

    val leftIcon: ImageView
    val leftText: TextView
    val leftSubIcon: ImageView
    val leftSubText: TextView

    val centerIcon: ImageView
    val centerText: TextView
    val centerSubIcon: ImageView
    val centerSubText: TextView

    val rightIcon: ImageView
    val rightText: TextView
    val rightSubIcon: ImageView
    val rightSubText: TextView

    private val leftContainer: LinearLayout
    private val centerContainer: LinearLayout
    private val rightContainer: LinearLayout

    /** XML 显式设置的可见性。存在 = 强制使用该值；不存在 = 走内容自动判断。 */
    private val explicitVisible = HashMap<View, Boolean>()

    /** 装监听器时的初始 top padding，状态栏 inset 在此基础上叠加，避免重复累计。 */
    private var basePaddingTop = 0

    /** 标记某段是否处于「自定义 view 模式」。处于该模式时 refreshSection 跳过默认行显隐管理。 */
    private val customMode = HashMap<LinearLayout, Boolean>()

    /** 每个 icon 的 hitExpand 像素；onLayout 后通过挂在 TitleView 根上的 TouchDelegate 扩大点击区。 */
    private val iconHitExpands = HashMap<ImageView, Int>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        leftIcon = ImageView(context)
        leftText = TextView(context)
        leftSubIcon = ImageView(context)
        leftSubText = TextView(context)
        leftContainer = buildSection(leftIcon, leftText, leftSubIcon, leftSubText, centerAligned = false)
        addView(leftContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        centerIcon = ImageView(context)
        centerText = TextView(context)
        centerSubIcon = ImageView(context)
        centerSubText = TextView(context)
        centerContainer = buildSection(centerIcon, centerText, centerSubIcon, centerSubText, centerAligned = true)
        addView(centerContainer, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        rightIcon = ImageView(context)
        rightText = TextView(context)
        rightSubIcon = ImageView(context)
        rightSubText = TextView(context)
        rightContainer = buildSection(rightIcon, rightText, rightSubIcon, rightSubText, centerAligned = false)
        addView(rightContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        var fitStatusBar = true
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.TitleView) {
                applyAttrs(this)
                fitStatusBar = getBoolean(R.styleable.TitleView_tv_fitStatusBar, true)
            }
        }
        if (fitStatusBar) installStatusBarInset()
        refreshVisibility()
    }

    private fun installStatusBarInset() {
        basePaddingTop = paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, basePaddingTop + top, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    private fun buildSection(
        icon: ImageView, text: TextView, subIcon: ImageView, subText: TextView,
        centerAligned: Boolean
    ): LinearLayout {
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = if (centerAligned) Gravity.CENTER else Gravity.CENTER_VERTICAL
        }
        container.addView(buildRow(icon, text, centerAligned))
        container.addView(buildRow(subIcon, subText, centerAligned))
        return container
    }

    private fun buildRow(icon: ImageView, text: TextView, centerAligned: Boolean): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = if (centerAligned) Gravity.CENTER else Gravity.CENTER_VERTICAL
        }
        icon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        row.addView(icon, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        row.addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun applyAttrs(ta: TypedArray) {
        parseExplicitVisible(ta, leftIcon, R.styleable.TitleView_tv_leftIconVisible)
        parseExplicitVisible(ta, leftText, R.styleable.TitleView_tv_leftTextVisible)
        parseExplicitVisible(ta, leftSubIcon, R.styleable.TitleView_tv_leftSubIconVisible)
        parseExplicitVisible(ta, leftSubText, R.styleable.TitleView_tv_leftSubTextVisible)
        parseExplicitVisible(ta, centerIcon, R.styleable.TitleView_tv_centerIconVisible)
        parseExplicitVisible(ta, centerText, R.styleable.TitleView_tv_centerTextVisible)
        parseExplicitVisible(ta, centerSubIcon, R.styleable.TitleView_tv_centerSubIconVisible)
        parseExplicitVisible(ta, centerSubText, R.styleable.TitleView_tv_centerSubTextVisible)
        parseExplicitVisible(ta, rightIcon, R.styleable.TitleView_tv_rightIconVisible)
        parseExplicitVisible(ta, rightText, R.styleable.TitleView_tv_rightTextVisible)
        parseExplicitVisible(ta, rightSubIcon, R.styleable.TitleView_tv_rightSubIconVisible)
        parseExplicitVisible(ta, rightSubText, R.styleable.TitleView_tv_rightSubTextVisible)

        applyImage(
            leftIcon,
            ta.getDrawable(R.styleable.TitleView_tv_leftIcon),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_leftIconSize, 0),
            ta.getColorStateList(R.styleable.TitleView_tv_leftIconTint),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_leftIconHitExpand, 0)
        )
        applyText(
            leftText,
            ta.getString(R.styleable.TitleView_tv_leftText),
            ta.getColorStateList(R.styleable.TitleView_tv_leftTextColor),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_leftTextSize, 0)
        )
        applyImage(
            leftSubIcon,
            ta.getDrawable(R.styleable.TitleView_tv_leftSubIcon),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_leftSubIconSize, 0),
            ta.getColorStateList(R.styleable.TitleView_tv_leftSubIconTint),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_leftSubIconHitExpand, 0)
        )
        applyText(
            leftSubText,
            ta.getString(R.styleable.TitleView_tv_leftSubText),
            ta.getColorStateList(R.styleable.TitleView_tv_leftSubTextColor),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_leftSubTextSize, 0)
        )
        applyIconPosition(
            leftContainer.getChildAt(0) as LinearLayout, leftIcon, leftText,
            ta.getInt(R.styleable.TitleView_tv_leftIconPosition, ICON_POS_START), centerAligned = false
        )
        applyIconPosition(
            leftContainer.getChildAt(1) as LinearLayout, leftSubIcon, leftSubText,
            ta.getInt(R.styleable.TitleView_tv_leftSubIconPosition, ICON_POS_START), centerAligned = false
        )
        applyMarginPadding(leftIcon, ta, R.styleable.TitleView_tv_leftIconMargin, R.styleable.TitleView_tv_leftIconPadding)
        applyMarginPadding(leftText, ta, R.styleable.TitleView_tv_leftTextMargin, R.styleable.TitleView_tv_leftTextPadding)
        applyMarginPadding(leftSubIcon, ta, R.styleable.TitleView_tv_leftSubIconMargin, R.styleable.TitleView_tv_leftSubIconPadding)
        applyMarginPadding(leftSubText, ta, R.styleable.TitleView_tv_leftSubTextMargin, R.styleable.TitleView_tv_leftSubTextPadding)

        applyImage(
            centerIcon,
            ta.getDrawable(R.styleable.TitleView_tv_centerIcon),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_centerIconSize, 0),
            ta.getColorStateList(R.styleable.TitleView_tv_centerIconTint),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_centerIconHitExpand, 0)
        )
        applyText(
            centerText,
            ta.getString(R.styleable.TitleView_tv_centerText),
            ta.getColorStateList(R.styleable.TitleView_tv_centerTextColor),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_centerTextSize, 0)
        )
        applyImage(
            centerSubIcon,
            ta.getDrawable(R.styleable.TitleView_tv_centerSubIcon),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_centerSubIconSize, 0),
            ta.getColorStateList(R.styleable.TitleView_tv_centerSubIconTint),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_centerSubIconHitExpand, 0)
        )
        applyText(
            centerSubText,
            ta.getString(R.styleable.TitleView_tv_centerSubText),
            ta.getColorStateList(R.styleable.TitleView_tv_centerSubTextColor),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_centerSubTextSize, 0)
        )
        applyIconPosition(
            centerContainer.getChildAt(0) as LinearLayout, centerIcon, centerText,
            ta.getInt(R.styleable.TitleView_tv_centerIconPosition, ICON_POS_START), centerAligned = true
        )
        applyIconPosition(
            centerContainer.getChildAt(1) as LinearLayout, centerSubIcon, centerSubText,
            ta.getInt(R.styleable.TitleView_tv_centerSubIconPosition, ICON_POS_START), centerAligned = true
        )
        applyMarginPadding(centerIcon, ta, R.styleable.TitleView_tv_centerIconMargin, R.styleable.TitleView_tv_centerIconPadding)
        applyMarginPadding(centerText, ta, R.styleable.TitleView_tv_centerTextMargin, R.styleable.TitleView_tv_centerTextPadding)
        applyMarginPadding(centerSubIcon, ta, R.styleable.TitleView_tv_centerSubIconMargin, R.styleable.TitleView_tv_centerSubIconPadding)
        applyMarginPadding(centerSubText, ta, R.styleable.TitleView_tv_centerSubTextMargin, R.styleable.TitleView_tv_centerSubTextPadding)

        applyImage(
            rightIcon,
            ta.getDrawable(R.styleable.TitleView_tv_rightIcon),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_rightIconSize, 0),
            ta.getColorStateList(R.styleable.TitleView_tv_rightIconTint),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_rightIconHitExpand, 0)
        )
        applyText(
            rightText,
            ta.getString(R.styleable.TitleView_tv_rightText),
            ta.getColorStateList(R.styleable.TitleView_tv_rightTextColor),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_rightTextSize, 0)
        )
        applyImage(
            rightSubIcon,
            ta.getDrawable(R.styleable.TitleView_tv_rightSubIcon),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_rightSubIconSize, 0),
            ta.getColorStateList(R.styleable.TitleView_tv_rightSubIconTint),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_rightSubIconHitExpand, 0)
        )
        applyText(
            rightSubText,
            ta.getString(R.styleable.TitleView_tv_rightSubText),
            ta.getColorStateList(R.styleable.TitleView_tv_rightSubTextColor),
            ta.getDimensionPixelSize(R.styleable.TitleView_tv_rightSubTextSize, 0)
        )
        applyIconPosition(
            rightContainer.getChildAt(0) as LinearLayout, rightIcon, rightText,
            ta.getInt(R.styleable.TitleView_tv_rightIconPosition, ICON_POS_START), centerAligned = false
        )
        applyIconPosition(
            rightContainer.getChildAt(1) as LinearLayout, rightSubIcon, rightSubText,
            ta.getInt(R.styleable.TitleView_tv_rightSubIconPosition, ICON_POS_START), centerAligned = false
        )
        applyMarginPadding(rightIcon, ta, R.styleable.TitleView_tv_rightIconMargin, R.styleable.TitleView_tv_rightIconPadding)
        applyMarginPadding(rightText, ta, R.styleable.TitleView_tv_rightTextMargin, R.styleable.TitleView_tv_rightTextPadding)
        applyMarginPadding(rightSubIcon, ta, R.styleable.TitleView_tv_rightSubIconMargin, R.styleable.TitleView_tv_rightSubIconPadding)
        applyMarginPadding(rightSubText, ta, R.styleable.TitleView_tv_rightSubTextMargin, R.styleable.TitleView_tv_rightSubTextPadding)

        val leftCustom = ta.getResourceId(R.styleable.TitleView_tv_leftCustomLayout, 0)
        if (leftCustom != 0) setCustomView(Section.LEFT, leftCustom)
        val centerCustom = ta.getResourceId(R.styleable.TitleView_tv_centerCustomLayout, 0)
        if (centerCustom != 0) setCustomView(Section.CENTER, centerCustom)
        val rightCustom = ta.getResourceId(R.styleable.TitleView_tv_rightCustomLayout, 0)
        if (rightCustom != 0) setCustomView(Section.RIGHT, rightCustom)
    }

    private fun applyIconPosition(
        row: LinearLayout, icon: ImageView, text: TextView,
        position: Int, centerAligned: Boolean
    ) {
        val horizontal = position == ICON_POS_START || position == ICON_POS_END
        val iconFirst = position == ICON_POS_START || position == ICON_POS_TOP
        row.removeAllViews()
        row.orientation = if (horizontal) HORIZONTAL else VERTICAL
        row.gravity = when {
            centerAligned -> Gravity.CENTER
            horizontal -> Gravity.CENTER_VERTICAL
            else -> Gravity.CENTER_HORIZONTAL
        }
        if (iconFirst) {
            row.addView(icon)
            row.addView(text)
        } else {
            row.addView(text)
            row.addView(icon)
        }
    }

    private fun parseExplicitVisible(ta: TypedArray, view: View, attrIdx: Int) {
        if (ta.hasValue(attrIdx)) explicitVisible[view] = ta.getBoolean(attrIdx, true)
    }

    private fun resolveVisible(view: View, autoVisible: Boolean): Boolean =
        explicitVisible[view] ?: autoVisible

    /**
     * 给单个控件应用 margin（外边距，应用到 MarginLayoutParams）和 padding（内边距，调用 setPadding）。
     * 均为四方向统一值；attr 未指定时跳过，不会清空已有值。
     */
    private fun applyMarginPadding(view: View, ta: TypedArray, marginIdx: Int, paddingIdx: Int) {
        if (ta.hasValue(marginIdx)) {
            val m = ta.getDimensionPixelSize(marginIdx, 0)
            val lp = view.layoutParams as? MarginLayoutParams
            if (lp != null) {
                lp.setMargins(m, m, m, m)
                view.layoutParams = lp
            }
        }
        if (ta.hasValue(paddingIdx)) {
            val p = ta.getDimensionPixelSize(paddingIdx, 0)
            view.setPadding(p, p, p, p)
        }
    }

    private fun applyImage(iv: ImageView, drawable: Drawable?, size: Int, tint: ColorStateList?, hitExpand: Int) {
        if (drawable != null) iv.setImageDrawable(drawable)
        if (size > 0) {
            val lp = iv.layoutParams
            lp.width = size
            lp.height = size
            iv.layoutParams = lp
        }
        // hitExpand 不再撑 ImageView，只记录起来，onLayout 时通过 TouchDelegate 扩大点击区。
        if (hitExpand > 0) iconHitExpands[iv] = hitExpand
        if (tint != null) iv.imageTintList = tint
    }

    private fun applyText(tv: TextView, text: CharSequence?, color: ColorStateList?, sizePx: Int) {
        if (!text.isNullOrEmpty()) tv.text = text
        if (color != null) tv.setTextColor(color)
        if (sizePx > 0) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        refreshVisibility()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * LinearLayout 默认布局下 centerContainer 占据「左右两段之间的剩余空间」，gravity=CENTER 只能让
     * 内容在剩余空间内居中 —— 当左右两段宽度不等（典型场景：只有返回键、右边空）时视觉中心会偏向另一侧。
     * 这里做后置修正：父类摆完后整体平移 centerContainer，使其几何中心对齐 TitleView 内容区中心
     * （去除 paddingLeft/Right）。
     *
     * 自定义 view 模式下跳过：自定义 view 通常希望占据左右之间的完整剩余空间（如搜索框），
     * 几何居中反而会让一侧贴边、一侧留缝。
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (customMode[centerContainer] != true) {
            val innerMid = (paddingLeft + (r - l) - paddingRight) / 2
            val containerMid = centerContainer.left + centerContainer.width / 2
            val shift = innerMid - containerMid
            if (shift != 0) centerContainer.offsetLeftAndRight(shift)
        }
        applyTouchDelegates()
    }

    /**
     * 为每个 hitExpand > 0 的 icon 通过 TouchDelegate 扩大点击区，挂在 TitleView 根上：
     * - 不撑大 ImageView，layout 不变（避免在 icon 上/文字下这类纵向叠加里产生视觉间距）
     * - 挂根而不是 row 是因为 row 的 bounds 紧贴 icon，挂 row 上的扩展 rect 会被裁回 icon 本身
     * - 多 icon 同时需要 hitExpand 时用 [CompositeTouchDelegate] 复合
     * 每次 onLayout 重建，自动跟随 icon 位置/visibility 变化。
     */
    private fun applyTouchDelegates() {
        val composite = CompositeTouchDelegate(this)
        for ((icon, expand) in iconHitExpands) {
            if (expand <= 0 || !icon.isVisible) continue
            val rect = Rect()
            icon.getHitRect(rect)
            var p: View = icon.parent as? View ?: continue
            while (p !== this) {
                rect.offset(p.left, p.top)
                p = p.parent as? View ?: return
            }
            rect.inset(-expand, -expand)
            rect.left = rect.left.coerceAtLeast(0)
            rect.top = rect.top.coerceAtLeast(0)
            rect.right = rect.right.coerceAtMost(width)
            rect.bottom = rect.bottom.coerceAtMost(height)
            composite.add(TouchDelegate(rect, icon))
        }
        touchDelegate = if (composite.isEmpty()) null else composite
    }

    /**
     * 多个 TouchDelegate 的简单复合 —— 因为 View.touchDelegate 字段只允许一个。
     * 依次分发；每个 inner delegate 处理事件前重置 event 位置（TouchDelegate 内部会改坐标）。
     */
    private class CompositeTouchDelegate(host: View) : TouchDelegate(Rect(), host) {
        private val delegates = ArrayList<TouchDelegate>(4)
        fun add(d: TouchDelegate) { delegates += d }
        fun isEmpty() = delegates.isEmpty()
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            var handled = false
            for (d in delegates) {
                event.setLocation(x, y)
                handled = d.onTouchEvent(event) || handled
            }
            event.setLocation(x, y)
            return handled
        }
    }

    private fun refreshVisibility() {
        refreshSection(leftContainer, leftIcon, leftText, leftSubIcon, leftSubText)
        refreshSection(centerContainer, centerIcon, centerText, centerSubIcon, centerSubText)
        refreshSection(rightContainer, rightIcon, rightText, rightSubIcon, rightSubText)
    }

    private fun refreshSection(
        container: LinearLayout,
        ic1: ImageView, tv1: TextView,
        ic2: ImageView, tv2: TextView
    ) {
        if (customMode[container] == true) {
            // 自定义 view 模式：默认行整体隐藏，容器保持可见以渲染自定义 view
            container.getChildAt(0).isVisible = false
            container.getChildAt(1).isVisible = false
            container.isVisible = true
            return
        }
        ic1.isVisible = resolveVisible(ic1, ic1.drawable != null)
        tv1.isVisible = resolveVisible(tv1, !tv1.text.isNullOrEmpty())
        ic2.isVisible = resolveVisible(ic2, ic2.drawable != null)
        tv2.isVisible = resolveVisible(tv2, !tv2.text.isNullOrEmpty())
        val row1 = container.getChildAt(0)
        val row2 = container.getChildAt(1)
        row1.visibility = if (ic1.isVisible || tv1.isVisible) VISIBLE else GONE
        row2.visibility = if (ic2.isVisible || tv2.isVisible) VISIBLE else GONE
        container.visibility = if (row1.isVisible || row2.isVisible) VISIBLE else GONE
    }

    // ========== Public API ==========

    /** 设置图片资源。传 null 等同于清空。 */
    fun setIcon(target: Target, drawable: Drawable?) {
        iconOf(target).setImageDrawable(drawable)
    }

    /** 设置图片资源（资源 id）。 */
    fun setIcon(target: Target, @DrawableRes resId: Int) {
        iconOf(target).setImageResource(resId)
    }

    /** 设置图片着色（单色）。 */
    fun setIconTint(target: Target, @ColorInt color: Int) {
        iconOf(target).imageTintList = ColorStateList.valueOf(color)
    }

    /** 设置图片着色（支持 state list）。传 null 清除着色。 */
    fun setIconTint(target: Target, tint: ColorStateList?) {
        iconOf(target).imageTintList = tint
    }

    /** 设置图片视觉尺寸（正方形，单位 px）。与 hitExpand 解耦，不会互相影响。 */
    fun setIconSize(target: Target, sizePx: Int) {
        val iv = iconOf(target)
        val lp = iv.layoutParams
        lp.width = sizePx
        lp.height = sizePx
        iv.layoutParams = lp
    }

    /**
     * 设置图片点击区域向四周外扩的像素，图片视觉尺寸保持不变；通过 TouchDelegate 实现，不占 layout 空间。
     * 传 0 清除。下一次 onLayout 生效。
     */
    fun setIconHitExpand(target: Target, expandPx: Int) {
        val iv = iconOf(target)
        if (expandPx > 0) iconHitExpands[iv] = expandPx else iconHitExpands.remove(iv)
        requestLayout()
    }

    /** 强制显示/隐藏图片：true 强制显示，false 强制隐藏，会覆盖内容自动判断。 */
    fun setIconVisible(target: Target, visible: Boolean) {
        explicitVisible[iconOf(target)] = visible
        requestLayout()
    }

    /** 设置图片点击监听。 */
    fun setIconClickListener(target: Target, listener: OnClickListener?) {
        iconOf(target).setOnClickListener(listener)
    }

    /** 设置图片相对文字的位置，position 取 [ICON_POS_START] / [ICON_POS_END] / [ICON_POS_TOP] / [ICON_POS_BOTTOM]。 */
    fun setIconPosition(target: Target, position: Int) {
        applyIconPosition(rowOf(target), iconOf(target), textOf(target), position, isCenterAligned(target))
    }

    /** 设置文字内容。传 null 等同于清空。 */
    fun setText(target: Target, text: CharSequence?) {
        textOf(target).text = text
    }

    /** 设置文字内容（字符串资源）。 */
    fun setText(target: Target, @StringRes resId: Int) {
        textOf(target).setText(resId)
    }

    /** 设置文字颜色。 */
    fun setTextColor(target: Target, @ColorInt color: Int) {
        textOf(target).setTextColor(color)
    }

    /** 设置文字大小（sp）。 */
    fun setTextSize(target: Target, sp: Float) {
        textOf(target).textSize = sp
    }

    /** 强制显示/隐藏文字：true 强制显示，false 强制隐藏，会覆盖内容自动判断。 */
    fun setTextVisible(target: Target, visible: Boolean) {
        explicitVisible[textOf(target)] = visible
        requestLayout()
    }

    /** 设置文字点击监听。 */
    fun setTextClickListener(target: Target, listener: OnClickListener?) {
        textOf(target).setOnClickListener(listener)
    }

    /**
     * 用自定义 view 替换某段（左/中/右）的默认内容。传 view = null 清除自定义并恢复默认行。
     * 注意：该段的默认 icon/text 仍可被 [setIcon] / [setText] 等修改，但在自定义模式下它们被强制 GONE，
     * 直到调用 setCustomView(section, null) 恢复。
     */
    fun setCustomView(section: Section, view: View?) {
        val container = sectionContainerOf(section)
        // 清掉之前的自定义 view（默认 2 个子 row，自定义在 index 2）
        while (container.childCount > 2) container.removeViewAt(2)
        if (view != null) {
            // 防御：若传入的 view 已挂在别处，先 detach，避免 IllegalStateException
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view)
            customMode[container] = true
        } else {
            customMode[container] = false
        }
        requestLayout()
    }

    /** inflate 一个布局作为自定义 view 加入对应段，返回 inflate 后的根 view。 */
    fun setCustomView(section: Section, @LayoutRes layoutId: Int): View {
        val container = sectionContainerOf(section)
        val view = LayoutInflater.from(context).inflate(layoutId, container, false)
        setCustomView(section, view)
        return view
    }

    /** 获取当前段的自定义 view；未设置时返回 null。 */
    fun getCustomView(section: Section): View? {
        val container = sectionContainerOf(section)
        return if (container.childCount > 2) container.getChildAt(2) else null
    }

    private fun sectionContainerOf(section: Section): LinearLayout = when (section) {
        Section.LEFT -> leftContainer
        Section.CENTER -> centerContainer
        Section.RIGHT -> rightContainer
    }

    private fun iconOf(target: Target): ImageView = when (target) {
        Target.LEFT -> leftIcon
        Target.LEFT_SUB -> leftSubIcon
        Target.CENTER -> centerIcon
        Target.CENTER_SUB -> centerSubIcon
        Target.RIGHT -> rightIcon
        Target.RIGHT_SUB -> rightSubIcon
    }

    private fun textOf(target: Target): TextView = when (target) {
        Target.LEFT -> leftText
        Target.LEFT_SUB -> leftSubText
        Target.CENTER -> centerText
        Target.CENTER_SUB -> centerSubText
        Target.RIGHT -> rightText
        Target.RIGHT_SUB -> rightSubText
    }

    private fun rowOf(target: Target): LinearLayout {
        val container = when (target) {
            Target.LEFT, Target.LEFT_SUB -> leftContainer
            Target.CENTER, Target.CENTER_SUB -> centerContainer
            Target.RIGHT, Target.RIGHT_SUB -> rightContainer
        }
        val isSub = target == Target.LEFT_SUB || target == Target.CENTER_SUB || target == Target.RIGHT_SUB
        return container.getChildAt(if (isSub) 1 else 0) as LinearLayout
    }

    private fun isCenterAligned(target: Target): Boolean =
        target == Target.CENTER || target == Target.CENTER_SUB
}
