package com.simple.mylibrary.paging

/**
 * 多布局数据项的可选契约：data class 实现该接口暴露 [itemType]，
 * 即可在 [BaseMultiPagingAdapter] 构造时不需手写 typeOf 提取器，直接：
 *
 * ```
 * data class FeedItem(
 *     val id: String,
 *     override val itemType: Int,
 *     val content: String?,
 *     val imageUrl: String?
 * ) : MultiTypeItem
 *
 * class FeedAdapter : BaseMultiPagingAdapter<FeedItem>(DIFF, typeOf = { it.itemType }) {
 *     init {
 *         addType<ItemTextBinding>(typeValue = 1) { b, item, _ -> b.tv.text = item.content }
 *         addType<ItemImageBinding>(typeValue = 2) { b, item, _ -> Glide... }
 *     }
 * }
 * ```
 *
 * 不实现该接口也可以——任意字段做派发依据，只要在构造里传 typeOf：
 * ```
 * class FeedAdapter : BaseMultiPagingAdapter<FeedItem>(DIFF, typeOf = { it.style })
 * ```
 */
interface MultiTypeItem {
    val itemType: Int
}
