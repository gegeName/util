package com.chat.uifoundation.widget

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/**
 * 让悬浮在内容前缘的Header卡片跟随滚动
 * - 内容不足以滚动时：Header常驻显示
 * - 内容可滚动时：按滚动偏移把Header向滚动反方向平移收起
 * - 数据首次加载完成前：Header保持隐藏，避免"先显示后被滚动收起"的闪现
 *   （由调用方在初始数据/首屏滚动到位后调用[markInitialLoadDone]触发）
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    scrollableView.setOnScrollChangeListener { _, x, y, oldX, oldY ->
                        syncHeaderTranslation()
                        if (externalScrollListeners.isNotEmpty()) {
                            dispatchScroll(x, y, oldX, oldY)
                        }
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