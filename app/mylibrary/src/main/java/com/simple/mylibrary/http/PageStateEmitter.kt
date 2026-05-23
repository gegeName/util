package com.simple.mylibrary.http

import com.lhj.statelayout.StateLayout
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 页面级状态：用于驱动 [StateLayout] 的 4 种视图。
 *
 * 与 [LoadingEmitter] / [launchApiWithLoading] 形成对偶：
 * - [LoadingEmitter] 驱动"全屏弹窗式 loading"；
 * - [PageStateEmitter] 驱动"页面区域内的 loading/empty/error/success 切换"。
 *
 * 两者可同页共存：一些操作要弹窗（如提交支付），一些操作要页面状态（如首屏拉列表）。
 */
sealed interface PageState {
    object Loading : PageState
    object Empty : PageState
    object Success : PageState
    data class Error(val cause: Throwable? = null) : PageState
}

/**
 * 页面状态发射器。ViewModel 持有一个，UI 订阅 [state] 驱动 [StateLayout]。
 *
 * 行为约定：
 * - [launchApiWithLoadingState] 进入时自动 emit [PageState.Loading]；
 * - block 抛异常 → emit [PageState.Error]，错误仍按 [launchApi] 规则分发到 [ApiErrorHandler]
 *   或被业务的 onApiError 消费；
 * - block 正常结束 → 若状态仍是 Loading（业务未主动改），自动 emit [PageState.Success]；
 *   业务可在 block 内根据数据主动调用 [showEmpty] / [showError] 等覆盖默认收尾。
 */
class PageStateEmitter(initial: PageState = PageState.Success) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PageState> = _state.asStateFlow()

    fun set(state: PageState) {
        _state.value = state
    }

    fun showLoading() = set(PageState.Loading)
    fun showEmpty() = set(PageState.Empty)
    fun showSuccess() = set(PageState.Success)
    fun showError(cause: Throwable? = null) = set(PageState.Error(cause))

    /** 当前是否处于 Loading（[launchApiWithLoadingState] 用于自动收尾时判断是否需置 Success）。 */
    val isLoading: Boolean get() = _state.value is PageState.Loading
}

/**
 * ViewModel 实现此接口即声明对页面状态的支持。可与 [LoadingOwner] 同时实现：
 * 一个 VM 同时拥有 loading 弹窗与 StateLayout 状态两条独立通道。
 */
interface PageStateOwner {
    val pageState: PageStateEmitter
}

/**
 * 启动 API 协程并把生命周期映射到 [PageStateEmitter]：
 * - 启动前：emit [PageState.Loading]
 * - 异常：emit [PageState.Error]，错误仍按 [launchApi] 规则分发（onApiError 优先，其余走 [ApiErrorHandler]）
 * - 正常结束：若状态仍是 Loading（业务未主动 emit），自动 emit [PageState.Success]
 *
 * 业务可在 block 内根据数据决定最终状态：
 * ```
 * fun load() = viewModelScope.launchApiWithLoadingState(pageState) {
 *     val list = ApiService.getList()
 *     _list.value = list
 *     if (list.isEmpty()) pageState.showEmpty()
 *     // 否则什么都不做，函数尾部自动 Success
 * }
 * ```
 *
 * 业务亦可主动判定 Error 而不抛异常：
 * ```
 * fun load() = viewModelScope.launchApiWithLoadingState(pageState) {
 *     val resp = ApiService.getDetail(id)
 *     if (resp.invalid) {
 *         pageState.showError()
 *         return@launchApiWithLoadingState
 *     }
 *     _detail.value = resp
 * }
 * ```
 *
 * 与 [launchApiWithLoading] 区别：
 * - 本函数走"页面状态"，不弹 loading dialog；
 * - [launchApiWithLoading] 走"loading 弹窗"，不影响页面 StateLayout。
 *
 * 同一请求二者**不要叠加**——会出现弹窗 + 页面 loading 同时显示。一个请求只选其一。
 */
fun CoroutineScope.launchApiWithLoadingState(
    pageState: PageStateEmitter,
    onApiError: ((ApiException) -> Boolean)? = null,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    pageState.showLoading()
    val handler = CoroutineExceptionHandler { _, t ->
        pageState.showError(t)
        val consumed = (t as? ApiException)?.let { onApiError?.invoke(it) } == true
        if (!consumed) ApiErrorHandler.handle(t)
    }
    return launch(context = handler) {
        block()
        if (pageState.isLoading) pageState.showSuccess()
    }
}

/**
 * 把 [PageStateEmitter] 绑到 [StateLayout]：UI 层一行接通。
 *
 * - Activity：传 `lifecycleScope`
 * - Fragment：传 `viewLifecycleOwner.lifecycleScope`，视图重建时连同协程一起销毁，
 *   避免操作已 detach 的 view
 *
 * ```
 * private val viewModel by getViewModel<MyVM>()
 * private lateinit var stateLayout: StateLayout
 *
 * override fun initView() {
 *     stateLayout = installStateLayout(topMarginPx = 48.dp, onRetry = { viewModel.load() })
 *     stateLayout.bindPageState(viewModel.pageState, lifecycleScope)
 * }
 * ```
 */
fun StateLayout.bindPageState(
    emitter: PageStateEmitter,
    scope: CoroutineScope,
): Job = scope.launch {
    emitter.state.collect { s ->
        when (s) {
            PageState.Loading -> showPageLoading()
            PageState.Empty -> showEmpty()
            PageState.Success -> showSuccess()
            is PageState.Error -> showError()
        }
    }
}
