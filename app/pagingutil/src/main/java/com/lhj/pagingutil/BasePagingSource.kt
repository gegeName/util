package com.lhj.pagingutil

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * 简化版 PagingSource: 子类二选一覆写
 * - [fetch] (page, pageSize) → (data, hasMore): 经典单向(只向后翻页),最常用
 * - [fetchBidirectional] (page, pageSize, direction) → [PageResult]: 双向(支持 PREPEND 向前加载),聊天 / 时间轴等场景
 *
 * 两者关系:框架内部统一走 [fetchBidirectional];它的默认实现会委托给 [fetch],
 * 因此存量子类(仅覆写 fetch)零改动继续可用。
 *
 * @param startPage 起始页码,默认从 1 开始
 * @param refreshFromStart 下拉刷新后是否从 [startPage] 重新开始拉。
 *  - false(默认):保留用户滚动位置;用户在第 N 页刷新,新 source 从第 N 页开始拉,体验更连贯。
 *    副作用:如果 N+1 页接口不稳定,刷新后立刻可能触发 append 失败。
 *  - true:永远从首页开始拉;用户被滚回顶部。适合"完全重置列表"语义(如更换筛选条件后的刷新)。
 */
abstract class BasePagingSource<T : Any>(
    private val startPage: Int = 1,
    private val refreshFromStart: Boolean = false
) : PagingSource<Int, T>() {

    /** 本次 load 的方向,Paging 自动从 [LoadParams] 子类型推出来,业务无需关心 */
    enum class LoadDirection {
        /** 首屏 / 刷新;key 通常为 null,使用 [startPage] */
        REFRESH,

        /** 向后翻页(下一页);key = 上一页的 nextKey */
        APPEND,

        /** 向前翻页(上一页 / 加载历史);key = 上一页的 prevKey,仅当首屏返回 [PageResult.hasPrev]=true 才会触发 */
        PREPEND
    }

    /**
     * 双向版返回值。
     *
     * @property data 本页数据
     * @property hasMore "沿当前请求方向"是否还能继续:
     *  - REFRESH / APPEND 时:本页之后(下一页 / 更老 / 翻页方向)还能加载 → true
     *  - PREPEND 时:本页之前(更早历史 / 反翻页方向)还能继续向前加载 → true
     * @property hasPrev 仅 REFRESH 时有意义:首屏返回的位置之前是否还有历史。
     *  - true → prevKey 被设为 page-1,后续滚到顶部会触发 PREPEND
     *  - false(默认)→ prevKey=null,Paging 永不调用 PREPEND
     *  APPEND / PREPEND 时本字段被忽略。
     */
    data class PageResult<T>(
        val data: List<T>,
        val hasMore: Boolean,
        val hasPrev: Boolean = false
    )

    /**
     * 经典单向 fetch:只关心向后翻页(下一页是否还有)。
     *
     * 存量代码继续走这条路即可;无需感知 PREPEND / [PageResult] / [LoadDirection]。
     *
     * 默认实现抛错 —— 子类必须覆写本方法或 [fetchBidirectional] 其中之一。
     */
    protected open suspend fun fetch(page: Int, pageSize: Int): Pair<List<T>, Boolean> {
        error(
            "BasePagingSource 子类必须覆写 fetch(page, pageSize) " +
                    "或 fetchBidirectional(page, pageSize, direction) 二者之一"
        )
    }

    /**
     * 双向 fetch:支持 PREPEND,聊天 / 评论流加载历史等场景用。
     *
     * 默认实现委托给单向 [fetch],hasPrev 由 `page > startPage` 推断 ——
     * 因此只覆写 [fetch] 的存量子类得到的就是"forward-only,永不触发 PREPEND"的行为。
     *
     * 示例(典型聊天):
     * ```
     * override suspend fun fetchBidirectional(
     *     page: Int, pageSize: Int, direction: LoadDirection
     * ): PageResult<Msg> = when (direction) {
     *     REFRESH -> {
     *         // 首屏:拉最新一页,告诉 paging 还有更早历史
     *         val resp = api.latestMessages(pageSize)
     *         PageResult(data = resp.list, hasMore = false, hasPrev = resp.hasOlder)
     *     }
     *     PREPEND -> {
     *         // 用户滚到顶部,加载更早:本页 page 已经 = 前一页的 prevKey
     *         val resp = api.messagesByPage(page, pageSize)
     *         PageResult(data = resp.list, hasMore = resp.hasOlder)
     *     }
     *     APPEND -> {
     *         // 聊天里通常不走 APPEND(REFRESH 已经是最新),保险起见兜个底
     *         PageResult(data = emptyList(), hasMore = false)
     *     }
     * }
     * ```
     *
     * @param page 当前要拉的页码;REFRESH 时 = [startPage],其余方向 = 上次 nextKey/prevKey
     * @param pageSize Paging 期望的条数(= PagingConfig.pageSize)
     * @param direction 本次 load 的方向,见 [LoadDirection]
     */
    protected open suspend fun fetchBidirectional(
        page: Int,
        pageSize: Int,
        direction: LoadDirection
    ): PageResult<T> {
        val (data, hasMore) = fetch(page, pageSize)
        return PageResult(
            data = data,
            hasMore = hasMore,
            hasPrev = false
        )
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, T> {
        val direction = when (params) {
            is LoadParams.Refresh -> LoadDirection.REFRESH
            is LoadParams.Append -> LoadDirection.APPEND
            is LoadParams.Prepend -> LoadDirection.PREPEND
        }
        val page = params.key ?: startPage
        return try {
            val result = fetchBidirectional(page, params.loadSize, direction)
            val hasData = result.data.isNotEmpty()

            /**
             * prevKey / nextKey 分方向计算:
             *
             * - REFRESH:
             *     prevKey 由业务的 hasPrev 决定 → 控制"是否允许 PREPEND 触发"
             *     nextKey 由业务的 hasMore 决定 → 控制"是否允许 APPEND 触发"
             *
             * - APPEND(已经在向后翻):
             *     prevKey = page - 1,让 Paging 能在缓存淘汰场景下回填前一页
             *     nextKey 跟 hasMore 走
             *
             * - PREPEND(已经在向前翻):
             *     prevKey 跟 hasMore 走(hasMore 在 PREPEND 语义下 = "更早还有没有")
             *     nextKey = page + 1,让 Paging 能回填后一页
             */
            val prevKey: Int? = when (direction) {
                LoadDirection.REFRESH -> if (result.hasPrev) page - 1 else null
                LoadDirection.APPEND -> page - 1
                LoadDirection.PREPEND -> if (result.hasMore && hasData) page - 1 else null
            }
            val nextKey: Int? = when (direction) {
                LoadDirection.REFRESH,
                LoadDirection.APPEND -> if (result.hasMore && hasData) page + 1 else null
                LoadDirection.PREPEND -> page + 1
            }

            LoadResult.Page(result.data, prevKey, nextKey)
        } catch (e: Exception) {
            LoadResult.Error(e)
        }
    }

    /**
     * 决定下拉刷新(refresh)后新 PagingSource 首屏从哪一页开始拉。
     *
     * - [refreshFromStart]=true → 返回 null,由 Pager 用 [startPage] 重拉首页(用户被滚回顶部)
     * - [refreshFromStart]=false(默认)→ 取用户最近"锚定"的那一页:
     *   优先用 closest 页的 prevKey+1(向前回算);prevKey=null(首屏)时退到 nextKey-1。
     *   双向场景下两条都可能为空,所以双重 fallback。
     */
    override fun getRefreshKey(state: PagingState<Int, T>): Int? {
        if (refreshFromStart) return null
        return state.anchorPosition?.let { anchor ->
            val closest = state.closestPageToPosition(anchor)
            closest?.prevKey?.plus(1) ?: closest?.nextKey?.minus(1)
        }
    }
}
