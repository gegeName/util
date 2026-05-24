package com.chat.pagingutil

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 包装 androidx SwipeRefreshLayout 的下拉刷新适配器。
 *
 * SwipeRefreshLayout 没有自带 autoRefresh，[autoRefresh] 这里手动置 isRefreshing=true 并回调 listener。
 */
class SwipeRefreshAdapter(private val srl: SwipeRefreshLayout) : PagingRefreshAdapter {

    private var listener: (() -> Unit)? = null

    override fun setOnRefreshListener(listener: () -> Unit) {
        this.listener = listener
        srl.setOnRefreshListener { listener() }
    }

    override fun setLoadMoreEnabled(enabled: Boolean) {
    }

    override fun setRefreshEnabled(enabled: Boolean) {
        srl.isEnabled = enabled
    }

    override fun autoRefresh() {
        srl.isRefreshing = true
        listener?.invoke()
    }

    override fun finishRefresh(success: Boolean) {
        srl.isRefreshing = false
    }
}
