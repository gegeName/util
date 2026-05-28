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
import com.chat.pagingutil.*

/**
 * 多布局 **非分页** RecyclerView Adapter 基类（与 [BaseMultiPagingAdapter] 对偶）。
 *
 * **无反射版**：[addType] 多接一个 `inflate` 函数引用参数（如 `ItemTextBlockBinding::inflate`）。
 *
 * 派发方式与 [BaseMultiPagingAdapter] 完全一致：
 *
 * 【方式 A】数据实现 [MultiTypeItem] 接口：
 * ```
 * data class DetailBlock(
 *     override val itemType: Int,
 *     val text: String? = null,
 *     val imageUrl: String? = null
 * ) : MultiTypeItem
 *
 * class BlocksAdapter : BaseMultiListAdapter<DetailBlock>(BLOCK_DIFF) {
 *     init {
 *         addType(typeValue = 1, inflate = ItemTextBlockBinding::inflate) { b, item, _ ->
 *             b.tv.text = item.text
 *         }
 *         addType(typeValue = 2, inflate = ItemImageBlockBinding::inflate) { b, item, _ ->
 *             Glide.with(b.iv).load(item.imageUrl).into(b.iv)
 *         }
 *     }
 * }
 * adapter.submit(vm.blocks)
 * ```
 *
 * 【方式 B】sealed class + isMine 谓词：
 * ```
 * sealed class Block { data class Text(...) : Block(); data class Image(...) : Block() }
 * adapter.addType(isMine = { it is Block.Text }, inflate = ItemTextBinding::inflate) { b, item, _ -> ... }
 * adapter.addType(isMine = { it is Block.Image }, inflate = ItemImageBinding::inflate) { b, item, _ -> ... }
 * ```
 *
 * 点击事件 + 节流 API 与 [BasePagingAdapter] 一致。
 *
 * @param T item 类型
 * @param diff DiffUtil.ItemCallback
 */
