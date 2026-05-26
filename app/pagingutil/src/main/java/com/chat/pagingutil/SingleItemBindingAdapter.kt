package com.chat.pagingutil

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * 单 item 头部 / 卡片 Adapter（适合 Banner、公告、分组标题等只占一格的位置）。
 *
 * 通过构造参数显式传入 ViewBinding 的 inflate 方法引用，零反射、R8 / minify 友好。
 *
 * 4 类点击事件：item 点击 / item 长按 / 子 View 点击 / 子 View 长按。节流语义与
 * [BaseClickPagingAdapter] 一致：传 `throttleMs > 0` 启用 leading-edge 节流；
 * 子 View 节流自动按 view.id 隔离。
 *
 * 示例：
 * ```
 * class BannerAdapter : SingleItemBindingAdapter<Banner, ItemBannerBinding>(ItemBannerBinding::inflate) {
 *     override fun onBind(binding: ItemBannerBinding, data: Banner) {
 *         binding.tvTitle.text = data.title
 *     }
 * }
 * ```
 *
 * @param T 数据类型
 * @param VB ViewBinding 或 ViewDataBinding 生成类
 * @param inflate ViewBinding inflate 函数引用（如 `ItemXxxBinding::inflate`）
 */
abstract class SingleItemBindingAdapter<T, VB : ViewBinding>(
    private val inflate: (LayoutInflater, ViewGroup, Boolean) -> VB
) : RecyclerView.Adapter<SingleItemBindingAdapter.VH<VB>>() {

    class VH<VB : ViewBinding>(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    private var data: T? = null
    private var visible: Boolean = true

    private var onItemClick: ((View, T) -> Unit)? = null
    private var onItemLongClick: ((View, T) -> Boolean)? = null
    private var onItemChildClick: ((View, T) -> Unit)? = null
    private var onItemChildLongClick: ((View, T) -> Boolean)? = null

    private var itemClickThrottleMs: Long = 0L
    private var itemChildClickThrottleMs: Long = 0L
    private val throttleLock = Any()
    private var itemClickLastTs: Long = 0L
    private val childClickLastTs = mutableMapOf<Int, Long>()

    private val childClickIds = mutableSetOf<Int>()
    private val childLongClickIds = mutableSetOf<Int>()

    /**
     * 业务实现：把数据绑到 binding 上。
     *
     * @param binding 强类型 ViewBinding
     * @param data    当前数据（非空保证由 [onBindViewHolder] 拦截）
     */
    abstract fun onBind(binding: VB, data: T)

    /**
     * 局部刷新回调，业务用 [notifyPayload] 或 `notifyItemChanged(0, payload)` 触发。
     * 默认回退到全量 [onBind]，不重写也安全。
     *
     * @param binding  强类型 ViewBinding
     * @param data     当前数据
     * @param payloads 非空 payloads（空 payloads 走全量分支）
     */
    protected open fun onBind(binding: VB, data: T, payloads: MutableList<Any>) {
        onBind(binding, data)
    }

    /**
     * 局部刷新单 item；可见且 data 非空才会派发，否则忽略。
     *
     * @param payload  局部刷新负载
     */
    fun notifyPayload(payload: Any) {
        if (visible && data != null) notifyItemChanged(0, payload)
    }

    /**
     * ViewHolder + 点击事件绑完之后只调一次的钩子，做与 data 无关的一次性初始化。
     *
     * @param holder  新建的 [VH]
     * @param binding 强类型 ViewBinding
     */
    protected open fun onViewHolderCreated(holder: VH<VB>, binding: VB) = Unit

    /**
     * 提交新数据；data 为 null 时配合 [setVisible] 控制是否展示。
     *
     * @param data 新数据，null 表示无数据
     */
    fun submit(data: T?) {
        val hadItem = visible && this.data != null
        this.data = data
        val hasItem = visible && data != null
        when {
            !hadItem && hasItem -> notifyItemInserted(0)
            hadItem && !hasItem -> notifyItemRemoved(0)
            hadItem && hasItem -> notifyItemChanged(0)
        }
    }

    /**
     * 切换是否展示该单 item。
     *
     * @param visible true 显示，false 隐藏（itemCount=0）
     */
    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        if (visible) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    /**
     * 设置 item 点击监听。
     *
     * @param throttleMs >0 启用按时间戳节流；0 不限
     * @param listener   点击回调，参数为被点击的 View 和当前数据
     */
    fun setOnItemClickListener(
        throttleMs: Long = 0L,
        listener: (view: View, data: T) -> Unit
    ) {
        itemClickThrottleMs = throttleMs
        onItemClick = listener
    }

    fun setOnItemLongClickListener(listener: (view: View, data: T) -> Boolean) {
        onItemLongClick = listener
    }

    fun addChildClickViewIds(@IdRes vararg ids: Int) {
        childClickIds.addAll(ids.toList())
    }

    /**
     * 设置子 View 点击监听。
     *
     * @param throttleMs >0 启用按 view.id 各自独立计时的节流；0 不限
     * @param listener   点击回调
     */
    fun setOnItemChildClickListener(
        throttleMs: Long = 0L,
        listener: (view: View, data: T) -> Unit
    ) {
        itemChildClickThrottleMs = throttleMs
        onItemChildClick = listener
    }

    fun addChildLongClickViewIds(@IdRes vararg ids: Int) {
        childLongClickIds.addAll(ids.toList())
    }

    fun setOnItemChildLongClickListener(listener: (view: View, data: T) -> Boolean) {
        onItemChildLongClick = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH<VB> {
        val binding = inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = VH(binding)
        bindClickListeners(holder)
        onViewHolderCreated(holder, binding)
        return holder
    }

    override fun onBindViewHolder(holder: VH<VB>, position: Int) {
        val d = data ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        onBind(holder.binding, d)
    }

    override fun onBindViewHolder(holder: VH<VB>, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val d = data ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        onBind(holder.binding, d, payloads)
    }

    override fun getItemCount(): Int = if (visible && data != null) 1 else 0

    private fun bindClickListeners(holder: VH<VB>) {
        holder.itemView.setOnClickListener { v ->
            val d = data ?: return@setOnClickListener
            if (!throttleItem()) return@setOnClickListener
            onItemClick?.invoke(v, d)
        }
        holder.itemView.setOnLongClickListener { v ->
            val d = data ?: return@setOnLongClickListener false
            onItemLongClick?.invoke(v, d) ?: false
        }
        childClickIds.forEach { id ->
            holder.itemView.findViewById<View>(id)?.setOnClickListener { v ->
                val d = data ?: return@setOnClickListener
                if (!throttleChild(v.id)) return@setOnClickListener
                onItemChildClick?.invoke(v, d)
            }
        }
        childLongClickIds.forEach { id ->
            holder.itemView.findViewById<View>(id)?.setOnLongClickListener { v ->
                val d = data ?: return@setOnLongClickListener false
                onItemChildLongClick?.invoke(v, d) ?: false
            }
        }
    }

    private fun throttleItem(): Boolean {
        if (itemClickThrottleMs <= 0) return true
        val now = SystemClock.elapsedRealtime()
        synchronized(throttleLock) {
            if (now - itemClickLastTs < itemClickThrottleMs) return false
            itemClickLastTs = now
        }
        return true
    }

    private fun throttleChild(id: Int): Boolean {
        if (itemChildClickThrottleMs <= 0) return true
        val now = SystemClock.elapsedRealtime()
        synchronized(throttleLock) {
            val last = childClickLastTs[id] ?: 0L
            if (now - last < itemChildClickThrottleMs) return false
            childClickLastTs[id] = now
        }
        return true
    }
}
