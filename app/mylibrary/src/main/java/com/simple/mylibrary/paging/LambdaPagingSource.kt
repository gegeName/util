package com.simple.mylibrary.paging

/**
 * 通用 PagingSource：把 [fetcher] 放到构造里，业务每个列表接口不用再单独写子类。
 *
 * 仍然继承 [BasePagingSource]，沿用 prevKey/nextKey 计算与 getRefreshKey 行为。
 *
 * 用法见 [pagingFlowOf]：直接在 ViewModel 里传 lambda 即可。
 *
 * @param startPage 起始页码，默认 1
 * @param refreshFromStart 刷新时是否回首页，详见 [BasePagingSource]
 * @param fetcher 实际拉数据的 suspend lambda，返回 (本页数据, 是否还有下一页)
 */
class LambdaPagingSource<T : Any>(
    startPage: Int = 1,
    refreshFromStart: Boolean = false,
    private val fetcher: suspend (page: Int, pageSize: Int) -> Pair<List<T>, Boolean>
) : BasePagingSource<T>(startPage, refreshFromStart) {
    override suspend fun fetch(page: Int, pageSize: Int): Pair<List<T>, Boolean> =
        fetcher(page, pageSize)
}