abstract class BaseMultiListAdapter<T : Any>(
    diff: DiffUtil.ItemCallback<T>
) : RecyclerView.Adapter<BaseMultiListAdapter.MultiHolder>() {

    class MultiHolder(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root)

    @PublishedApi
    internal class TypeDelegate<T>(
        val viewType: Int,
        val isMine: (T) -> Boolean,
        val inflate: (LayoutInflater, ViewGroup, Boolean) -> ViewBinding,
        val onCreate: ((ViewBinding) -> Unit)?,
        val onBind: (ViewBinding, T, Int) -> Unit,
        val onBindPayloads: ((ViewBinding, T, Int, MutableList<Any>) -> Unit)?
    )

    @PublishedApi
    internal val delegates = mutableListOf<TypeDelegate<T>>()

    @PublishedApi
    internal var nextAutoType: Int = 0

    private val differ = AsyncListDiffer(this, diff)

    /** 当前完整数据快照 */
    val currentList: List<T> get() = differ.currentList

    fun submit(list: List<T>?, commitCallback: (() -> Unit)? = null) {
        if (commitCallback != null) differ.submitList(list) { commitCallback() }
        else differ.submitList(list)
    }

    override fun getItemCount(): Int = differ.currentList.size
    fun getItemOrNull(position: Int): T? = differ.currentList.getOrNull(position)

    /**
     * 谓词版：最灵活，适合 sealed class 类型断言。
     * @param VB ViewBinding / ViewDataBinding 生成类
     * @param isMine 给定 item 是否由此类型渲染；按注册顺序首个返回 true 的胜出
     * @param inflate ViewBinding inflate 函数引用，通常写成 `XxxBinding::inflate`
     * @param viewType 显式指定 viewType；不传时自动分配
     * @param onCreate 可选；ViewHolder 创建完 + 点击事件绑完之后只调一次
     * @param onBindPayloads 可选；局部刷新回调
     * @param onBind 拿到强类型 binding 后做绑定
     */
    fun <VB : ViewBinding> addType(
        isMine: (T) -> Boolean,
        inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
        viewType: Int = -1,
        onCreate: ((binding: VB) -> Unit)? = null,
        onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
        onBind: (binding: VB, item: T, position: Int) -> Unit
    ) {
        @Suppress("UNCHECKED_CAST")
        addTypeInternal(
            viewType, isMine,
            inflate = inflate as (LayoutInflater, ViewGroup, Boolean) -> ViewBinding,
            onCreate = onCreate?.let { cb ->
                { binding ->
                    @Suppress("UNCHECKED_CAST")
                    cb(binding as VB)
                }
            },
            onBind = { binding, item, pos ->
                @Suppress("UNCHECKED_CAST")
                onBind(binding as VB, item, pos)
            },
            onBindPayloads = onBindPayloads?.let { cb ->
                { binding, item, pos, payloads ->
                    @Suppress("UNCHECKED_CAST")
                    cb(binding as VB, item, pos, payloads)
                }
            }
        )
    }

    /**
     * type 字段版：要求 T 实现 [MultiTypeItem] 接口。viewType 直接采用 [typeValue]。
     * @param inflate ViewBinding inflate 函数引用，通常写成 `XxxBinding::inflate`
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

    @PublishedApi
    internal fun addTypeInternal(
        viewType: Int,
        isMine: (T) -> Boolean,
        inflate: (LayoutInflater, ViewGroup, Boolean) -> ViewBinding,
        onCreate: ((ViewBinding) -> Unit)? = null,
        onBind: (ViewBinding, T, Int) -> Unit,
        onBindPayloads: ((ViewBinding, T, Int, MutableList<Any>) -> Unit)? = null
    ) {
        val finalType = if (viewType == -1) nextAutoType++ else viewType
        delegates.add(TypeDelegate(finalType, isMine, inflate, onCreate, onBind, onBindPayloads))
    }

    /**
     * 跨所有 itemType 的全局一次性配置；具体某 type 的初始化建议用 [addType] 的 `onCreate` 参数。
     */
    protected open fun onViewHolderCreated(
        holder: MultiHolder,
        binding: ViewBinding,
        viewType: Int
    ) = Unit

    override fun getItemViewType(position: Int): Int {
        val item = getItemOrNull(position) ?: return super.getItemViewType(position)
        return delegates.firstOrNull { it.isMine(item) }?.viewType
            ?: error(
                "BaseMultiListAdapter: 未匹配到 itemType, item=$item。" +
                        "用 addType(typeValue) 时请让数据实现 MultiTypeItem 接口；" +
                        "或改用 addType(isMine) 谓词版本"
            )
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MultiHolder {
        val delegate = delegates.firstOrNull { it.viewType == viewType }
            ?: error("BaseMultiListAdapter: 未注册的 viewType=$viewType")
        val binding = delegate.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = MultiHolder(binding)
        bindClickListeners(holder)
        delegate.onCreate?.invoke(binding)
        onViewHolderCreated(holder, binding, viewType)
        return holder
    }

    override fun onBindViewHolder(holder: MultiHolder, position: Int) {
        val item = getItemOrNull(position) ?: return
        val delegate = delegates.firstOrNull { it.isMine(item) } ?: return
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
        val item = getItemOrNull(position)
        val delegate = item?.let { delegates.firstOrNull { d -> d.isMine(it) } }
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

    // ───── 点击事件 + 节流（与 BaseClickPagingAdapter 等价） ─────

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

    private fun bindClickListeners(holder: MultiHolder) {
        holder.itemView.setOnClickListener { v ->
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            val item = getItemOrNull(pos) ?: return@setOnClickListener
            val keyOf = itemClickKeyOf
            val originalKey = keyOf?.invoke(item)
            val throttleKey = originalKey ?: pos
            if (!throttle(
                    itemClickLastTs,
                    itemClickThrottleMs,
                    throttleKey
                )
            ) return@setOnClickListener
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
                if (!throttle(
                        childClickLastTs,
                        itemChildClickThrottleMs,
                        throttleKey
                    )
                ) return@setOnClickListener
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
