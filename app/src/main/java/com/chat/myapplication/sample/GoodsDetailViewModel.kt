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
}
