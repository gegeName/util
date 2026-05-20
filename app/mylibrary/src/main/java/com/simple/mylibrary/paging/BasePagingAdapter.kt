package com.simple.mylibrary.paging
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import java.lang.reflect.ParameterizedType

/**
 * 单布局版 PagingDataAdapter 基类（继承 [BaseClickPagingAdapter]，自带点击事件 + 节流）。
 *
 * 子类只需实现 [onBind]，无需重写 onCreateViewHolder。
 *
 * 示例：
 * ```
 * class UserAdapter : BasePagingAdapter<UserItem, ItemUserBinding>(DIFF) {
 *     override fun onBind(binding: ItemUserBinding, item: UserItem, position: Int) {
 *         binding.tvNickname.text = item.nickname
 *     }
 * }
 *
 * userAdapter.setOnItemClickListener(
 *     throttleMs = 800,
 *     keyOf = { it.userId }
 * ) { v, item, pos -> openProfile(item.userId) }
 * ```
 */
abstract class BasePagingAdapter<T : Any, VB : ViewBinding>(
    diff: DiffUtil.ItemCallback<T>
) : BaseClickPagingAdapter<T, BasePagingAdapter.BindingHolder<VB>>(diff) {

    class BindingHolder<VB : ViewBinding>(val binding: VB) : RecyclerView.ViewHolder(binding.root)

    abstract fun onBind(binding: VB, item: T, position: Int)

    /**
     * 局部刷新回调，业务用 `notifyItemChanged(pos, payload)` 触发。
     * 默认实现回退到全量 [onBind]，所以不重写也安全。
     * @param payloads 非空；空 payloads 由 [onBindViewHolder] 拦截直接走全量分支
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
     * - 给 holder.itemView 上挂 tag、自定义属性
     *
     * 与 [onBind] 区分:
     * - onBind 每次滚动复用 / 数据变更都会被调,适合写"数据 → 视图"的映射
     * - onViewHolderCreated 只调一次,适合写"View 自身的结构 / 行为初始化"
     *
     * 默认空实现,业务按需 override.
     */
    protected open fun onViewHolderCreated(holder: BindingHolder<VB>, binding: VB) = Unit

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BindingHolder<VB> {
        @Suppress("UNCHECKED_CAST")
        val binding = inflateMethod.invoke(
            null, LayoutInflater.from(parent.context), parent, false
        ) as VB
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

    /**
     * 局部刷新分发：payloads 为空时回退到全量 [onBindViewHolder]，
     * 否则走带 payloads 的 [onBind] 重载。
     */
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
