package com.chat.myapplication.sample

import android.graphics.Color
import kotlinx.coroutines.delay

/**
 * 假商品详情接口：delay 模拟网络耗时；返回结构化数据让 VM 拆给各 section。
 */
object FakeGoodsApi {

    private val palette = intArrayOf(
        Color.parseColor("#FFCDD2"),
        Color.parseColor("#F8BBD0"),
        Color.parseColor("#E1BEE7"),
        Color.parseColor("#C5CAE9"),
        Color.parseColor("#BBDEFB"),
        Color.parseColor("#B2DFDB"),
        Color.parseColor("#DCEDC8"),
        Color.parseColor("#FFE0B2"),
        Color.parseColor("#D7CCC8"),
        Color.parseColor("#CFD8DC")
    )

    private fun colorOf(seed: Long) = palette[(seed and Long.MAX_VALUE).toInt() % palette.size]

    /** 头部信息：图片列表 + 标题价格 */
    suspend fun header(goodsId: String): GoodsHeaderResp {
        delay(400)
        return GoodsHeaderResp(
            images = listOf(colorOf(1), colorOf(2), colorOf(3), colorOf(4), colorOf(5)),
            header = GoodsHeader(
                name = "ID=$goodsId · Demo 商品 · RvPage 演示套装",
                priceText = "¥ 199",
                originalPriceText = "¥ 299",
                sales = 12_345
            )
        )
    }

    suspend fun spec(goodsId: String): GoodsSpec {
        delay(300)
        return GoodsSpec(
            selectedLabel = "默认款 / 1 件",
            deliveryDays = "预计 2-4 天送达"
        )
    }

    /** 详情区块（多类型）：随机文/图/视频 */
    suspend fun detail(goodsId: String): List<DetailBlock> {
        delay(500)
        val out = mutableListOf<DetailBlock>()
        var id = 0L
        out += DetailBlock(id++, DetailBlock.TYPE_TEXT, text = "【商品介绍】这是一个用来演示 RvPage 多类型详情区块的商品。")
        out += DetailBlock(id++, DetailBlock.TYPE_IMAGE, imageColor = colorOf(11))
        out += DetailBlock(id++, DetailBlock.TYPE_TEXT, text = "选用优质材料，工艺精良。所有图片以纯色块代替真实商品图。")
        out += DetailBlock(id++, DetailBlock.TYPE_IMAGE, imageColor = colorOf(12))
        out += DetailBlock(id++, DetailBlock.TYPE_VIDEO, videoColor = colorOf(13))
        out += DetailBlock(id++, DetailBlock.TYPE_TEXT, text = "【规格参数】尺寸：通用 / 重量：约 200g / 产地：中国")
        out += DetailBlock(id++, DetailBlock.TYPE_IMAGE, imageColor = colorOf(14))
        out += DetailBlock(id++, DetailBlock.TYPE_TEXT, text = "【售后说明】支持 7 天无理由退换，详情请咨询客服。")
        return out
    }

    /** 评论分页接口：一共 50 条 */
    suspend fun comments(goodsId: String, page: Int, pageSize: Int): Pair<List<Comment>, Boolean> {
        delay(450)
        val total = 50
        val start = (page - 1) * pageSize
        if (start >= total) return emptyList<Comment>() to false
        val end = minOf(start + pageSize, total)
        val list = (start until end).map { i ->
            val seed = (i + 1).toLong()
            Comment(
                id = seed,
                userName = "用户${i + 1}",
                avatarColor = colorOf(seed),
                content = "这是第 ${i + 1} 条评论，演示分页评论加载。" +
                    if (i % 3 == 0) "宝贝收到了，质量不错，下次还来！" else "",
                likeCount = (i * 7) % 99,
                date = "2025-${(i % 12) + 1}-${(i % 28) + 1}"
            )
        }
        return list to (end < total)
    }

    /** 推荐商品 */
    suspend fun recommend(goodsId: String): List<RecGoods> {
        delay(350)
        return (1..6).map { i ->
            RecGoods(
                id = i.toLong(),
                name = "推荐商品 #$i",
                priceText = "¥ ${i * 19 + 9}",
                coverColor = colorOf((i + 100).toLong())
            )
        }
    }
}

data class GoodsHeaderResp(
    val images: List<Int>,
    val header: GoodsHeader
)
