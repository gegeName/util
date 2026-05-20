package com.lhj.pagingutil

import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.IdRes
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

/**
 * 单 item 头部 / 卡片 Adapter（适合 Banner、公告、分组标题等只占一格的位置）。
 *
 * 4 类点击事件：item 点击 / item 长按 / 子 View 点击 / 子 View 长按。节流语义与
 * [BaseClickPagingAdapter] 一致：传 `throttleMs > 0` 启用 leading-edge 节流；
 * 子 View 节流自动按 view.id 隔离。
 *
 * @param T 数据类型
 * @param VB ViewBinding 或 ViewDataBinding 生成类
 */
abstract class SingleItemBindingAdapter<T, VB : ViewBinding> :
    RecyclerView.Adapter<SingleItemBindingAdapter.VH<VB>>() {

    class VH<VB : ViewBinding>(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    private var data: T? = null
    private var visible: Boolean = true

    // ───── 监听器 ─────
    private var onItemClick: ((View, T) -> Unit)? = null
    private var onItemLongClick: ((View, T) -> Boolean)? = null
    private var onItemChildClick: ((View, T) -> Unit)? = null
    private var onItemChildLongClick: ((View, T) -> Boolean)? = null

    // ───── 节流 ─────
    private var itemClickThrottleMs: Long = 0L
    private var itemChildClickThrottleMs: Long = 0L
    private val throttleLock = Any()
    private var itemClickLastTs: Long = 0L
    private val childClickLastTs = mutableMapOf<Int, Long>()

    // ───── 子 View id ─────
    private val childClickIds = mutableSetOf<Int>()
    private val childLongClickIds = mutableSetOf<Int>()

    abstract fun onBind(binding: VB, data: T)

    /**
     * 局部刷新回调，业务用 [notifyPayload] 或 `notifyItemChanged(0, payload)` 触发。
     * 默认回退到全量 [onBind]，不重写也安全。
     * @param payloads 非空；空 payloads 由 [onBindViewHolder] 拦截直接走全量分支
     */
    protected open fun onBind(binding: VB, data: T, payloads: MutableList<Any>) {
        onBind(binding, data)
    }

    /** 局部刷新单 item；可见且 data 非空才会派发，否则忽略。 */
    fun notifyPayload(position: Int,payload: Any) {
        if (visible && data != null) notifyItemChanged(position, payload)
    }

    /**
     * onCreateViewHolder 创建完 ViewHolder、绑完点击事件之后回调,
     * 整个 holder 生命周期只触发一次(后续 submit / setVisible 都不会再进来).
     *
     * 用来做"与 data 无关、只跟 View 有关"的一次性配置:
     * - 嵌套 RecyclerView 设 layoutManager / addItemDecoration / setRecycledViewPool
     * - 给某个 View 挂 setOnTouchListener 等长期监听
     * - 给 holder.itemView 上挂 tag、自定义属性
     *
     * 与 [onBind] 区分:
     * - onBind 每次 submit(data) 触发的 notifyItemChanged 都会重新跑,
     *   适合写"数据 → 视图"的映射
     * - onViewHolderCreated 只调一次, 适合写"View 自身的结构 / 行为初始化"
     *
     * 默认空实现, 业务按需 override.
     */
    protected open fun onViewHolderCreated(holder: VH<VB>, binding: VB) = Unit

    fun submit(data: T?) {
        this.data = data
        notifyItemChanged(0)
    }

    fun setVisible(visible: Boolean) {
        if (this.visible == visible) return
        this.visible = visible
        if (visible) notifyItemInserted(0) else notifyItemRemoved(0)
    }

    /** @param throttleMs >0 启用按时间戳节流；0 不限 */
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

    /** 子 View 点击监听；节流按 view.id 各自独立计时 */
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

    @Suppress("UNCHECKED_CAST")
    private val inflateMethod by lazy {
        val vbClass = (javaClass.genericSuperclass as ParameterizedType)
            .actualTypeArguments[1] as Class<VB>
        vbClass.getMethod(
            "inflate",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Boolean::class.javaPrimitiveType
        )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH<VB> {
        @Suppress("UNCHECKED_CAST")
        val binding = inflateMethod.invoke(
            null, LayoutInflater.from(parent.context), parent, false
        ) as VB
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

    /**
     * 局部刷新分发：payloads 为空时回退到全量 [onBindViewHolder]，
     * 否则走带 payloads 的 [onBind] 重载。
     */
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
