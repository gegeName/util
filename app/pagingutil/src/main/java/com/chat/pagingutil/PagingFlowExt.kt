package com.chat.pagingutil

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
import com.chat.pagingutil.BasePagingSource.LoadDirection
import com.chat.pagingutil.BasePagingSource.PageResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * ViewModel 内一行造出 cachedIn(viewModelScope) 后的 PagingFlow，免写样板。
 *
 * 用法：
 * ```
 * class UserListVM : ViewModel() {
 *     val pagingFlow = pagingFlowOf { UserPagingSource() }
 * }
 * ```
 *
 * 业务想覆盖默认参数，按需传：
 * ```
 * val pagingFlow = pagingFlowOf(pageSize = 30, prefetchDistance = 10) {
 *     UserPagingSource(query = currentQuery)
 * }
 * ```
 *
 * @param T item 类型
 * @param pageSize 每页大小，默认 20
 * @param initialLoadSize 首屏加载条数，默认 `pageSize * 3`（与 AndroidX Paging 默认一致）。下拉刷新会重新走首屏加载，
 *                        若设得过小（比如 = pageSize），用户已 prefetch 过 N 页时刷新瞬间 itemCount 会大幅缩水，
 *                        造成可见的白屏闪烁；想首屏只取一页可显式传 `pageSize`
 * @param prefetchDistance 触发下一页的预取距离，默认 [pageSize]
 * @param enablePlaceholders 是否在未加载位置展示占位 null，默认 false（绝大多数业务都是 false）
 * @param maxSize 内存中最多保留多少条；默认 [PagingConfig.MAX_SIZE_UNBOUNDED]，即不淘汰
 * @param sourceFactory 创建 PagingSource 的工厂，每次 refresh / invalidate 都会调一次
 */
fun <T : Any> ViewModel.pagingFlowOf(
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize * 3,
    prefetchDistance: Int = pageSize,
    enablePlaceholders: Boolean = false,
    maxSize: Int = PagingConfig.MAX_SIZE_UNBOUNDED,
    sourceFactory: () -> PagingSource<Int, T>
): Flow<PagingData<T>> = Pager(
    config = PagingConfig(
        pageSize = pageSize,
        initialLoadSize = initialLoadSize,
        prefetchDistance = prefetchDistance,
        enablePlaceholders = enablePlaceholders,
        maxSize = maxSize
    ),
    pagingSourceFactory = sourceFactory
).flow.cachedIn(viewModelScope)

/**
 * 同 [pagingFlowOf]，但允许业务自己传 [CoroutineScope]（不在 ViewModel 内时用）。
 *
 * @param scope 用来 cachedIn 的协程作用域，注意应当随宿主销毁
 */
fun <T : Any> pagingFlowOf(
    scope: CoroutineScope,
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize * 3,
    prefetchDistance: Int = pageSize,
    enablePlaceholders: Boolean = false,
    maxSize: Int = PagingConfig.MAX_SIZE_UNBOUNDED,
    sourceFactory: () -> PagingSource<Int, T>
): Flow<PagingData<T>> = Pager(
    config = PagingConfig(
        pageSize = pageSize,
        initialLoadSize = initialLoadSize,
        prefetchDistance = prefetchDistance,
        enablePlaceholders = enablePlaceholders,
        maxSize = maxSize
    ),
    pagingSourceFactory = sourceFactory
).flow.cachedIn(scope)

/**
 * 极简重载：业务每个接口不再单独建 PagingSource 子类，传 lambda 即可。
 *
 * 用法（ViewModel 内）：
 * ```
 * val pagingFlow = pagingFlowOf { page, size ->
 *     val resp = api.getUsers(page, size)
 *     resp.list to resp.hasMore
 * }
 * ```
 *
 * 多接口共用一个 ViewModel：
 * ```
 * val userFlow   = pagingFlowOf(pageSize = 30) { p, s -> api.users(p, s).run { list to hasMore } }
 * val orderFlow  = pagingFlowOf            { p, s -> api.orders(p, s).run { list to hasMore } }
 * val searchFlow = pagingFlowOf(pageSize = 50) { p, s -> api.search(query, p, s).run { list to hasMore } }
 * ```
 *
 * 内部用 [LambdaPagingSource] 包了一层；prevKey/nextKey/getRefreshKey 全部沿用 [BasePagingSource] 行为。
 *
 * @param T item 类型
 * @param startPage 起始页码，默认 1
 * @param fetcher (page, pageSize) -> (本页数据, 是否还有下一页)；与 [BasePagingSource.fetch] 同义
 */
fun <T : Any> ViewModel.pagingFlowOf(
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize * 3,
    prefetchDistance: Int = pageSize,
    enablePlaceholders: Boolean = false,
    maxSize: Int = PagingConfig.MAX_SIZE_UNBOUNDED,
    startPage: Int = 1,
    refreshFromStart: Boolean = false,
    fetcher: suspend (page: Int, pageSize: Int) -> Pair<List<T>, Boolean>
): Flow<PagingData<T>> = pagingFlowOf(
    pageSize = pageSize,
    initialLoadSize = initialLoadSize,
    prefetchDistance = prefetchDistance,
    enablePlaceholders = enablePlaceholders,
    maxSize = maxSize,
    sourceFactory = { LambdaPagingSource(startPage, refreshFromStart, fetcher) }
)

