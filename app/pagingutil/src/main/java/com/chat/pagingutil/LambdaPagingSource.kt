package com.chat.pagingutil

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

/**
 * 双向版通用 PagingSource:把 [fetcher] 改成支持 [LoadDirection] 派发,
 * 业务可以在同一个 lambda 里根据方向走不同接口 / 入参,典型聊天 / 时间轴场景。
 *
 * 与 [LambdaPagingSource] 的区别:fetcher 拿到的是方向 + 必须返回 [PageResult],
 * 业务能控制 `hasPrev`(开启 PREPEND 的开关)和按方向分别决定 `hasMore`。
 *
 * 用法见双向版 [pagingFlowOf]:
 * ```
 * val chatFlow = pagingFlowOf { page, size, direction ->
 *     when (direction) {
 *         LoadDirection.REFRESH -> api.latest(roomId, size).run {
 *             PageResult(list, hasMore = false, hasPrev = hasOlder)
 *         }
 *         LoadDirection.PREPEND -> api.byPage(roomId, page, size).run {
 *             PageResult(list, hasMore = hasOlder)
 *         }
 *         LoadDirection.APPEND -> PageResult(emptyList(), hasMore = false)
 *     }
 * }
 * ```
 *
 * @param startPage 起始页码,默认 1
 * @param refreshFromStart 刷新时是否回首页,详见 [BasePagingSource]
 * @param fetcher 实际拉数据的 suspend lambda,签名 = [BasePagingSource.fetchBidirectional]
 */
class BidirectionalLambdaPagingSource<T : Any>(
    startPage: Int = 1,
    refreshFromStart: Boolean = false,
    private val fetcher: suspend (page: Int, pageSize: Int, direction: LoadDirection) -> PageResult<T>
) : BasePagingSource<T>(startPage, refreshFromStart) {
    override suspend fun fetchBidirectional(
        page: Int,
        pageSize: Int,
        direction: LoadDirection
    ): PageResult<T> = fetcher(page, pageSize, direction)
}
