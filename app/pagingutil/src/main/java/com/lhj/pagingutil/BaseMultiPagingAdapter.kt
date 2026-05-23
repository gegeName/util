package com.lhj.pagingutil

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding

/**
 * 多布局 PagingDataAdapter 基类。
 *
 * 派发方式：
 * - 【方式 A】数据实现 [MultiTypeItem]：`addType(typeValue, ItemXxxBinding::inflate) { ... }`
 * - 【方式 B】谓词：`addType(isMine = { ... }, inflate = ItemXxxBinding::inflate) { ... }`
 *
 * 点击事件 + 节流 API 继承自 [BaseClickPagingAdapter]。
 */
abstract class BaseMultiPagingAdapter<T : Any>(
    diff: DiffUtil.ItemCallback<T>
) : BaseClickPagingAdapter<T, BaseMultiPagingAdapter.MultiHolder>(diff) {

    class MultiHolder(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root)

    private class TypeDelegate<T>(
        val viewType: Int,
        val isMine: (T) -> Boolean,
        val inflate: (LayoutInflater, ViewGroup, Boolean) -> ViewBinding,
        val onCreate: ((ViewBinding) -> Unit)?,
        val onBind: (ViewBinding, T, Int) -> Unit,
        val onBindPayloads: ((ViewBinding, T, Int, MutableList<Any>) -> Unit)?
    )

    private val delegates = mutableListOf<TypeDelegate<T>>()
    private var nextAutoType: Int = 0

    /**
     * 谓词版：根据 [isMine] 决定 item 是否归该类型，按注册顺序首个返回 true 的胜出。
     *
     * @param isMine          判断 item 是否由此类型渲染
     * @param inflate         形如 `ItemXxxBinding::inflate` 的函数引用
     * @param viewType        显式指定 viewType；不传时自动分配
     * @param onCreate        可选；ViewHolder + 点击事件绑完后只调一次，做与数据无关的初始化
     * @param onBindPayloads  可选；局部刷新回调（payloads 非空时优先调用）
     * @param onBind          拿到强类型 binding 后做绑定
     */
    fun <VB : ViewBinding> addType(
        isMine: (T) -> Boolean,
        inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
        viewType: Int = -1,
        onCreate: ((binding: VB) -> Unit)? = null,
        onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
        onBind: (binding: VB, item: T, position: Int) -> Unit
    ) {
        val finalType = if (viewType == -1) nextAutoType++ else viewType
        require(delegates.none { it.viewType == finalType }) {
            "BaseMultiPagingAdapter: viewType=$finalType 已注册，请改用不同的 viewType / typeValue"
        }
        @Suppress("UNCHECKED_CAST")
        delegates.add(
            TypeDelegate(
                viewType = finalType,
                isMine = isMine,
                inflate = inflate as (LayoutInflater, ViewGroup, Boolean) -> ViewBinding,
                onCreate = onCreate?.let { cb ->
                    { b ->
                        @Suppress("UNCHECKED_CAST")
                        cb(b as VB)
                    }
                },
                onBind = { b, item, pos ->
                    @Suppress("UNCHECKED_CAST")
                    onBind(b as VB, item, pos)
                },
                onBindPayloads = onBindPayloads?.let { cb ->
                    { b, item, pos, payloads ->
                        @Suppress("UNCHECKED_CAST")
                        cb(b as VB, item, pos, payloads)
                    }
                }
            )
        )
    }

    /**
     * type 字段版：要求 T 实现 [MultiTypeItem]，viewType 直接采用 [typeValue]。
     *
     * @param typeValue       数据 itemType 的具体值，同时作为 RecyclerView 的 viewType
     * @param inflate         形如 `ItemXxxBinding::inflate` 的函数引用
     * @param onCreate        可选；ViewHolder + 点击事件绑完后只调一次
     * @param onBindPayloads  可选；局部刷新回调
     * @param onBind          拿到强类型 binding 后做绑定
     */
    fun <VB : ViewBinding> addType(
        typeValue: Int,
        inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
        onCreate: ((binding: VB) -> Unit)? = null,
        onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
        onBind: (binding: VB, item: T, position: Int) -> Unit
    ) {
        addType(
            isMine = { item -> (item as? MultiTypeItem)?.itemType == typeValue },
            inflate = inflate,
            viewType = typeValue,
            onCreate = onCreate,
            onBindPayloads = onBindPayloads,
            onBind = onBind
        )
    }

    /**
     * 跨所有 itemType 的一次性配置钩子，每个 holder 生命周期内只触发一次。
     *
     * @param holder   新建的 [MultiHolder]
     * @param binding  对应的 ViewBinding（类型擦除）
     * @param viewType 当前 holder 的 viewType
     */
    protected open fun onViewHolderCreated(
        holder: MultiHolder,
        binding: ViewBinding,
        viewType: Int
    ) = Unit

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position) ?: return super.getItemViewType(position)
        return delegates.firstOrNull { it.isMine(item) }?.viewType
            ?: error(
                "BaseMultiPagingAdapter: 未匹配到 itemType, item=$item。" +
                        "用 addType(typeValue) 时请让数据实现 MultiTypeItem 接口；" +
                        "或改用 addType(isMine) 谓词版本"
            )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MultiHolder {
        val delegate = delegates.firstOrNull { it.viewType == viewType }
            ?: error("BaseMultiPagingAdapter: 未注册的 viewType=$viewType")
        val binding = delegate.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = MultiHolder(binding)
        bindClickListeners(holder)
        delegate.onCreate?.invoke(binding)
        onViewHolderCreated(holder, binding, viewType)
        return holder
    }

    override fun onBindViewHolder(holder: MultiHolder, position: Int) {
        val item = getItem(position) ?: return
        val delegate = delegates.firstOrNull { it.viewType == holder.itemViewType } ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        delegate.onBind(holder.binding, item, position)
    }

    override fun onBindViewHolder(
        holder: MultiHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        val item = getItem(position)
        val delegate = delegates.firstOrNull { it.viewType == holder.itemViewType }
        val payloadsCb = delegate?.onBindPayloads
        if (item == null || delegate == null || payloadsCb == null) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        payloadsCb(holder.binding, item, position, payloads)
    }
}
