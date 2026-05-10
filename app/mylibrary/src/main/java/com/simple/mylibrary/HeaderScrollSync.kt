package com.simple.mylibrary

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * 让悬浮在内容前缘的Header卡片跟随滚动
 *
 * - 内容不足以滚动时：Header常驻显示
 * - 内容可滚动时：按滚动偏移把Header向滚动反方向平移收起
 * - 数据首次加载完成前：Header保持隐藏，避免"先显示后被滚动收起"的闪现
 *   （由调用方在初始数据/首屏滚动到位后调用[markInitialLoadDone]触发）
 *
 * 通过[Orientation]切换纵向/横向滚动场景。支持的滚动容器：
 * - 纵向：[RecyclerView]（vertical LayoutManager）、[NestedScrollView]
 * - 横向：[RecyclerView]（horizontal LayoutManager）、[HorizontalScrollView]
 *
 * ## 布局结构要求
 *
 * 1. [headerView]与[scrollableView]必须是同一个父布局的兄弟节点。父布局必须支持视图叠加，
 *    推荐[androidx.constraintlayout.widget.ConstraintLayout] / [android.widget.FrameLayout] /
 *    [android.widget.RelativeLayout]，**不要用[android.widget.LinearLayout]**（无法叠加）。
 *
 * 2. **绘制顺序**：[headerView]在XML中必须**声明在[scrollableView]之后**——ViewGroup按声明顺序绘制，
 *    后声明的画在上层；否则Header会被列表内容盖住。
 *
 * 3. [headerView]约束：
 *    - 纵向场景顶部对齐父容器（如`app:layout_constraintTop_toTopOf="parent"`），
 *      横向场景左侧对齐父容器（如`app:layout_constraintStart_toStartOf="parent"`）
 *    - 必须有非透明背景，否则平移时背后的列表内容会透出来
 *    - 父布局需提供[ViewGroup.MarginLayoutParams]（ConstraintLayout/FrameLayout/LinearLayout均满足），
 *      否则需通过`headerStartOffset`显式传入Header起点到parent起点的距离
 *
 * 4. [scrollableView]约束：
 *    - **仅接受[RecyclerView] / [NestedScrollView] / [HorizontalScrollView]**，
 *      其他类型会抛[IllegalArgumentException]
 *    - 必须从父容器对应方向的起点开始铺满（纵向`top_toTopOf="parent"`、横向`start_toStartOf="parent"`），
 *      不要在滚动方向上设`layout_margin`，留白由本类通过对应方向的`padding`维护
 *    - 推荐设置`android:clipToPadding="false"`（[RecyclerView]需自行配置；
 *      [NestedScrollView]/[HorizontalScrollView]本类自动开启）
 *    - [RecyclerView]的LayoutManager方向需与[Orientation]一致
 *
 * ## 调用流程
 *
 * 5. **必须调用[markInitialLoadDone]**：首屏数据加载并完成首次滚动到位后调用一次，否则Header会
 *    一直处于隐藏状态。若页面无异步加载，attach之后立刻调用即可
 *
 * 6. **不要手动改[headerView]的[View.setTranslationY]/[View.setTranslationX]**：本类托管该值，
 *    外部修改会被下一次滚动/布局回调覆盖
 *
 * 7. Header尺寸变化的场景（如根据数据条件显示/隐藏内部元素）无需额外处理，
 *    内部的[View.addOnLayoutChangeListener]会自动重算padding与位移；
 *    需要在某次数据变更后立刻同步可调用[syncHeaderTranslation]
 *
 * ## 易踩坑点
 *
 * 8. **滚动事件分发**：本类内部已占用了[RecyclerView.addOnScrollListener]/
 *    [NestedScrollView.setOnScrollChangeListener]/[HorizontalScrollView.setOnScrollChangeListener]。
 *    NSV与HorizontalScrollView原生只支持一个scroll change listener，业务若需要监听滚动，
 *    统一通过[addOnScrollChangeListener]/[removeOnScrollChangeListener]注册，
 *    本类会在执行内部位移同步后把(scrollX, scrollY, oldScrollX, oldScrollY)分发给所有外部listener
 *
 * 9. `extraPadding`含义：Header尺寸之外额外补给滚动容器对应方向起点的padding
 *    （首条内容与Header之间在滚动方向上的留白），建议传入设计稿中Header与列表的间距值
 *
 * 10. **不要重复attach同一对view**：会重复注册监听器，导致padding/位移被反复设置。
 *     视图被销毁后监听器自动随之回收，无需手动解绑
 *
 * 11. `headerStartOffset`含义：Header起点边到parent起点边的几何距离（纵向=离parent顶部多远，
 *     横向=离parent起边多远），决定「Header完全收起时需要平移的距离」以及滚动容器padding起点。
 *     默认从[ViewGroup.MarginLayoutParams]读取（纵向取topMargin、横向取marginStart，自动适配RTL）——
 *     如果Header起点留白不是用margin而是用padding/Space/Guideline实现的，需显式传入对应像素值，
 *     否则Header收起后会露一截。**不要和[extraPadding]混淆**：本参数描述Header的几何位置（参与平移距离计算），
 *     [extraPadding]描述Header与首条内容之间的视觉留白（不参与平移距离计算）
 *
 * 12. **方向单一**：每个实例仅处理[orientation]一个方向。同时存在双向滚动的需求请创建两个实例
 *
 * 13. **生命周期**：本类持有[headerView]与[scrollableView]的强引用，
 *     **不要把实例存到Application/单例等比View活得久的容器**，否则会泄漏Activity
 *
 * 14. **运行时Header改为GONE**：[View.GONE]不会触发[View.addOnLayoutChangeListener]，
 *     因此padding不会自动收回。如确有Header运行时隐藏需求，请在切换可见性后手动调用[syncHeaderTranslation]
 *     并自行调整滚动容器padding（或重新attach）
 *
 * 15. **重复attach同一[scrollableView]的注意**：[NestedScrollView]/[HorizontalScrollView]
 *     原生只允许一个scroll change listener，重复attach会让本类的listener被后者**静默替换**，
 *     导致先前实例失效；[RecyclerView]则会**重复注册**。
 *     务必避免对同一[scrollableView]多次attach
 */
