package com.chat.myapplication.sample

import androidx.recyclerview.widget.DiffUtil
import com.chat.pagingutil.MultiTypeItem

/** 商品标题 + 价格区 */
data class GoodsHeader(
    val name: String,
    val priceText: String,
    val originalPriceText: String,
    val sales: Int
)

/** 商品规格区 */
data class GoodsSpec(
    val selectedLabel: String,
    val deliveryDays: String
)

/** 详情多类型区块：文字 / 图片 / 视频 */
data class DetailBlock(
    val id: Long,
    override val itemType: Int,
    val text: String? = null,
    val imageColor: Int = 0,   // 用颜色代替真实图片，方便 demo 不依赖网络
    val videoColor: Int = 0
) : MultiTypeItem {
    companion object {
        const val TYPE_TEXT = 1
        const val TYPE_IMAGE = 2
        const val TYPE_VIDEO = 3
    }
}

/** 评论 */
data class Comment(
    val id: Long,
    val userName: String,
    val avatarColor: Int,
    val content: String,
    val likeCount: Int,
    val date: String
)

/** 相关推荐商品 */
data class RecGoods(
    val id: Long,
    val name: String,
    val priceText: String,
    val coverColor: Int
)

// ───── DiffUtil ─────

val DETAIL_BLOCK_DIFF = object : DiffUtil.ItemCallback<DetailBlock>() {
    override fun areItemsTheSame(old: DetailBlock, new: DetailBlock) = old.id == new.id
    override fun areContentsTheSame(old: DetailBlock, new: DetailBlock) = old == new
}

val COMMENT_DIFF = object : DiffUtil.ItemCallback<Comment>() {
    override fun areItemsTheSame(old: Comment, new: Comment) = old.id == new.id
    override fun areContentsTheSame(old: Comment, new: Comment) = old == new
}

val REC_GOODS_DIFF = object : DiffUtil.ItemCallback<RecGoods>() {
    override fun areItemsTheSame(old: RecGoods, new: RecGoods) = old.id == new.id
    override fun areContentsTheSame(old: RecGoods, new: RecGoods) = old == new
}
