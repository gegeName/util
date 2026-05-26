package com.chat.rv_page

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * 单布局 **非分页** RecyclerView Adapter 基类（与 [BasePagingAdapter] 对偶）。
 *
 * **无反射版**：子类通过构造器直接传 ViewBinding 的 `::inflate` 函数引用。
 *
 * 用于"已知全量数据"的场景：设置项、菜单、静态分组列表等。内部用 [AsyncListDiffer]
 * 做后台 diff，[submit] 一次性提交全量数据；点击事件 + 节流语义与
 * [BaseClickPagingAdapter] 完全一致。
 *
 * 子类只需实现 [onBind]，无需重写 onCreateViewHolder。
 *
 * 示例：
 * ```
 * class MenuAdapter : BaseListAdapter<MenuItem, ItemMenuBinding>(
 *     diff = MENU_DIFF,
 *     inflater = ItemMenuBinding::inflate
 * ) {
 *     override fun onBind(binding: ItemMenuBinding, item: MenuItem, position: Int) {
 *         binding.tv.text = item.title
 *     }
 * }
 *
 * menuAdapter.setOnItemClickListener(throttleMs = 600, keyOf = { it.id }) { v, item, pos ->
 *     nav(item.route)
 * }
 * menuAdapter.submit(vm.menuItems)
 * ```
 *
 * @param T item 类型
 * @param VB ViewBinding 或 ViewDataBinding 生成类
 * @param diff DiffUtil.ItemCallback，与 PagingDataAdapter 同款
 * @param inflater ViewBinding 的 inflate 函数引用，通常写成 `XxxBinding::inflate`
 */
abstract class BaseListAdapter<T : Any, VB : ViewBinding>(
    diff: DiffUtil.ItemCallback<T>,
    private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB
) : RecyclerView.Adapter<BaseListAdapter.BindingHolder<VB>>() {

    class BindingHolder<VB : ViewBinding>(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    private val differ = AsyncListDiffer(this, diff)

    /** 当前完整数据快照（diff 已落地） */
    val currentList: List<T> get() = differ.currentList

    abstract fun onBind(binding: VB, item: T, position: Int)

    /**
     * 局部刷新回调，业务用 `notifyItemChanged(pos, payload)` 触发。
     * 默认回退到全量 [onBind]，不重写也安全。
     */
    protected open fun onBind(binding: VB, item: T, position: Int, payloads: MutableList<Any>) {
        onBind(binding, item, position)
    }

    /**
     * onCreateViewHolder 创建完 ViewHolder、绑完点击事件之后回调,
     * 整个 holder 生命周期只触发一次(后续复用 / 重 bind 不会再进来).
     *
     * 用来做"与 item 数据无关、只跟 View 有关"的一次性配置:
     * - 嵌套 RecyclerView 设 layoutManager / addItemDecoration / setRecycledViewPool
     * - 给某个 View 挂 setOnTouchListener 等长期监听
     *
     * 默认空实现,业务按需 override.
     */
    protected open fun onViewHolderCreated(holder: BindingHolder<VB>, binding: VB) = Unit

    /**
     * 提交全量数据；触发后台 diff，diff 完成后自动 dispatch 增量更新。
     * @param list 新数据，传 null 视为清空
     * @param commitCallback diff 落地后回调，可在此 scrollToTop 等
     */
    fun submit(list: List<T>?, commitCallback: (() -> Unit)? = null) {
        if (commitCallback != null) differ.submitList(list) { commitCallback() }
        else differ.submitList(list)
    }

    override fun getItemCount(): Int = differ.currentList.size

    /** 业务直接按位置取 item；越界返回 null */
    fun getItemOrNull(position: Int): T? = differ.currentList.getOrNull(position)

    // ───── 监听器 ─────
    private var onItemClick: ((View, T, Int) -> Unit)? = null
    private var onItemLongClick: ((View, T, Int) -> Boolean)? = null
    private var onItemChildClick: ((View, T, Int) -> Unit)? = null
    private var onItemChildLongClick: ((View, T, Int) -> Boolean)? = null

    // ───── 节流配置 ─────
    private var itemClickThrottleMs: Long = 0L
    private var itemClickKeyOf: ((T) -> Any)? = null
    private var itemChildClickThrottleMs: Long = 0L
    private var itemChildClickKeyOf: ((T) -> Any)? = null

    // ───── 子 View id ─────
    private val childClickIds = mutableSetOf<Int>()
    private val childLongClickIds = mutableSetOf<Int>()

    // ───── 节流时间戳表 ─────
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

    /** 子 View 点击监听；节流 key 自动混入 view.id */
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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<VB> {
        val binding = inflater(LayoutInflater.from(parent.context), parent, false)
        val holder = BindingHolder(binding)
        bindClickListeners(holder)
        onViewHolderCreated(holder, binding)
        return holder
    }

    override fun onBindViewHolder(holder: BindingHolder<VB>, position: Int) {
        val item = getItemOrNull(position) ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        onBind(holder.binding, item, position)
    }

    override fun onBindViewHolder(
        holder: BindingHolder<VB>,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = getItemOrNull(position) ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        onBind(holder.binding, item, position, payloads)
    }

    private fun bindClickListeners(holder: BindingHolder<VB>) {
        holder.itemView.setOnClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val item = getItemOrNull(pos) ?: return@setOnClickListener
            val keyOf = itemClickKeyOf
            val originalKey = keyOf?.invoke(item)
            val throttleKey = originalKey ?: pos
            if (!throttle(itemClickLastTs, itemClickThrottleMs, throttleKey)) return@setOnClickListener
            val curPos = holder.bindingAdapterPosition
            if (curPos == RecyclerView.NO_POSITION) return@setOnClickListener
            val curItem = getItemOrNull(curPos) ?: return@setOnClickListener
            if (keyOf != null && keyOf.invoke(curItem) != originalKey) return@setOnClickListener
            onItemClick?.invoke(v, curItem, curPos)
        }
        holder.itemView.setOnLongClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            val item = getItemOrNull(pos) ?: return@setOnLongClickListener false
            onItemLongClick?.invoke(v, item, pos) ?: false
        }
        childClickIds.forEach { id ->
            holder.itemView.findViewById<View>(id)?.setOnClickListener { v ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = getItemOrNull(pos) ?: return@setOnClickListener
                val keyOf = itemChildClickKeyOf
                val originalItemKey = keyOf?.invoke(item)
                val itemKey = originalItemKey ?: pos
                val throttleKey = "$itemKey@${v.id}"
                if (!throttle(childClickLastTs, itemChildClickThrottleMs, throttleKey)) return@setOnClickListener
                val curPos = holder.bindingAdapterPosition
                if (curPos == RecyclerView.NO_POSITION) return@setOnClickListener
                val curItem = getItemOrNull(curPos) ?: return@setOnClickListener
                if (keyOf != null && keyOf.invoke(curItem) != originalItemKey) return@setOnClickListener
                onItemChildClick?.invoke(v, curItem, curPos)
            }
        }
        childLongClickIds.forEach { id ->
            holder.itemView.findViewById<View>(id)?.setOnLongClickListener { v ->
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                val item = getItemOrNull(pos) ?: return@setOnLongClickListener false
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
