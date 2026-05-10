package com.simple.mylibrary.paging

//import com.scwang.smart.refresh.layout.api.RefreshLayout

/**
 * 包装 SmartRefreshLayout 的下拉刷新适配器。
 *
 * SmartRefreshLayout 的 autoRefresh 自身会触发已注册的 OnRefreshListener，
 * 因此不需要在适配器内手动调 listener。
 */
/*class SmartRefreshAdapter(private val refreshLayout: RefreshLayout) : PagingRefreshAdapter {

    override fun setOnRefreshListener(listener: () -> Unit) {
        refreshLayout.setOnRefreshListener { listener() }
    }

    override fun setLoadMoreEnabled(enabled: Boolean) {
        refreshLayout.setEnableLoadMore(enabled)
    }

    override fun setRefreshEnabled(enabled: Boolean) {
        refreshLayout.setEnableRefresh(enabled)
    }

    override fun autoRefresh() {
        refreshLayout.autoRefresh()
    }

    override fun finishRefresh(success: Boolean) {
        refreshLayout.finishRefresh(success)
    }
}*/
