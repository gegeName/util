package com.simple.mylibrary.paging

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.ViewDataBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import java.lang.reflect.Method

/**
 * 多布局版 PagingDataAdapter 基类（继承 [BaseClickPagingAdapter]，自带点击事件 + 节流）。
 *
 * 派发方式有两种：
 *
 * 【方式 A】数据实现 [MultiTypeItem] 接口（最常用，零配置）：
 * ```
 * data class FeedItem(
 *     val id: String,
 *     val type: Int,                // 服务端字段
 *     val content: String? = null,
 *     val imageUrl: String? = null,
 *     val videoCover: String? = null
 * ) : MultiTypeItem {
 *     override val itemType: Int get() = type   // 字段名不一致时这样桥接
 * }
 *
 * class FeedAdapter : BaseMultiPagingAdapter<FeedItem>(DIFF) {
 *     init {
 *         addType<ItemFeedTextBinding>(typeValue = 1) { binding, item, _ ->
 *             binding.tvContent.text = item.content
 *         }
 *         addType<ItemFeedImageBinding>(typeValue = 2) { binding, item, _ ->
 *             Glide.with(binding.root).load(item.imageUrl).into(binding.iv)
 *         }
 *         addType<ItemFeedVideoBinding>(typeValue = 3) { binding, item, _ ->
 *             Glide.with(binding.root).load(item.videoCover).into(binding.ivCover)
 *         }
 *     }
 * }
 * ```
 *
 * 【方式 B】sealed class + isMine 谓词（最灵活，复杂判定逻辑用）：
 * ```
 * sealed class FeedItem { data class Text(...) : FeedItem(); data class Image(...) : FeedItem() }
 * class FeedAdapter : BaseMultiPagingAdapter<FeedItem>(DIFF) {
 *     init {
 *         addType<ItemTextBinding>({ it is FeedItem.Text }) { b, item, _ -> ... }
 *         addType<ItemImageBinding>({ it is FeedItem.Image }) { b, item, _ -> ... }
 *     }
 * }
 * ```
 *
 * 两种方式可在同一 Adapter 里混用：A 注册简单类型，B 处理特例。
 *
 * 点击事件 + 节流 API 完全继承自 [BaseClickPagingAdapter]，与 [BasePagingAdapter] 一致。
 */