/**
 * [pagingFlowOf] lambda 版的非 ViewModel 重载，业务自己传 [CoroutineScope]。
 */
fun <T : Any> pagingFlowOf(
    scope: CoroutineScope,
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize * 3,
    prefetchDistance: Int = pageSize,
    enablePlaceholders: Boolean = false,
    maxSize: Int = PagingConfig.MAX_SIZE_UNBOUNDED,
    startPage: Int = 1,
    refreshFromStart: Boolean = false,
    fetcher: suspend (page: Int, pageSize: Int) -> Pair<List<T>, Boolean>
): Flow<PagingData<T>> = pagingFlowOf(
    scope = scope,
    pageSize = pageSize,
    initialLoadSize = initialLoadSize,
    prefetchDistance = prefetchDistance,
    enablePlaceholders = enablePlaceholders,
    maxSize = maxSize,
    sourceFactory = { LambdaPagingSource(startPage, refreshFromStart, fetcher) }
)

/**
 * 双向 lambda 版:聊天 / 时间轴等需要"加载更早历史"(PREPEND)的场景。
 *
 * 与单向 [pagingFlowOf] 的区别:fetcher 多两个参数 `direction` 与 `prependIndex`,返回 [PageResult]
 * (data, hasMore, hasPrev) 而不是 `Pair<List, Boolean>`。`hasPrev=true` 开启 PREPEND 触发,
 * `hasMore` 在不同方向下含义不同(REFRESH/APPEND 向后看,PREPEND 向前看)。
 *
 * Kotlin 按 lambda 形参个数区分单向(2 个)/双向(4 个)重载,业务侧调用时直接写就行,
 * 不会和单向版冲突。
 *
 * 典型聊天用法(ViewModel 内):
 * ```
 * class ChatVM(private val api: ChatApi, private val roomId: String) : ViewModel() {
 *     val pagingFlow = pagingFlowOf { page, size, direction, prependIndex ->
 *         when (direction) {
 *              //首屏拉最新一页,告诉 Paging 还有更早历史(开启 PREPEND)
 *             LoadDirection.REFRESH -> api.latest(roomId, size).run {
 *                 PageResult(data = list,
 *                 hasMore = false, //最新页之后没东西
 *                 hasPrev = hasOlder// ← 关键:开启 PREPEND 的开关
 *                 )
 *             }
 *             // 滚到顶,加载更早:prependIndex = 1, 2, 3 ... 业务直接当 offset 用
 *             LoadDirection.PREPEND -> api.olderBatch(prependIndex, size).run {
 *                 PageResult(data = list, hasMore = hasOlder)
 *             }
 *             // 聊天里 REFRESH 已经在最新,通常走不到 APPEND;留个兜底
 *             LoadDirection.APPEND -> PageResult(emptyList(), hasMore = false)
 *         }
 *     }
 * }
 * ```
 *
 * RecyclerView 配 `LinearLayoutManager(reverseLayout = true, stackFromEnd = true)`,
 * 用户向上滚动会自动触发 PREPEND 加载更早消息。
 *
 * @param fetcher (page, pageSize, direction, prependIndex) -> [PageResult],
 *                签名 = [BasePagingSource.fetchBidirectional]。prependIndex 在 PREPEND 时
 *                从 1 递增,REFRESH/APPEND 时为 0;业务通常用 prependIndex 而忽略 page。
 */
fun <T : Any> ViewModel.pagingFlowOf(
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize * 3,
    prefetchDistance: Int = pageSize,
    enablePlaceholders: Boolean = false,
    maxSize: Int = PagingConfig.MAX_SIZE_UNBOUNDED,
    startPage: Int = 1,
    refreshFromStart: Boolean = false,
    fetcher: suspend (page: Int, pageSize: Int, direction: LoadDirection, prependIndex: Int) -> PageResult<T>
): Flow<PagingData<T>> = pagingFlowOf(
    pageSize = pageSize,
    initialLoadSize = initialLoadSize,
    prefetchDistance = prefetchDistance,
    enablePlaceholders = enablePlaceholders,
    maxSize = maxSize,
    sourceFactory = { BidirectionalLambdaPagingSource(startPage, refreshFromStart, fetcher) }
)

/** 双向 lambda 版的非 ViewModel 重载,业务自己传 [CoroutineScope]。 */
fun <T : Any> pagingFlowOf(
    scope: CoroutineScope,
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize * 3,
    prefetchDistance: Int = pageSize,
    enablePlaceholders: Boolean = false,
    maxSize: Int = PagingConfig.MAX_SIZE_UNBOUNDED,
    startPage: Int = 1,
    refreshFromStart: Boolean = false,
    fetcher: suspend (page: Int, pageSize: Int, direction: LoadDirection, prependIndex: Int) -> PageResult<T>
): Flow<PagingData<T>> = pagingFlowOf(
    scope = scope,
    pageSize = pageSize,
    initialLoadSize = initialLoadSize,
    prefetchDistance = prefetchDistance,
    enablePlaceholders = enablePlaceholders,
    maxSize = maxSize,
    sourceFactory = { BidirectionalLambdaPagingSource(startPage, refreshFromStart, fetcher) }
)
