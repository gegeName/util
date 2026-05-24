package com.chat.pagingutil

/**
 * 多布局数据项的可选契约：data class 实现该接口暴露 [itemType]，
 * 即可在 [BaseMultiPagingAdapter] 里直接用 `addType(typeValue = ...)` 派发。
 *
 * ```
 * data class FeedItem(
 *     val id: String,
 *     override val itemType: Int,
 *     val content: String?,
 *     val imageUrl: String?
 * ) : MultiTypeItem
 *
 * class FeedAdapter : BaseMultiPagingAdapter<FeedItem>(DIFF) {
 *     init {
 *         addType(typeValue = 1, inflate = ItemTextBinding::inflate) { b, item, _ -> b.tv.text = item.content }
 *         addType(typeValue = 2, inflate = ItemImageBinding::inflate) { b, item, _ -> Glide... }
 *     }
 * }
 * ```
 *
 * 不想实现该接口时改用谓词版：`addType(isMine = { ... }, inflate = ItemXxxBinding::inflate) { ... }`
 */
interface MultiTypeItem {
    val itemType: Int
}
