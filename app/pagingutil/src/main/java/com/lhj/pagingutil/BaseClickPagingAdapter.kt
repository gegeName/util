package com.lhj.pagingutil

import android.os.SystemClock
import android.view.View
import androidx.annotation.IdRes
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

/**
 * 抽出 [BasePagingAdapter] / [BaseMultiPagingAdapter] 共享的点击事件 + 按 key 节流逻辑。
 *
 * 4 类事件：item 点击 / item 长按 / 子 View 点击 / 子 View 长按。
 *
 * 节流：
 * - [setOnItemClickListener] / [setOnItemChildClickListener] 接受可选的 `throttleMs` 与 `keyOf`。
 * - `throttleMs > 0` 时启用按 key 时间戳节流：同 key 在窗口内的点击直接丢弃。
 * - `keyOf` 不传则按 position 节流（同一格子防连点）。
 * - 子 View 节流 key 自动混入 view.id，让同一 item 内不同子 View 互不干扰。
 *
 * 子类需在 onCreateViewHolder 创建好 ViewHolder 后调用 [bindClickListeners] 完成绑定。
 *
 * 设计取舍：
 * - 不直接复用 [KeyedRequestRunner]：那是给"带返回值的乐观更新请求"用的，需要 CoroutineScope；
 *   点击是同步事件，没有 suspend 上下文，引入 scope 会让 Adapter 与 lifecycle 绑死。
 * - 节流语义与 [RequestPolicy.Throttle] 完全一致（leading edge），方便业务认知统一。
 */
abstract class BaseClickPagingAdapter<T : Any, VH : RecyclerView.ViewHolder>(
    diff: DiffUtil.ItemCallback<T>
) : PagingDataAdapter<T, VH>(diff) {

    private var onItemClick: ((View, T, Int) -> Unit)? = null
    private var onItemLongClick: ((View, T, Int) -> Boolean)? = null
    private var onItemChildClick: ((View, T, Int) -> Unit)? = null
    private var onItemChildLongClick: ((View, T, Int) -> Boolean)? = null

    private var itemClickThrottleMs: Long = 0L
    private var itemClickKeyOf: ((T) -> Any)? = null
    private var itemChildClickThrottleMs: Long = 0L
    private var itemChildClickKeyOf: ((T) -> Any)? = null

    private val childClickIds = mutableSetOf<Int>()
    private val childLongClickIds = mutableSetOf<Int>()

    private val throttleLock = Any()
    private val itemClickLastTs = mutableMapOf<Any, Long>()
    private val childClickLastTs = mutableMapOf<Any, Long>()

    /**
     * @param throttleMs 节流窗口（ms），>0 启用；0 不限
     * @param keyOf 节流 key 提取器；不传时按 position 节流
     */
    fun setOnItemClickListener(
        throttleMs: Long = 0L,
        keyOf: ((T) -> Any)? = null,
        listener: (view: View, item: T, position: Int) -> Unit
    ) {
        itemClickThrottleMs = throttleMs
        itemClickKeyOf = keyOf
        onItemClick = listener
    }

    fun setOnItemLongClickListener(listener: (view: View, item: T, position: Int) -> Boolean) {
        onItemLongClick = listener
    }

    fun addChildClickViewIds(@IdRes vararg ids: Int) {
        childClickIds.addAll(ids.toList())
    }

    /**
     * 子 View 点击监听。节流 key 自动混入 view.id，不同子 View 各自独立计时。
     */
    fun setOnItemChildClickListener(
        throttleMs: Long = 0L,
        keyOf: ((T) -> Any)? = null,
        listener: (view: View, item: T, position: Int) -> Unit
    ) {
        itemChildClickThrottleMs = throttleMs
        itemChildClickKeyOf = keyOf
        onItemChildClick = listener
    }

    fun addChildLongClickViewIds(@IdRes vararg ids: Int) {
        childLongClickIds.addAll(ids.toList())
    }

    fun setOnItemChildLongClickListener(listener: (view: View, item: T, position: Int) -> Boolean) {
        onItemChildLongClick = listener
    }

    /** 子类在 onCreateViewHolder 创建完 ViewHolder 后调用 */
    protected fun bindClickListeners(holder: VH) {
        holder.itemView.setOnClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val item = getItem(pos) ?: return@setOnClickListener
            val keyOf = itemClickKeyOf
            val originalKey = keyOf?.invoke(item)
            val throttleKey = originalKey ?: pos
            if (!throttle(itemClickLastTs, itemClickThrottleMs, throttleKey)) return@setOnClickListener
            val curPos = holder.bindingAdapterPosition
            if (curPos == RecyclerView.NO_POSITION) return@setOnClickListener
            val curItem = getItem(curPos) ?: return@setOnClickListener
            if (keyOf != null && keyOf.invoke(curItem) != originalKey) return@setOnClickListener
            onItemClick?.invoke(v, curItem, curPos)
        }
        holder.itemView.setOnLongClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            val item = getItem(pos) ?: return@setOnLongClickListener false
            onItemLongClick?.invoke(v, item, pos) ?: false
        }
        childClickIds.forEach { id ->
            holder.itemView.findViewById<View>(id)?.setOnClickListener { v ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = getItem(pos) ?: return@setOnClickListener
                val keyOf = itemChildClickKeyOf
                val originalItemKey = keyOf?.invoke(item)
                val itemKey = originalItemKey ?: pos
                val throttleKey = "$itemKey@${v.id}"
                if (!throttle(childClickLastTs, itemChildClickThrottleMs, throttleKey)) return@setOnClickListener
                val curPos = holder.bindingAdapterPosition
                if (curPos == RecyclerView.NO_POSITION) return@setOnClickListener
                val curItem = getItem(curPos) ?: return@setOnClickListener
                if (keyOf != null && keyOf.invoke(curItem) != originalItemKey) return@setOnClickListener
                onItemChildClick?.invoke(v, curItem, curPos)
            }
        }
        childLongClickIds.forEach { id ->
            holder.itemView.findViewById<View>(id)?.setOnLongClickListener { v ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val item = getItem(pos) ?: return@setOnLongClickListener false
                onItemChildLongClick?.invoke(v, item, pos) ?: false
            }
        }
    }

    private fun throttle(lastTsMap: MutableMap<Any, Long>, intervalMs: Long, key: Any): Boolean {
        if (intervalMs <= 0) return true
        val now = SystemClock.elapsedRealtime()
        synchronized(throttleLock) {
            val last = lastTsMap[key] ?: 0L
            if (now - last < intervalMs) return false
            lastTsMap[key] = now
        }
        return true
    }
}
