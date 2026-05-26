package com.chat.myapplication.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.chat.pagingutil.PagingPatcher
import com.chat.pagingutil.pagingFlowOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 商品详情 VM。
 *
 * 4 个非分页接口 + 1 个分页接口的标准组织方式：
 * - 每个非分页接口对应一个 [MutableStateFlow]，UI 层 collect 后喂给对应 section
 * - 评论是分页，直接暴露 [Flow<PagingData<...>>]，[RvPage] 的 `pagingList` 直接接
 * - [loadAll] 用 `async` 并发拉所有非分页接口，速度 = max(各接口耗时)
 */
class GoodsDetailViewModel : ViewModel() {

    private val goodsId = "1024"

    private val _images = MutableStateFlow<List<Int>>(emptyList())
    val images = _images.asStateFlow()

    private val _header = MutableStateFlow<GoodsHeader?>(null)
    val header = _header.asStateFlow()

    private val _spec = MutableStateFlow<GoodsSpec?>(null)
    val spec = _spec.asStateFlow()

    private val _detailBlocks = MutableStateFlow<List<DetailBlock>>(emptyList())
    val detailBlocks = _detailBlocks.asStateFlow()

    private val _commentCount = MutableStateFlow(0)
    val commentCount = _commentCount.asStateFlow()

    private val _recommends = MutableStateFlow<List<RecGoods>>(emptyList())
    val recommends = _recommends.asStateFlow()

    /** 评论分页流（直接接给 pagingList） */
    val commentFlow: Flow<PagingData<Comment>> = pagingFlowOf(pageSize = 10) { page, size ->
        FakeGoodsApi.comments(goodsId, page, size)
    }

    /** 评论的字段级补丁器（演示点赞乐观更新会用到） */
    val commentPatcher = PagingPatcher<Any, Comment> { it.id }

    init {
        loadAll()
    }

    /** 并发拉 4 个非分页接口；评论是 paging，由 RvPage 内部管理 */
    fun loadAll() = viewModelScope.launch {
        val headerJob = async { runCatching { FakeGoodsApi.header(goodsId) } }
        val specJob = async { runCatching { FakeGoodsApi.spec(goodsId) } }
        val detailJob = async { runCatching { FakeGoodsApi.detail(goodsId) } }
        val recJob = async { runCatching { FakeGoodsApi.recommend(goodsId) } }

        headerJob.await().onSuccess {
            _images.value = it.images
            _header.value = it.header
        }
        specJob.await().onSuccess { _spec.value = it }
        detailJob.await().onSuccess { _detailBlocks.value = it }
        recJob.await().onSuccess { _recommends.value = it }
        _commentCount.value = 50
    }

    /** 点击标题区:销量 +1,模拟服务端返回新销量后刷新本地 */
    fun incrementSales() {
        _header.value = _header.value?.let { it.copy(sales = it.sales + 1) }
    }

    private val specRotation = listOf(
        GoodsSpec(selectedLabel = "已选: 黑色 / 256GB", deliveryDays = "预计 24 小时内发货"),
        GoodsSpec(selectedLabel = "已选: 白色 / 512GB", deliveryDays = "预计 48 小时内发货"),
        GoodsSpec(selectedLabel = "已选: 蓝色 / 1TB", deliveryDays = "下单后 3 天内发货"),
    )
    private var specIndex = 0

    /** 点击规格行:在预设规格之间循环切换,模拟用户改选规格后服务端返回新报价/库存 */
    fun cycleSpec() {
        specIndex = (specIndex + 1) % specRotation.size
        _spec.value = specRotation[specIndex]
    }

    /** 点击文字详情块:文案前加/去 ★ 标记,演示列表中单条的局部刷新 */
    fun toggleDetailStar(id: Long) {
        _detailBlocks.value = _detailBlocks.value.map { block ->
            if (block.id != id || block.itemType != DetailBlock.TYPE_TEXT) block
            else {
                val text = block.text.orEmpty()
                val newText = if (text.startsWith("★ ")) text.removePrefix("★ ") else "★ $text"
                block.copy(text = newText)
            }
        }
    }

    /** 点击推荐商品:在价格后追加/取消 ❤,模拟收藏状态切换 */
    fun toggleRecLike(id: Long) {
        _recommends.value = _recommends.value.map { rec ->
            if (rec.id != id) rec
            else {
                val price = rec.priceText
                val newPrice = if (price.endsWith(" ❤")) price.removeSuffix(" ❤") else "$price ❤"
                rec.copy(priceText = newPrice)
            }
        }
    }
}
