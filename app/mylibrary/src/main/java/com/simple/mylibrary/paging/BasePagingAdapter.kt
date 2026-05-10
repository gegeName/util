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
        return holder
    }

    override fun onBindViewHolder(holder: BindingHolder<VB>, position: Int) {
        val item = getItem(position) ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        onBind(holder.binding, item, position)
    }
}
