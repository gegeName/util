package com.chat.rv_page

import com.chat.pagingutil.PagingController
import com.chat.pagingutil.SingleItemBindingAdapter

/**
 * Section 声明返回的强类型句柄，让业务侧用变量直接持有 section，不再依赖 tag 字符串。
 *
 * 用法：
 * ```
 * private lateinit var bannerSection: SingleSectionHandle<List<String>>
 * private lateinit var headerSection: SingleSectionHandle<GoodsHeader>
 *
 * page = RvPage.with(owner).sections {
 *     bannerSection = single<List<String>, ItemBannerBinding>(ItemBannerBinding::inflate) {
 *         onBind { b, urls -> ... }
 *     }
 *     headerSection = single<GoodsHeader, ItemHeaderBinding>(ItemHeaderBinding::inflate) {
 *         onBind { b, d -> ... }
 *     }
 * }.start()
 *
 * // 拉到数据后直接 submit，无需 tag、无需 cast
 * launch { vm.images.collect { bannerSection.submit(it) } }
 * launch { vm.header.collect { headerSection.submit(it) } }
 * ```
 *
 * 仍然兼容 tag：在 single/list 等声明时显式传 tag 后，既可用 handle 也可用
 * [RvPageController.findAdapter] 按 tag 查找；动态增删 section 仍然走 tag。
 */

/** `single { ... }` 返回的句柄 */
class SingleSectionHandle<T : Any> internal constructor(
    /** 底层 Adapter；高级用法可拿来调 setVisible / notifyPayload 等 */
    val adapter: SingleItemBindingAdapter<T, *>
) {
    /** 直接喂数据；null 视为隐藏该格 */
    fun submit(data: T?) = adapter.submit(data)
}

/** `list { ... }` 返回的句柄 */
class ListSectionHandle<T : Any> internal constructor(
    val adapter: BaseListAdapter<T, *>
) {
    /** 提交全量数据，触发后台 diff */
    fun submit(list: List<T>?, commitCallback: (() -> Unit)? = null) =
        adapter.submit(list, commitCallback)
}

/** `multiList { ... }` 返回的句柄 */
class MultiListSectionHandle<T : Any> internal constructor(
    val adapter: BaseMultiListAdapter<T>
) {
    fun submit(list: List<T>?, commitCallback: (() -> Unit)? = null) =
        adapter.submit(list, commitCallback)
}

/**
 * `pagingList { ... }` / `pagingMultiList { ... }` 返回的句柄。
 *
 * 注意：[controller] 在 [RvPageBuilder.start] 完成后才被填充；
 * 在 sections {} block 内或 start() 之前访问会得到 null。
 * 业务侧通常在事件回调（点击 / 滚动）里用 controller，那时 start() 已完成。
 */
class PagingSectionHandle<T : Any> internal constructor() {
    private var _controller: PagingController<T>? = null

    /** 拿到分页控制器；start() 完成后非空 */
    val controller: PagingController<T>? get() = _controller

    @Suppress("UNCHECKED_CAST")
    internal fun bind(controller: PagingController<*>) {
        _controller = controller as PagingController<T>
    }
}

/**
 * `custom(adapter)` 返回的句柄：直接给业务自家 Adapter 用，无 submit 语义。
 */
class CustomSectionHandle internal constructor(
    val adapter: androidx.recyclerview.widget.RecyclerView.Adapter<*>
)

/** `carousel { ... }` 返回的句柄。 */
class CarouselSectionHandle<T : Any> internal constructor(
    internal val adapter: CarouselAdapter<T>
) {
    /** 提交横向列表数据;null 视为清空。 */
    fun submit(list: List<T>?) = adapter.submit(list)
}
