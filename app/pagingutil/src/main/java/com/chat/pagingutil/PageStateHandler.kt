package com.chat.pagingutil

/**
 * 全屏占位状态处理器抽象。
 *
 * 业务可以让自家的空页 / 错误页 / 加载页 View 实现此接口，然后通过
 * [PagingHelper.pageState] 注入；helper 在 LoadState 切换时只调下面这 4 个方法，
 * 不关心你的 View 长什么样。
 *
 * 提供的默认实现 [PagingStateLayout] 是 4 状态切换的 FrameLayout，开箱即用，业务也可不用。
 *
 * 4 个方法的语义：
 * - [showLoading]：列表为空 + refresh = Loading
 * - [showEmpty(text)]：列表为空 + refresh = NotLoading + endOfPaginationReached=true
 * - [showError(throwable, text)]：列表为空 + refresh = Error
 * - [showContent]：有数据，应隐藏占位
 *
 * [bindRetry] 由 helper 在 start() 时调一次，把"重试"按钮的回调注入进来；
 * 业务需要在自家 View 的"点击重试"控件上调用该回调即可触发 [androidx.paging.PagingDataAdapter.retry]。
 *
 * 接入案例（如 LoadSir）
 * ```
 * class LoadSirAdapter(private val service: LoadService<Any>) : PageStateHandler {
 *     override fun bindRetry(retry: () -> Unit) {
 *         service.setCallBack(ErrorCallback::class.java) { _, view ->
 *             view.findViewById<View>(R.id.btn_retry).setOnClickListener { retry() }
 *         }
 *     }
 *     override fun showLoading() = service.showCallback(LoadingCallback::class.java)
 *     override fun showEmpty(text: CharSequence?) {
 *         service.showCallback(EmptyCallback::class.java)
 *         // 用 setCallBack 把 text 注入对应 view
 *     }
 *     override fun showError(throwable: Throwable?, text: CharSequence?) {
 *         service.showCallback(ErrorCallback::class.java)
 *     }
 *     override fun showContent() = service.showSuccess()
 * }
 * ```
 */
interface PageStateHandler {

    /** helper 注入的 retry 回调；业务在自家 View 的"点击重试"上调它 */
    fun bindRetry(retry: () -> Unit)

    /** 显示加载中 */
    fun showLoading()

    /** 显示空数据；[text] 由 [PagingHelper.emptyText] 提供，没配则为 null，业务 View 决定回退文案 */
    fun showEmpty(text: CharSequence?)

    /** 显示加载失败；[throwable] 是首屏失败时的异常，[text] 由 [PagingHelper.errorText] 解析 */
    fun showError(throwable: Throwable?, text: CharSequence?)

    /** 隐藏占位，露出列表 */
    fun showContent()
}
