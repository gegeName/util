package com.chat.pagingutil

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * 单布局 PagingDataAdapter 基类（继承 [BaseClickPagingAdapter]，自带点击事件 + 节流）。
 *
 * 通过构造参数显式传入 ViewBinding 的 inflate 方法引用，零反射、R8 / minify 友好。
 *
 * 示例：
 * ```
 * class UserAdapter : BasePagingAdapter<UserItem, ItemUserBinding>(
 *     DIFF,
 *     ItemUserBinding::inflate
 * ) {
 *     override fun onBind(binding: ItemUserBinding, item: UserItem, position: Int) {
 *         binding.tvNickname.text = item.nickname
 *     }
 * }
 * ```
 *
 * 点击事件 + 节流 API 完全继承自 [BaseClickPagingAdapter]。
 */
abstract class BasePagingAdapter<T : Any, VB : ViewBinding>(
    diff: DiffUtil.ItemCallback<T>,
    private val inflate: (LayoutInflater, ViewGroup, Boolean) -> VB
) : BaseClickPagingAdapter<T, BasePagingAdapter.BindingHolder<VB>>(diff) {

    class BindingHolder<VB : ViewBinding>(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    /**
     * 业务实现：把数据绑到 binding 上，每次 bind / 复用都会调。
     *
     * @param binding  强类型 ViewBinding
     * @param item     当前位置数据
     * @param position 当前位置
     */
    abstract fun onBind(binding: VB, item: T, position: Int)

    /**
     * 局部刷新回调，默认回退到全量 [onBind]。
     *
     * @param binding  强类型 ViewBinding
     * @param item     当前位置数据
     * @param position 当前位置
     * @param payloads 非空 payloads（空 payloads 会走全量 [onBind] 路径）
     */
    protected open fun onBind(binding: VB, item: T, position: Int, payloads: MutableList<Any>) {
        onBind(binding, item, position)
    }

    /**
     * 创建完 ViewHolder + 绑完点击事件之后只调一次的钩子，做与 item 数据无关的一次性初始化。
     *
     * @param holder  新建的 [BindingHolder]
     * @param binding 强类型 ViewBinding
     */
    protected open fun onViewHolderCreated(holder: BindingHolder<VB>, binding: VB) = Unit

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<VB> {
        val binding = inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = BindingHolder(binding)
        bindClickListeners(holder)
        onViewHolderCreated(holder, binding)
        return holder
    }

    override fun onBindViewHolder(holder: BindingHolder<VB>, position: Int) {
        val item = getItem(position) ?: return
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
        val item = getItem(position) ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        onBind(holder.binding, item, position, payloads)
    }
}
