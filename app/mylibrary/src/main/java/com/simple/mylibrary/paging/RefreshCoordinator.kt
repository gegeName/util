package com.simple.mylibrary.paging
/**
 * 头部接口 + 分页两个数据源的下拉刷新协调器：等两边都 done 才 finishRefresh。
 *
 * 不再绑定具体刷新框架，统一通过 [PagingRefreshAdapter] 抽象。
 */
internal class RefreshCoordinator(private val refresh: PagingRefreshAdapter?) {

    private var pagingFinished = false
    private var headerFinished = true
    private var success = true
    private var inFlight = false

    fun start(needHeader: Boolean) {
        inFlight = true
        pagingFinished = false
        headerFinished = !needHeader
        success = true
    }

    fun headerDone(success: Boolean = true) {
        if (!success) this.success = false
        headerFinished = true
        tryFinish()
    }

    fun pagingDone(success: Boolean) {
        if (!success) this.success = false
        pagingFinished = true
        tryFinish()
    }

    private fun tryFinish() {
        if (!inFlight) return
        if (pagingFinished && headerFinished) {
            inFlight = false
            refresh?.finishRefresh(success)
        }
    }
}