class HeaderScrollSync private constructor(
    private val headerView: View,
    private val scrollableView: View,
    private val extraPadding: Int,
    private val headerMarginOverride: Int,
    private val orientation: Orientation,
) {

    private var initialLoadDone = false
    private val externalScrollListeners = mutableListOf<OnScrollChangeListener>()

    init {
        require(headerView !== scrollableView) {
            "headerView and scrollableView must be different views"
        }
        ensureScrollableSupported()
        if (scrollableView is NestedScrollView) scrollableView.clipToPadding = false
        if (scrollableView is HorizontalScrollView) scrollableView.clipToPadding = false
        headerView.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val extent = if (orientation == Orientation.VERTICAL) bottom - top else right - left
            val oldExtent = if (orientation == Orientation.VERTICAL) {
                oldBottom - oldTop
            } else {
                oldRight - oldLeft
            }
            applyContainerPadding(extent + extraPadding)
            if (extent != oldExtent) {
                syncHeaderTranslation()
            }
        }
        attachInternalScrollListener()
        if (headerView.isLaidOut) {
            applyContainerPadding(headerExtent() + extraPadding)
            syncHeaderTranslation()
        }
    }

    private fun ensureScrollableSupported() {
        when (scrollableView) {
            is RecyclerView, is NestedScrollView, is HorizontalScrollView -> Unit
            else -> throw IllegalArgumentException(
                "scrollableView must be RecyclerView / NestedScrollView / HorizontalScrollView, " +
                        "got ${scrollableView.javaClass.name}"
            )
        }
    }

    private fun attachInternalScrollListener() {
        when (scrollableView) {
            is RecyclerView -> {
                scrollableView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                        syncHeaderTranslation()
                        if (externalScrollListeners.isEmpty()) return
                        val newX = rv.computeHorizontalScrollOffset()
                        val newY = rv.computeVerticalScrollOffset()
                        dispatchScroll(newX, newY, newX - dx, newY - dy)
                    }
                })
            }

            is NestedScrollView -> {
                scrollableView.setOnScrollChangeListener(
                    NestedScrollView.OnScrollChangeListener { _, x, y, oldX, oldY ->
                        syncHeaderTranslation()
                        if (externalScrollListeners.isNotEmpty()) {
                            dispatchScroll(x, y, oldX, oldY)
                        }
                    }
                )
            }

            is HorizontalScrollView -> {
                scrollableView.setOnScrollChangeListener { _, x, y, oldX, oldY ->
                    syncHeaderTranslation()
                    if (externalScrollListeners.isNotEmpty()) {
                        dispatchScroll(x, y, oldX, oldY)
                    }
                }
            }
        }
    }

    /**
     * 通知首次加载完成。在历史/首屏数据完成接入并触发滚动后调用，
     * 之后Header会按滚动偏移决定显隐。
     */
    fun markInitialLoadDone() {
        if (initialLoadDone) return
        initialLoadDone = true
        scrollableView.post { syncHeaderTranslation() }
    }

    /**
     * 重新计算Header的位移（外部如需手动同步，例如Header尺寸因数据变化时）
     */
    fun syncHeaderTranslation() {
        val maxOffset = (headerExtent() + resolveHeaderMargin()).toFloat()
        if (maxOffset <= 0f) {
            applyTranslation(0f)
            return
        }
        if (!initialLoadDone) {
            applyTranslation(-maxOffset)
            return
        }
        if (!canScrollAlongOrientation()) {
            applyTranslation(0f)
            return
        }
        val offset = currentScrollOffset().toFloat()
        applyTranslation(-offset.coerceAtMost(maxOffset))
    }

    /**
     * 统一的滚动监听接口，屏蔽不同滚动容器之间的差异
     */
    fun interface OnScrollChangeListener {
        /**
         * @param scrollX 当前水平滚动偏移
         * @param scrollY 当前纵向滚动偏移
         * @param oldScrollX 上一次水平滚动偏移
         * @param oldScrollY 上一次纵向滚动偏移
         */
        fun onScrollChange(scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int)
    }

    /**
     * 注册外部滚动监听。本类会在执行内部位移同步之后再分发给外部，避免重复包装。
     * 多个监听之间相互独立，按注册顺序回调。
     */
    fun addOnScrollChangeListener(listener: OnScrollChangeListener) {
        if (!externalScrollListeners.contains(listener)) {
            externalScrollListeners.add(listener)
        }
    }

    fun removeOnScrollChangeListener(listener: OnScrollChangeListener) {
        externalScrollListeners.remove(listener)
    }

    private fun dispatchScroll(
        scrollX: Int,
        scrollY: Int,
        oldScrollX: Int,
        oldScrollY: Int,
    ) {
        val snapshot = externalScrollListeners.toList()
        snapshot.forEach { it.onScrollChange(scrollX, scrollY, oldScrollX, oldScrollY) }
    }

    private fun headerExtent(): Int = if (orientation == Orientation.VERTICAL) {
        headerView.height
    } else {
        headerView.width
    }

    private fun resolveHeaderMargin(): Int {
        if (headerMarginOverride >= 0) return headerMarginOverride
        val lp = headerView.layoutParams
        if (lp !is ViewGroup.MarginLayoutParams) return 0
        return if (orientation == Orientation.VERTICAL) lp.topMargin else lp.marginStart
    }

    private fun applyTranslation(value: Float) {
        if (orientation == Orientation.VERTICAL) {
            headerView.translationY = value
        } else {
            headerView.translationX = value
        }
    }

    private fun applyContainerPadding(target: Int) {
        if (orientation == Orientation.VERTICAL) {
            if (scrollableView.paddingTop == target) return
            scrollableView.setPadding(
                scrollableView.paddingLeft,
                target,
                scrollableView.paddingRight,
                scrollableView.paddingBottom
            )
        } else {
            if (scrollableView.paddingStart == target) return
            scrollableView.setPaddingRelative(
                target,
                scrollableView.paddingTop,
                scrollableView.paddingEnd,
                scrollableView.paddingBottom
            )
        }
    }

    private fun canScrollAlongOrientation(): Boolean = if (orientation == Orientation.VERTICAL) {
        scrollableView.canScrollVertically(-1) || scrollableView.canScrollVertically(1)
    } else {
        scrollableView.canScrollHorizontally(-1) || scrollableView.canScrollHorizontally(1)
    }

    private fun currentScrollOffset(): Int {
        return when (val v = scrollableView) {
            is RecyclerView -> if (orientation == Orientation.VERTICAL) {
                v.computeVerticalScrollOffset()
            } else {
                v.computeHorizontalScrollOffset()
            }

            is NestedScrollView -> v.scrollY
            is HorizontalScrollView -> v.scrollX
            else -> 0
        }
    }

    /**
     * Header收起的方向
     */
    enum class Orientation {
        /** 纵向滚动，Header在顶部，向上平移收起 */
        VERTICAL,

        /** 横向滚动，Header在左侧，向左平移收起 */
        HORIZONTAL,
    }

    companion object {
        /**
         * @param headerView 滚动方向起点上的Header视图
         * @param scrollableView 滚动容器，仅支持[RecyclerView] / [NestedScrollView] / [HorizontalScrollView]
         * @param extraPadding 在Header尺寸之外额外补给滚动容器对应方向起点的padding
         *                     （保证内容不与Header重叠的留白）
         * @param headerMargin Header在滚动方向起点上的外边距，默认-1=从[headerView]的
         *                     [ViewGroup.MarginLayoutParams]读取（纵向topMargin、横向marginStart），
         *                     用于保证Header收起时能完全移出可视区
         * @param orientation Header收起方向，纵向/横向，默认[Orientation.VERTICAL]
         */
        fun attach(
            headerView: View,
            scrollableView: View,
            extraPadding: Int = 0,
            headerMargin: Int = -1,
            orientation: Orientation = Orientation.VERTICAL,
        ): HeaderScrollSync {
            return HeaderScrollSync(
                headerView,
                scrollableView,
                extraPadding,
                headerMargin,
                orientation
            )
        }
    }
}


