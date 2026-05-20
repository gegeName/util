package com.lhj.pagingutil

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 统一间距 ItemDecoration
 *
 * 支持 LinearLayoutManager (横/竖) 与 GridLayoutManager (横/竖, 含 SpanSizeLookup)
 * 可分别设置 item 之间的间距与 RecyclerView 四条边缘的间距
 *
 * 用法:
 * ```
 * val decoration = SpacingItemDecoration.builder()
 *     .itemSpacing(SpacingItemDecoration.dp(context, 12f))
 *     .edgeHorizontal(SpacingItemDecoration.dp(context, 16f))
 *     .edgeTop(SpacingItemDecoration.dp(context, 8f))
 *     .edgeBottom(SpacingItemDecoration.dp(context, 24f))
 *     .attachRecyclerView(recyclerView)
 * ```
 */
class SpacingItemDecoration private constructor(
    private val itemSpacing: Int,
    private val edgeLeft: Int,
    private val edgeTop: Int,
    private val edgeRight: Int,
    private val edgeBottom: Int
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val itemCount = state.itemCount

        when (val lm = parent.layoutManager) {
            is GridLayoutManager -> handleGrid(outRect, position, itemCount, lm)
            is LinearLayoutManager -> handleLinear(outRect, position, itemCount, lm)
            else -> outRect.set(edgeLeft, edgeTop, edgeRight, edgeBottom)
        }
    }

    private fun handleLinear(
        outRect: Rect,
        position: Int,
        itemCount: Int,
        lm: LinearLayoutManager
    ) {
        val isFirst = position == 0
        val isLast = position == itemCount - 1

        if (lm.orientation == RecyclerView.VERTICAL) {
            outRect.left = edgeLeft
            outRect.right = edgeRight
            outRect.top = if (isFirst) edgeTop else 0
            outRect.bottom = if (isLast) edgeBottom else itemSpacing
        } else {
            outRect.top = edgeTop
            outRect.bottom = edgeBottom
            outRect.left = if (isFirst) edgeLeft else 0
            outRect.right = if (isLast) edgeRight else itemSpacing
        }
    }

    private fun handleGrid(
        outRect: Rect,
        position: Int,
        itemCount: Int,
        lm: GridLayoutManager
    ) {
        val spanCount = lm.spanCount
        val lookup = lm.spanSizeLookup
        val column = lookup.getSpanIndex(position, spanCount)
        val groupIndex = lookup.getSpanGroupIndex(position, spanCount)
        val lastGroupIndex = lookup.getSpanGroupIndex(itemCount - 1, spanCount)
        val isFirstGroup = groupIndex == 0
        val isLastGroup = groupIndex == lastGroupIndex

        if (lm.orientation == RecyclerView.VERTICAL) {
            val (l, r) = calcCross(column, spanCount, edgeLeft, edgeRight, itemSpacing)
            outRect.left = l
            outRect.right = r
            outRect.top = if (isFirstGroup) edgeTop else 0
            outRect.bottom = if (isLastGroup) edgeBottom else itemSpacing
        } else {
            val (t, b) = calcCross(column, spanCount, edgeTop, edgeBottom, itemSpacing)
            outRect.top = t
            outRect.bottom = b
            outRect.left = if (isFirstGroup) edgeLeft else 0
            outRect.right = if (isLastGroup) edgeRight else itemSpacing
        }
    }

    private fun calcCross(
        column: Int,
        spanCount: Int,
        edgeStart: Int,
        edgeEnd: Int,
        spacing: Int
    ): Pair<Int, Int> {
        val total = edgeStart + edgeEnd + (spanCount - 1) * spacing
        val each = total / spanCount
        val start = edgeStart + column * (spacing - each)
        val end = each - start
        return start to end
    }

    class Builder {
        private var itemSpacing = 0
        private var edgeLeft = 0
        private var edgeTop = 0
        private var edgeRight = 0
        private var edgeBottom = 0

        /** item 之间的间距 (px) */
        fun itemSpacing(px: Int) = apply { itemSpacing = px }

        /** 一次性设置四个边缘 (px) */
        fun edge(px: Int) = apply {
            edgeLeft = px
            edgeTop = px
            edgeRight = px
            edgeBottom = px
        }

        fun edgeLeft(px: Int) = apply { edgeLeft = px }
        fun edgeTop(px: Int) = apply { edgeTop = px }
        fun edgeRight(px: Int) = apply { edgeRight = px }
        fun edgeBottom(px: Int) = apply { edgeBottom = px }

        /** 水平边缘 (左+右) */
        fun edgeHorizontal(px: Int) = apply {
            edgeLeft = px
            edgeRight = px
        }

        /** 垂直边缘 (上+下) */
        fun edgeVertical(px: Int) = apply {
            edgeTop = px
            edgeBottom = px
        }

        /**
         * 应用到 RecyclerView
         */
        fun attachRecyclerView(recyclerView: RecyclerView): SpacingItemDecoration {
            for (i in recyclerView.itemDecorationCount - 1 downTo 0) {
                if (recyclerView.getItemDecorationAt(i) is SpacingItemDecoration) {
                    recyclerView.removeItemDecorationAt(i)
                }
            }
            val decoration = SpacingItemDecoration(
                itemSpacing, edgeLeft, edgeTop, edgeRight, edgeBottom
            )
            recyclerView.addItemDecoration(decoration)
            return decoration
        }
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
