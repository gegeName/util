package com.simple.mylibrary.paging

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * 简化版 PagingSource：子类只需实现 [fetch] 返回（一页数据 + 是否还有下一页）。
 *
 * @param startPage 起始页码，默认从 1 开始
 * @param refreshFromStart 下拉刷新后是否从 [startPage] 重新开始拉。
 *  - false（默认）：保留用户滚动位置；用户在第 N 页刷新，新 source 从第 N 页开始拉，体验更连贯。
 *    副作用：如果 N+1 页接口不稳定，刷新后立刻可能触发 append 失败。
 *  - true：永远从首页开始拉；用户被滚回顶部。适合"完全重置列表"语义（如更换筛选条件后的刷新）。
 */
abstract class BasePagingSource<T : Any>(
    private val startPage: Int = 1,
    private val refreshFromStart: Boolean = false
) : PagingSource<Int, T>() {

    /** 子类实现：调用接口拉取第 [page] 页，返回 (本页数据, 是否还有下一页) */
    protected abstract suspend fun fetch(page: Int, pageSize: Int): Pair<List<T>, Boolean>

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val page = params.key ?: startPage
        return try {
            val (list, hasMore) = fetch(page, params.loadSize)
            LoadResult.Page(
                data = list,
                prevKey = if (page == startPage) null else page - 1,
                nextKey = if (hasMore && list.isNotEmpty()) page + 1 else null
            )
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * 决定下拉刷新（[androidx.paging.PagingDataAdapter.refresh]）后，新创建的 PagingSource
     * 首屏从哪一页开始拉。
     *
     * - 构造参数 [refreshFromStart]=true → 直接返回 null，由 Pager 用 [startPage] 重拉首页（用户被滚回顶部）
     * - [refreshFromStart]=false（默认）→ 取用户最近"锚定"的那一页（[PagingState.anchorPosition] 所在 page）的
     *   `prevKey + 1`，也就是当前可见区域所在页码。这是 Paging 官方推荐的"保留滚动位置"做法。
     *
     * 副作用提醒（仅当保留位置时）：
     * - 刷新后位置保留，会立刻命中 prefetchDistance，导致接着拉下一页（例如锚在第 2 页底部 →
     *   刷新后从第 2 页开始 → 紧接着触发第 3 页加载）。如果第 3 页接口不稳定，就会看到
     *   "刚下拉刷新就出现 append 失败 footer"的现象。要避免就把 [refreshFromStart] 设为 true，
     *   或子类自行覆写本方法。
     */
    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        if (refreshFromStart) return null
        return state.anchorPosition?.let { state.closestPageToPosition(it)?.prevKey?.plus(1) }
    }
}
