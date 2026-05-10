package com.simple.mylibrary.paging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.cachedIn
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
 * @param initialLoadSize 首屏加载条数，默认与 [pageSize] 相同（即只取一页）；想首屏多塞两页可填 `pageSize * 2`
 * @param prefetchDistance 触发下一页的预取距离，默认 [pageSize]
 * @param enablePlaceholders 是否在未加载位置展示占位 null，默认 false（绝大多数业务都是 false）
 * @param maxSize 内存中最多保留多少条；默认 [PagingConfig.MAX_SIZE_UNBOUNDED]，即不淘汰
 * @param sourceFactory 创建 PagingSource 的工厂，每次 refresh / invalidate 都会调一次
 */
fun <T : Any> ViewModel.pagingFlowOf(
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize,
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
    initialLoadSize: Int = pageSize,
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
    initialLoadSize: Int = pageSize,
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
    maxSize = maxSize
) { LambdaPagingSource(startPage, refreshFromStart, fetcher) }

/**
 * [pagingFlowOf] lambda 版的非 ViewModel 重载，业务自己传 [CoroutineScope]。
 */
fun <T : Any> pagingFlowOf(
    scope: CoroutineScope,
    pageSize: Int = 20,
    initialLoadSize: Int = pageSize,
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
    maxSize = maxSize
) { LambdaPagingSource(startPage, refreshFromStart, fetcher) }
