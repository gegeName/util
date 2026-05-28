package com.chat.rv_page

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.withTranslation
import androidx.recyclerview.widget.RecyclerView

/**
 * 轻量 sticky header 实现:每次 [onDrawOver] 找当前最近的 sticky position,measure/layout/draw 到 RV 顶部。
 *
 * 触达下一个 sticky position 时按"推走旧的"做 y 轴偏移。不拦截点击,header 视图本身只参与绘制,
 * 业务点击事件继续走 RV 下层的真 ViewHolder。
 */
internal class StickyHeaderItemDecoration(
    private val callbacks: StickyHeaderCallbacks
) : RecyclerView.ItemDecoration() {

    private var currentHeaderView: View? = null
    private var currentHeaderPos: Int = RecyclerView.NO_POSITION
    private var currentHeaderViewType: Int = INVALID_VIEW_TYPE

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val rawAdapter = parent.adapter ?: return run { teardownIfAny() }

        @Suppress("UNCHECKED_CAST")
        val adapter = rawAdapter as RecyclerView.Adapter<RecyclerView.ViewHolder>

        val topChild = parent.getChildAt(0) ?: return run { teardownIfAny() }
        val topPos = parent.getChildAdapterPosition(topChild)
        if (topPos == RecyclerView.NO_POSITION) return run { teardownIfAny() }

        val headerPos = findHeaderPositionForItem(topPos)
        if (headerPos == RecyclerView.NO_POSITION) return run { teardownIfAny() }

        val header = ensureHeader(parent, adapter, headerPos) ?: return

        val contactPoint = header.bottom
        val childInContact = findChildInContact(parent, contactPoint)
        val offsetY = if (childInContact != null) {
            val childPos = parent.getChildAdapterPosition(childInContact)
            if (childPos != RecyclerView.NO_POSITION && callbacks.isStickyHeader(childPos)) {
                (childInContact.top - header.height).coerceAtMost(0)
            } else 0
        } else 0

        c.withTranslation(parent.paddingLeft.toFloat(), offsetY.toFloat()) {
            header.draw(this)
        }
    }

    private fun ensureHeader(
        parent: RecyclerView,
        adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>,
        pos: Int
    ): View? {
        val viewType = adapter.getItemViewType(pos)
        if (pos == currentHeaderPos && currentHeaderViewType == viewType && currentHeaderView != null) {
            return currentHeaderView
        }
        teardownIfAny()

        val holder = adapter.createViewHolder(parent, viewType)
        adapter.bindViewHolder(holder, pos)
        val view = holder.itemView
        val lp = view.layoutParams
        val widthMode = View.MeasureSpec.EXACTLY
        val heightMode = View.MeasureSpec.AT_MOST
        val widthSpec = View.MeasureSpec.makeMeasureSpec(
            parent.width - parent.paddingLeft - parent.paddingRight,
            widthMode
        )
        val heightSpec = View.MeasureSpec.makeMeasureSpec(
            parent.height - parent.paddingTop - parent.paddingBottom,
            heightMode
        )
        val childWidthSpec = ViewGroup.getChildMeasureSpec(
            widthSpec, 0,
            lp?.width ?: ViewGroup.LayoutParams.MATCH_PARENT
        )
        val childHeightSpec = ViewGroup.getChildMeasureSpec(
            heightSpec, 0,
            lp?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
        )
        view.measure(childWidthSpec, childHeightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        callbacks.setupStickyHeaderView(view)
        currentHeaderView = view
        currentHeaderPos = pos
        currentHeaderViewType = viewType
        return view
    }

    private fun teardownIfAny() {
        currentHeaderView?.let { callbacks.teardownStickyHeaderView(it) }
        currentHeaderView = null
        currentHeaderPos = RecyclerView.NO_POSITION
        currentHeaderViewType = INVALID_VIEW_TYPE
    }

    private fun findHeaderPositionForItem(itemPos: Int): Int {
        var p = itemPos
        while (p >= 0) {
            if (callbacks.isStickyHeader(p)) return p
            p--
        }
        return RecyclerView.NO_POSITION
    }

    private fun findChildInContact(parent: RecyclerView, contactPoint: Int): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.bottom > contactPoint && child.top <= contactPoint) return child
        }
        return null
    }

    companion object {
        private const val INVALID_VIEW_TYPE = -1
    }
}
