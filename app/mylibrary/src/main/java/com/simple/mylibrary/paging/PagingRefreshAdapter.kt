package com.simple.mylibrary.paging

/**
 * 下拉刷新框架抽象层，让 [PagingHelper] 不绑死具体实现。
 *
 * 项目自带 [SmartRefreshAdapter]（SmartRefreshLayout）和 [SwipeRefreshAdapter]（androidx SwipeRefreshLayout）。
 * 业务也可自行实现该接口接入其它任意刷新框架（例如自定义 Header / 自家组件库等）。
 */
interface PagingRefreshAdapter {

    /** 注册下拉触发回调；具体框架自行处理事件分发 */
    fun setOnRefreshListener(listener: () -> Unit)

    /** 开关上拉加载更多。Paging 已自动接管加载更多，建议传 false */
    fun setLoadMoreEnabled(enabled: Boolean)

    /** 开关下拉刷新本身。PagingHelper 在上拉加载中会自动调它=false 做互斥 */
    fun setRefreshEnabled(enabled: Boolean)

    /** 主动触发下拉刷新动画，并回调已注册的 onRefreshListener */
    fun autoRefresh()

    /** 结束刷新动画 */
    fun finishRefresh(success: Boolean)
}
