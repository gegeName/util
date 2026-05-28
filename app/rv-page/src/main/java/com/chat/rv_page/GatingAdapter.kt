package com.chat.rv_page

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 转发型 wrapper:对外 [getItemCount] 受 [setVisible] 闸门控制,关闭时报 0 项;
 * onCreate / onBind / 数据变化通知全部透传给 [inner]。
 *
 * 让 `hideSection` 与 PagingHelper 的 aux 摘/插逻辑面对同一 wrapper 实例独立切各自状态,
 * 互不覆盖。
 */
internal class GatingAdapter(
    @Suppress("UNCHECKED_CAST")
    val inner: RecyclerView.Adapter<RecyclerView.ViewHolder>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var visible: Boolean = true

    init {
        if (inner.hasStableIds()) setHasStableIds(true)
        inner.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                if (visible) notifyDataSetChanged()
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                if (visible) notifyItemRangeChanged(positionStart, itemCount)
            }
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) {
                if (visible) notifyItemRangeChanged(positionStart, itemCount, payload)
            }
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (visible) notifyItemRangeInserted(positionStart, itemCount)
            }
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                if (visible) notifyItemRangeRemoved(positionStart, itemCount)
            }
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                if (!visible) return
                if (itemCount == 1) notifyItemMoved(fromPosition, toPosition)
                else notifyDataSetChanged()
            }
        })
    }

    fun setVisible(v: Boolean) {
        if (visible == v) return
        if (v) {
            visible = true
            val n = inner.itemCount
            if (n > 0) notifyItemRangeInserted(0, n)
        } else {
            val n = inner.itemCount
            visible = false
            if (n > 0) notifyItemRangeRemoved(0, n)
        }
    }

    fun isVisible(): Boolean = visible

    override fun getItemCount(): Int = if (visible) inner.itemCount else 0

    override fun getItemViewType(position: Int): Int = inner.getItemViewType(position)
    override fun getItemId(position: Int): Long = inner.getItemId(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        inner.onCreateViewHolder(parent, viewType)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) =
        inner.onBindViewHolder(holder, position)

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) = inner.onBindViewHolder(holder, position, payloads)

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) = inner.onViewRecycled(holder)

    override fun onFailedToRecycleView(holder: RecyclerView.ViewHolder): Boolean =
        inner.onFailedToRecycleView(holder)

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) =
        inner.onViewAttachedToWindow(holder)

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) =
        inner.onViewDetachedFromWindow(holder)
}