abstract class BaseMultiPagingAdapter<T : Any>(
    diff: DiffUtil.ItemCallback<T>
) : BaseClickPagingAdapter<T, BaseMultiPagingAdapter.MultiHolder>(diff) {

    /**
     * 通用 ViewHolder：持有任意 ViewBinding；onBind 时由 Adapter 按注册类型回调，业务侧拿到强类型 binding。
     * @property binding 当前 item 对应的 ViewBinding 实例（可能是 ViewBinding 也可能是 ViewDataBinding）
     */
    class MultiHolder(val binding: ViewBinding) : RecyclerView.ViewHolder(binding.root)

    /**
     * 一种 itemType 的注册信息。
     * @property viewType RecyclerView 的 itemViewType，onCreateViewHolder 反查
     * @property isMine 给定 item 是否归此类型；按注册顺序首个返回 true 的胜出
     * @property bindingClass 反射 inflate 用的 ViewBinding 类
     * @property onBind 真正的绑定闭包（已在 inline addType 把 ViewBinding 强转成业务 VB）
     */
    @PublishedApi
    internal class TypeDelegate<T>(
        val viewType: Int,
        val isMine: (T) -> Boolean,
        val bindingClass: Class<out ViewBinding>,
        val onCreate: ((ViewBinding) -> Unit)?,
        val onBind: (ViewBinding, T, Int) -> Unit,
        val onBindPayloads: ((ViewBinding, T, Int, MutableList<Any>) -> Unit)?
    )

    /** 已注册的所有类型代理，按调用 addType 的顺序排列；getItemViewType 按此顺序匹配 */
    @PublishedApi internal val delegates = mutableListOf<TypeDelegate<T>>()
    /** 自动 viewType 分配计数器；addType 不传 viewType 时使用，自增 */
    @PublishedApi internal var nextAutoType: Int = 0

    /**
     * 谓词版：根据 isMine 决定 item 是否归该类型，最灵活；适合 sealed class 类型断言、复杂判定。
     * @param VB ViewBinding / ViewDataBinding 生成类
     * @param isMine 给定 item 是否由此类型渲染；按注册顺序首个返回 true 的胜出
     * @param viewType 显式指定 viewType；不传时自动分配
     * @param onCreate 可选；ViewHolder 创建完 + 点击事件绑完之后只调一次，拿到强类型 binding
     *                 做一次性初始化（嵌套 RV 的 layoutManager / addItemDecoration /
     *                 setRecycledViewPool 等），与 item 数据无关
     * @param onBindPayloads 可选；局部刷新回调（payloads 非空时优先调用）。
     *                       不传或 payloads 为空时回退到全量 [onBind]。
     * @param onBind 拿到强类型 binding 后做绑定，参数为 binding / item / position
     */
    inline fun <reified VB : ViewBinding> addType(
        noinline isMine: (T) -> Boolean,
        viewType: Int = -1,
        noinline onCreate: ((binding: VB) -> Unit)? = null,
        noinline onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
        noinline onBind: (binding: VB, item: T, position: Int) -> Unit
    ) {
        addTypeInternal(
            viewType, isMine, VB::class.java,
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
     * type 字段版：要求 T 实现 [MultiTypeItem] 接口（暴露 `itemType: Int`）。
     * 内部 cast 失败会让所有匹配返回 false，最终在 [getItemViewType] 抛出未匹配异常给出明确提示。
     * 字段名不叫 itemType 时在 data class 里 `override val itemType get() = yourField` 桥接即可。
     *
     * viewType 直接采用 [typeValue]，方便后续 `concatAdapter.findInner...` 调试和按 type 查询。
     *
     * @param VB ViewBinding / ViewDataBinding 生成类
     * @param typeValue 数据 itemType 的具体值，同时作为 RecyclerView 的 viewType（便于按 type 调试）
     * @param onCreate 可选；ViewHolder 创建完 + 点击事件绑完之后只调一次，拿到强类型 binding
     *                 做一次性初始化（嵌套 RV 的 layoutManager / addItemDecoration /
     *                 setRecycledViewPool 等），与 item 数据无关
     * @param onBindPayloads 可选；局部刷新回调（payloads 非空时优先调用）。
     *                       不传或 payloads 为空时回退到全量 [onBind]。
     * @param onBind 拿到强类型 binding 后做绑定，参数为 binding / item / position
     */
    inline fun <reified VB : ViewBinding> addType(
        typeValue: Int,
        noinline onCreate: ((binding: VB) -> Unit)? = null,
        noinline onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
        noinline onBind: (binding: VB, item: T, position: Int) -> Unit
    ) {
        addTypeInternal(
            typeValue,
            isMine = { item -> (item as? MultiTypeItem)?.itemType == typeValue },
            bindingClass = VB::class.java,
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
     * 真正落库的注册入口；inline addType 把强类型 onBind 包装后调到这里。
     * @param viewType 传 -1 时使用 nextAutoType 自增分配，否则采用传入值
     * @param isMine 派发谓词
     * @param bindingClass ViewBinding 生成类，反射 inflate 用
     * @param onBind 类型擦除后的绑定闭包
     */
    @PublishedApi
    internal fun addTypeInternal(
        viewType: Int,
        isMine: (T) -> Boolean,
        bindingClass: Class<out ViewBinding>,
        onCreate: ((ViewBinding) -> Unit)? = null,
        onBind: (ViewBinding, T, Int) -> Unit,
        onBindPayloads: ((ViewBinding, T, Int, MutableList<Any>) -> Unit)? = null
    ) {
        val finalType = if (viewType == -1) nextAutoType++ else viewType
        delegates.add(TypeDelegate(finalType, isMine, bindingClass, onCreate, onBind, onBindPayloads))
    }

    /**
     * onCreateViewHolder 创建完 ViewHolder、绑完点击事件、跑完该 type 的
     * [addType] 里 `onCreate` 之后回调,整个 holder 生命周期只触发一次.
     *
     * 用于跨所有 itemType 的一次性配置(全局钩子);
     * 跟某个具体 type 强相关的配置(嵌套 RV / 装饰 / 监听)更建议用 [addType] 的
     * `onCreate` 参数,拿到强类型 binding,免 when (viewType) 分发.
     *
     * 默认空实现.
     */
    protected open fun onViewHolderCreated(
        holder: MultiHolder,
        binding: ViewBinding,
        viewType: Int
    ) = Unit

    /** 反射 inflate 方法缓存，每种 ViewBinding 类只在首次 onCreateViewHolder 时反射一次 */
    private val inflateCache = mutableMapOf<Class<out ViewBinding>, Method>()

    /**
     * 取（或缓存）一个 ViewBinding 类的静态 inflate(LayoutInflater, ViewGroup, Boolean) 方法。
     * @param cls ViewBinding 子类
     * @return 可直接 invoke 的反射 Method
     */
    private fun inflateOf(cls: Class<out ViewBinding>): Method = inflateCache.getOrPut(cls) {
        cls.getMethod(
            "inflate",
            LayoutInflater::class.java,
            ViewGroup::class.java,
            Boolean::class.javaPrimitiveType
        )
    }

    /**
     * 派发 itemType。按 delegates 注册顺序找首个 isMine 命中的代理；找不到时抛错并提示业务用哪种 addType。
     * @param position 列表位置
     * @return 注册时给定的 viewType（typeValue 重载即数据 itemType；isMine 重载为显式或自动分配值）
     */
    override fun getItemViewType(position: Int): Int {
        val item = getItem(position) ?: return super.getItemViewType(position)
        return delegates.firstOrNull { it.isMine(item) }?.viewType
            ?: error(
                "BaseMultiPagingAdapter: 未匹配到 itemType, item=$item。" +
                    "用 addType(typeValue) 时请让数据实现 MultiTypeItem 接口；" +
                    "或改用 addType(isMine) 谓词版本"
            )
    }

    /**
     * 创建 ViewHolder。按 viewType 反查 delegates，反射 inflate 出对应 ViewBinding，并把点击事件挂上。
     * @param parent RecyclerView 父容器
     * @param viewType getItemViewType 返回的整数值
     */
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MultiHolder {
        val delegate = delegates.firstOrNull { it.viewType == viewType }
            ?: error("BaseMultiPagingAdapter: 未注册的 viewType=$viewType")
        val binding = inflateOf(delegate.bindingClass).invoke(
            null, LayoutInflater.from(parent.context), parent, false
        ) as ViewBinding
        val holder = MultiHolder(binding)
        bindClickListeners(holder)
        delegate.onCreate?.invoke(binding)
        onViewHolderCreated(holder, binding, viewType)
        return holder
    }

    /**
     * 绑定数据。再次按 isMine 找到代理（不依赖 viewType，避免占位 / 局部刷新边界情况）。
     * ViewDataBinding 自动 executePendingBindings；普通 ViewBinding 走业务 onBind。
     * @param holder 创建好的 ViewHolder
     * @param position 列表位置
     */
    override fun onBindViewHolder(holder: MultiHolder, position: Int) {
        val item = getItem(position) ?: return
        val delegate = delegates.firstOrNull { it.isMine(item) } ?: return
        if (holder.binding is ViewDataBinding) {
            (holder.binding as ViewDataBinding).executePendingBindings()
        }
        delegate.onBind(holder.binding, item, position)
    }

    /**
     * 局部刷新分发：
     * - payloads 非空且对应 type 注册了 [TypeDelegate.onBindPayloads]，走增量回调；
     * - 否则回退到全量 [onBindViewHolder]（保留 super 的默认转发语义）。
     *
     * 业务侧用 `notifyItemChanged(pos, payload)` 触发。
     */
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
}
