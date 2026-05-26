package com.chat.rv_page

import androidx.viewbinding.ViewBinding
import com.chat.pagingutil.SingleItemBindingAdapter

/**
 * 把数据喂给指定 tag 的 section。
 *
 * 这里集中三个扩展，把 [RvPageController.findAdapter] + 强制类型转换 + 调 `submit(...)`
 * 的样板包成一行，业务侧避免每个 collect 块都写 cast。
 *
 * **行为约定**：
 * - tag 找不到 / cast 失败 → **静默忽略**（一般是业务 tag 写错或 section 还没装配完毕），
 *   UI 表现是"section 没变化"。调试时通过对照 [RvPageController.findEntry] 自查 tag。
 * - 这层扩展只覆盖 RvPage 内置的 3 种 Adapter；业务通过 `custom(tag, adapter)` 挂的自家
 *   Adapter 直接用 [RvPageController.findAdapter] 自己 cast。
 *
 * 典型用法（详情页 collect 多个 StateFlow）：
 * ```
 * viewLifecycleOwner.lifecycleScope.launch {
 *     viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
 *         launch { vm.header.collect { it?.let { page.submitSingle<GoodsHeader>("header", it) } } }
 *         launch { vm.detailBlocks.collect { page.submitMultiList<DetailBlock>("details", it) } }
 *         launch { vm.recommends.collect   { page.submitList<RecGoods>("recommend", it) } }
 *     }
 * }
 * ```
 */

/**
 * 喂数据给 `single { ... }` 声明的 section（底层 [SingleItemBindingAdapter]）。
 *
 * @param T 数据类型，需与声明 section 时的 `single<T, VB>(...)` 一致
 * @param tag 声明 single 时给的 tag
 * @param data 新数据；传 null 视为隐藏该格（[SingleItemBindingAdapter] 内部 itemCount 变 0）
 */
@Suppress("UNCHECKED_CAST")
fun <T : Any> RvPageController.submitSingle(tag: String, data: T?) {
    (findAdapter(tag) as? SingleItemBindingAdapter<T, ViewBinding>)?.submit(data)
}

/**
 * 喂数据给 `list { ... }` 声明的 section（底层 [BaseListAdapter]）。
 *
 * @param T item 类型，需与声明时的 `list<T, VB>(...)` 一致
 * @param tag 声明 list 时给的 tag
 * @param list 新数据；传空列表会清空该 section（但 section 本身仍占位 0 个 item）
 * @param commitCallback diff 落地后回调，可在此 scrollToTop 等
 */
@Suppress("UNCHECKED_CAST")
fun <T : Any> RvPageController.submitList(
    tag: String,
    list: List<T>?,
    commitCallback: (() -> Unit)? = null
) {
    (findAdapter(tag) as? BaseListAdapter<T, ViewBinding>)?.submit(list, commitCallback)
}

/**
 * 喂数据给 `multiList { ... }` 声明的 section（底层 [BaseMultiListAdapter]）。
 *
 * @param T item 类型，需与声明时的 `multiList<T>(...)` 一致
 * @param tag 声明 multiList 时给的 tag
 * @param list 新数据
 * @param commitCallback diff 落地后回调
 */
@Suppress("UNCHECKED_CAST")
fun <T : Any> RvPageController.submitMultiList(
    tag: String,
    list: List<T>?,
    commitCallback: (() -> Unit)? = null
) {
    (findAdapter(tag) as? BaseMultiListAdapter<T>)?.submit(list, commitCallback)
}
