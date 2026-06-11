package com.chat.mylibrary.tablayout

import android.animation.ValueAnimator
import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs
import androidx.core.view.isNotEmpty
import androidx.core.view.isEmpty

/**
 * 三层抽象：
 * - [ITabProvider]  控制 Tab View 怎么生成
 * - [IIndicator]    控制指示器怎么画
 * - [ITabAnimator]  控制 Tab 滑动过程中的样式插值
 *
 * 绑定 ViewPager2：调用 [bindViewPager] 或直接构造 [MagicTabLayoutMediator]。
 */
class MagicTabLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    private val tabContainer: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
        clipChildren = false
        clipToPadding = false
    }

    private val stage: FrameLayout = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        addView(tabContainer, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.MATCH_PARENT,
        ))
    }

    var tabProvider: ITabProvider = TextTabProvider()
        set(value) {
            if (field === value) return
            field = value
            if (titles.isNotEmpty()) rebuildTabs()
        }

    var animator: ITabAnimator = ScaleColorAnimator()
        set(value) {
            if (field === value) return
            val oldAnimator = field
            field = value
            if (tabContainer.isNotEmpty()) post {
                // 旧 animator 自己清掉它写过的属性，避免新 animator 之上叠加旧残留
                for (i in 0 until tabContainer.childCount) {
                    tabContainer.getChildAt(i)?.let { oldAnimator.reset(it, tabProvider) }
                }
                invalidateFractionCache()
                stabilizeTabWidths()
                applyState()
            }
        }

    val badge: BadgeOverlay = BadgeOverlay(context)

    private var _indicator: IIndicator = LineIndicator(context)
    var indicator: IIndicator
        get() = _indicator
        set(value) {
            removeIndicatorViewIfNeeded(_indicator)
            _indicator = value
            if (tabContainer.isNotEmpty()) {
                value.attach(stage, tabContainer)
                ensureBadgeOnTop()
                post {
                    invalidateFractionCache()
                    applyState()
                }
            }
        }

    /**
     * Tab 排布模式：
     * - [TabMode.SCROLLABLE]  WRAP_CONTENT 不等宽，超出可视宽度可横向滚动（默认）
     * - [TabMode.FIXED]       等分铺满可视宽度，禁止滚动
     * - [TabMode.AUTO]        总宽 <= 可视宽度 → FIXED；否则 SCROLLABLE
     */
    var tabMode: TabMode = TabMode.SCROLLABLE
        set(value) {
            if (field == value) return
            field = value
            if (tabContainer.isNotEmpty()) post { applyTabMode() }
        }

    /** 点击 Tab 切换的动画时长，毫秒；<=0 表示不带动画 */
    var clickAnimDuration: Long = 220L

    /**
     * 点击 Tab 触发切换动画时，立即通知外部翻页器跳到目标位。
     * Mediator.attach() 会注入：`viewPager.setCurrentItem(target, false)`，让 VP2 瞬切到目标页，
     * 不滚过中间页；indicator 由内部 ValueAnimator 平滑过渡。
     */
    var onJumpUnderlyingPager: ((position: Int) -> Unit)? = null

    /** 可视区前后预留的缓冲（按可视宽度的倍数），避免快滑入屏时样式空白 */
    var visibleBufferRatio: Float = 0.5f

    /** 脏检测阈值：本帧 fraction 与上帧差异 < 该值时跳过 apply */
    var dirtyThreshold: Float = 0.005f

    /**
     * 是否把每个 tab 的最小宽度锁定到 "完全选中态" 的测量宽度，避免选中态切换时的整排抖动。
     * 仅对 SCROLLABLE / AUTO(非等分) 有效；FIXED 模式天然稳定。默认 true。
     */
    var stabilizeTabWidth: Boolean = true

    private var lastFractions: FloatArray = FloatArray(0)

    private val titles = mutableListOf<CharSequence>()
    private var currentPosition = 0
    private var positionOffset = 0f
    private var lastDispatchedSelected = -1

    private val tabSelectedListeners = mutableListOf<(position: Int) -> Unit>()

    private var clickAnimator: ValueAnimator? = null

    private var inPageScrollDispatch = false

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = false
        clipChildren = false
        clipToPadding = false
        addView(stage, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /* -------------------- 公共 API -------------------- */

    /** 设置标题列表。再次调用会清空并重建。 */
    fun setTitles(list: List<CharSequence>) {
        titles.clear(); titles.addAll(list)
        rebuildTabs()
    }

    fun addOnTabSelectedListener(l: (position: Int) -> Unit) {
        tabSelectedListeners += l
    }

    fun removeOnTabSelectedListener(l: (position: Int) -> Unit) {
        tabSelectedListeners -= l
    }

    /** 程序化选中（带动画） */
    fun selectTab(position: Int, smooth: Boolean = true) {
        if (position !in 0 until tabContainer.childCount) return
        if (smooth && clickAnimDuration > 0L) animateTo(position) else jumpTo(position)
    }

    val selectedPosition: Int get() = currentPosition

    val tabCount: Int get() = tabContainer.childCount

    fun getTabAt(position: Int): View? = tabContainer.getChildAt(position)

    /* -------------------- ViewPager2 绑定 -------------------- */

    private var boundMediator: MagicTabLayoutMediator? = null

    /**
     * 一行绑定 ViewPager2。再次调用会自动 detach 上一次的 mediator。
     * 需要精细控制时直接构造 [MagicTabLayoutMediator]。
     */
    fun bindViewPager(viewPager: ViewPager2, titleProvider: (Int) -> CharSequence) {
        boundMediator?.detach()
        boundMediator = MagicTabLayoutMediator(this, viewPager, titleProvider).also { it.attach() }
    }

    fun bindViewPager(viewPager: ViewPager2, titles: List<CharSequence>) {
        bindViewPager(viewPager) { titles[it] }
    }

    fun unbindViewPager() {
        boundMediator?.detach()
        boundMediator = null
    }

    override fun onDetachedFromWindow() {
        unbindViewPager()
        super.onDetachedFromWindow()
    }

    /* -------------------- 角标 API -------------------- */

    fun showBadgeDot(position: Int) {
        if (position !in 0 until tabCount) return
        badge.set(position, BadgeStyle.Dot)
    }

    /** count<=0 自动隐藏 */
    fun showBadgeCount(position: Int, count: Int, cap: Int = 99) {
        if (position !in 0 until tabCount) return
        if (count <= 0) hideBadge(position)
        else badge.set(position, BadgeStyle.Count(count, cap))
    }

    fun showBadgeText(position: Int, text: CharSequence) {
        if (position !in 0 until tabCount) return
        badge.set(position, BadgeStyle.Text(text))
    }

    fun hideBadge(position: Int) {
        badge.set(position, null)
    }

    fun hideAllBadges() {
        badge.clearAll()
    }

    /* -------------------- ViewPager2 联动入口 -------------------- */

    /**
     * 由 Mediator 调用。只更新视觉态，不基于 offset 提前 dispatch 选中事件
     * （否则 listener 反向调 VP2.setCurrentItem 会干扰 VP2 自己的 settle 决策）。
     */
    internal fun onPageScrolled(position: Int, offset: Float) {
        if (clickAnimator?.isRunning == true) return  // 自身正在跑切换动画，忽略 VP2 中间帧
        currentPosition = position
        positionOffset = offset
        inPageScrollDispatch = true
        try {
            scrollToCenter(position, offset)
        } finally {
            inPageScrollDispatch = false
        }
        applyState()
    }

    internal fun onPageSelected(position: Int) {
        if (clickAnimator?.isRunning == true) return
        // 不立即 jumpTo：settle 阶段位置同步由 onPageScrollStateChanged(SETTLING) 那条路接管；
        // 这里只 dispatch 选中事件。
        dispatchSelectedIfNeeded(position)
    }


    /* -------------------- 内部 -------------------- */

    private fun rebuildTabs() {
        clickAnimator?.cancel()
        clickAnimator = null
        invalidateFractionCache()
        removeIndicatorViewIfNeeded(_indicator)
        tabContainer.removeAllViews()

        titles.forEachIndexed { i, t ->
            val v = tabProvider.create(context, i, t)
            v.setOnClickListener { selectTab(i, smooth = true) }
            tabContainer.addView(
                v,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        applyTabMode()

        _indicator.attach(stage, tabContainer)
        ensureBadgeOnTop()
        currentPosition = currentPosition.coerceIn(0, (tabCount - 1).coerceAtLeast(0))
        positionOffset = 0f
        lastDispatchedSelected = -1

        post {
            stabilizeTabWidths()
            applyState()
        }
    }

    /**
     * 用 fraction=1 测每个 tab，把"选中态宽度"锁到 minimumWidth：
     * - SCROLLABLE：锁到 tab 自身，避免相邻 tab 被推动
     * - FIXED：清零 tab 自身的 minWidth（防破坏等分），仅锁内部标题 TextView
     */
    private fun stabilizeTabWidths() {
        if (!stabilizeTabWidth) return
        val count = tabContainer.childCount
        if (count == 0) return

        val isFixed = tabContainer.layoutParams?.width == LayoutParams.MATCH_PARENT
        val heightSpec = if (height > 0)
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
        else
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val widthSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        for (i in 0 until count) {
            val tab = tabContainer.getChildAt(i) ?: continue
            animator.apply(tab, i, 1f, tabProvider)
            tab.measure(widthSpec, heightSpec)

            if (isFixed) {
                tab.minimumWidth = 0
            } else {
                tab.minimumWidth = tab.measuredWidth
            }

            val tv = tabProvider.findTitleView(tab)
            if (tv != null && tv !== tab) {
                tv.minimumWidth = tv.measuredWidth
            }
        }
        invalidateFractionCache()
    }

    private fun applyTabMode() {
        val fixed = when (tabMode) {
            TabMode.FIXED -> true
            TabMode.SCROLLABLE -> false
            TabMode.AUTO -> {
                if (width <= 0) {
                    post { applyTabMode() }
                    false
                } else {
                    val h = if (height > 0) height else MeasureSpec.UNSPECIFIED
                    tabContainer.measure(
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST)
                    )
                    tabContainer.measuredWidth <= width
                }
            }
        }

        for (i in 0 until tabContainer.childCount) {
            val child = tabContainer.getChildAt(i)
            val lp = (child.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            if (fixed) {
                lp.width = 0
                lp.weight = 1f
            } else {
                lp.width = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
            }
            child.layoutParams = lp
        }

        if (fixed) {
            tabContainer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            isFillViewport = true
        } else {
            tabContainer.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
            isFillViewport = false
        }
        tabContainer.requestLayout()
        invalidateFractionCache()
        post {
            stabilizeTabWidths()
            applyState()
        }
    }

    private fun removeIndicatorViewIfNeeded(ind: IIndicator) {
        if (ind is View && ind.parent === stage) {
            stage.removeView(ind)
        }
    }

    private fun ensureBadgeOnTop() {
        (badge.parent as? android.view.ViewGroup)?.removeView(badge)
        stage.addView(
            badge,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        )
        badge.attachContainer(tabContainer)
    }

    private fun applyState() {
        val count = tabContainer.childCount
        if (count == 0) {
            _indicator.update(currentPosition, positionOffset)
            return
        }
        if (lastFractions.size != count) lastFractions = FloatArray(count) { Float.NaN }

        val buf = (width * visibleBufferRatio).toInt()
        val visibleLeft = scrollX - buf
        val visibleRight = scrollX + width + buf

        for (i in 0 until count) {
            val tab = tabContainer.getChildAt(i)
            val target = if (tab.right < visibleLeft || tab.left > visibleRight) {
                0f
            } else {
                computeSelectFraction(i)
            }
            val prev = lastFractions[i]
            if (!prev.isNaN() && abs(target - prev) < dirtyThreshold) continue
            lastFractions[i] = target
            animator.apply(tab, i, target, tabProvider)
        }
        _indicator.update(currentPosition, positionOffset)
        if (badge.hasAny) badge.invalidate()
    }

    private fun invalidateFractionCache() {
        lastFractions = FloatArray(0)
    }

    private fun computeSelectFraction(i: Int): Float {
        val virt = currentPosition + positionOffset
        val d = abs(i - virt)
        return (1f - d).coerceIn(0f, 1f)
    }

    private fun jumpTo(position: Int) {
        currentPosition = position
        positionOffset = 0f
        scrollToCenter(position, 0f)
        applyState()
        dispatchSelectedIfNeeded(position)
    }

    private fun animateTo(target: Int) {
        val count = tabContainer.childCount
        if (count == 0) return
        val startVirt = currentPosition + positionOffset
        val startIdx = currentPosition
        clickAnimator?.cancel()

        if (lastFractions.size != count) lastFractions = FloatArray(count) { Float.NaN }

        for (i in 0 until count) {
            if (i == startIdx || i == target) continue
            val prev = lastFractions[i]
            if (!prev.isNaN() && prev == 0f) continue
            lastFractions[i] = 0f
            tabContainer.getChildAt(i)?.let { animator.apply(it, i, 0f, tabProvider) }
        }

        // 抽出来给 onUpdate 和 onEnd 复用：onEnd 强制 progress=1f 走一次终态，
        // 修复部分设备/帧率下 ValueAnimator 最后一帧 progress<1.0 → onEnd 直接跳到 1.0
        // 造成的 "第一次没到正中间，下一帧到正中间" 的 1~10px 残影抖动。
        val runUpdateAt: (Float) -> Unit = { progress ->
            val virt = startVirt + (target - startVirt) * progress
            val p = virt.toInt().coerceIn(0, count - 1)
            val o = (virt - p).coerceIn(0f, 1f)
            currentPosition = p
            positionOffset = o
            scrollToCenter(p, o)
            applyJumpFraction(startIdx, 1f - progress)
            if (target != startIdx) applyJumpFraction(target, progress)
            _indicator.update(p, o)
            if (badge.hasAny) badge.invalidate()
        }

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = clickAnimDuration
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                runUpdateAt(it.animatedValue as Float)
            }
            addListener(onEnd = {
                runUpdateAt(1f)
                dispatchSelectedIfNeeded(target)
            })
        }
        clickAnimator = anim
        anim.start()
        onJumpUnderlyingPager?.invoke(target)
    }

    private fun applyJumpFraction(idx: Int, fraction: Float) {
        val tab = tabContainer.getChildAt(idx) ?: return
        val f = fraction.coerceIn(0f, 1f)
        if (idx !in lastFractions.indices) return
        val prev = lastFractions[idx]
        if (!prev.isNaN() && abs(f - prev) < dirtyThreshold) return
        lastFractions[idx] = f
        animator.apply(tab, idx, f, tabProvider)
    }

    private fun scrollToCenter(position: Int, offset: Float) {
        val cur = tabContainer.getChildAt(position) ?: return
        val next = tabContainer.getChildAt(position + 1)
        val curCenter = cur.left + cur.width / 2f
        val center = if (next == null) curCenter
        else curCenter + (next.left + next.width / 2f - curCenter) * offset
        val target = (center - width / 2f).toInt().coerceAtLeast(0)
        if (target == scrollX) return
        scrollTo(target, 0)
    }

    private fun dispatchSelectedIfNeeded(position: Int) {
        if (position == lastDispatchedSelected) return
        lastDispatchedSelected = position
        tabSelectedListeners.forEach { it(position) }
    }

    /** 内容没超过可视宽度就不拦截横向手势，避免和 ViewPager2 抢事件 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val needScroll = tabContainer.width > width
        return needScroll && super.onInterceptTouchEvent(ev)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (inPageScrollDispatch) return
        if (clickAnimator?.isRunning == true) return
        if (l != oldl && tabContainer.isNotEmpty()) applyState()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == oldw || tabContainer.isEmpty()) return
        if (tabMode == TabMode.AUTO) applyTabMode()
        invalidateFractionCache()
        post {
            scrollToCenter(currentPosition, positionOffset)
            applyState()
        }
    }

    /* -------------------- 状态保存 -------------------- */

    override fun onSaveInstanceState(): Parcelable {
        val sup = super.onSaveInstanceState()
        return SavedState(sup, currentPosition)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            currentPosition = state.position
            positionOffset = 0f
            invalidateFractionCache()
            post {
                scrollToCenter(currentPosition, 0f)
                applyState()
            }
        } else super.onRestoreInstanceState(state)
    }

    private class SavedState : BaseSavedState {
        val position: Int
        constructor(superState: Parcelable?, position: Int) : super(superState) { this.position = position }
        constructor(parcel: Parcel) : super(parcel) { this.position = parcel.readInt() }
        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags); out.writeInt(position)
        }
        companion object {
            @JvmField val CREATOR: Parcelable.Creator<SavedState> = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(p: Parcel) = SavedState(p)
                override fun newArray(size: Int) = arrayOfNulls<SavedState>(size)
            }
        }
    }
}

enum class TabMode { SCROLLABLE, FIXED, AUTO }

private inline fun ValueAnimator.addListener(crossinline onEnd: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) { onEnd() }
    })
}
