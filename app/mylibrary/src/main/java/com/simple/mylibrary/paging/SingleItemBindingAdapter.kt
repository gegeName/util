package com.simple.mylibrary.paging

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
        return holder
    }

    override fun onBindViewHolder(holder: VH<VB>, position: Int) {
        val d = data ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        onBind(holder.binding, d)
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
