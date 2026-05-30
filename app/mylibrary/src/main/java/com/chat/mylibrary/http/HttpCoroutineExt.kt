package com.chat.mylibrary.http

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.chat.statelayout.StateLayout
import com.chat.mylibrary.base.BaseViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 默认全局 handler：任何未被业务消费的异常走这里。可复用单例。
 */
val DefaultApiHandler: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, t -> ApiErrorHandler.handle(t) }

/**
 * 带业务自处理能力的 handler 工厂。
 *
 * @param onApiError 返回 true 表示业务已消费此异常，全局不再处理；
 *                   返回 false / 不传 → 回退到 [ApiErrorHandler] 统一处理。
 *                   仅对 [ApiException] 生效；其它异常（网络、HTTP 等）一律走全局。
 */
private fun apiHandler(
    onApiError: ((ApiException) -> Boolean)?
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
    val consumed = (t as? ApiException)?.let { onApiError?.invoke(it) } == true
    if (!consumed) ApiErrorHandler.handle(t)
}

/**
 * 启动一个带统一异常拦截的协程。无 try/catch，仅靠 [CoroutineExceptionHandler] 分发。
 *
 * 要求：scope 必须使用 SupervisorJob（`lifecycleScope` / `viewModelScope` 均满足）。
 *
 * 配合 [HttpResult.unwrap] 使用：ApiService 接口直接返回
 * `HttpResult<T>`，HttpHelper 调用 `.unwrap()` 在 code 非成功时抛 [ApiException]，
 * 由本函数挂的 handler 路由到 [ApiErrorHandler]。
 *
 * ```
 * // ApiService
 * @POST("{url}")
 * suspend fun getUser(@Path("url") url: String): HttpResult<UserBean>
 *
 * // HttpHelper
 * suspend fun getUser() = httpService.getUser(url).unwrap()
 * ```
 *
 * 示例：
 * ```
 * // 全部走全局
 * lifecycleScope.launchApi {
 *     val token = ApiService.getOssToken()
 *     binding.tv.text = token.token
 * }
 *
 * // 业务拦截特定 code
 * lifecycleScope.launchApi(
 *     onApiError = { e ->
 *         when (e.errorCode) {
 *             "10006" -> { showPwdDialog(); true }
 *             else -> false
 *         }
 *     }
 * ) {
 *     ApiService.joinRoom(id)
 * }
 *
 * 嵌套 launch 需用 supervisorScope
 * 如果 block 里有 launch { ... } 子协程并发请求，子协程异常会透传到父 Job 触发取消。想互不影响就用：
 * launchApi {
 *     supervisorScope {
 *         launch { ApiService.getA() }
 *         launch { ApiService.getB() }
 *     }
 * }
 *
 * ```
 */
fun CoroutineScope.launchApi(
    onApiError: ((ApiException) -> Boolean)? = null,
    block: suspend CoroutineScope.() -> Unit
): Job = launch(
    context = if (onApiError == null) DefaultApiHandler else apiHandler(onApiError),
    block = block
)

/* ─────────────────────────── loading 支持（MVVM 友好） ────────────────────────────
 * ViewModel 持有 [LoadingEmitter] 并暴露 [LoadingEmitter.state]；
 * UI 层订阅 state 驱动自己的 loading UI（[LoadingDialog] 或其他）。
 * ViewModel 不持有任何 Android UI 引用，无泄漏风险。
 *
 * 典型写法：
 * ```
 * class MyVM : ViewModel() {
 *     val loading = LoadingEmitter()
 *
 *     fun load() = viewModelScope.launchApiWithLoading(loading) {
 *         val data = ApiService.getXxx()
 *         // ...更新 state/flow
 *     }
 * }
 *
 * // Activity/Fragment
 * override fun initView() {
 *     bindLoadingDialog(viewModel.loading, lifecycleScope)   // 一行接通
 *     // Fragment: requireActivity().bindLoadingDialog(viewModel.loading, viewLifecycleOwner.lifecycleScope)
 * }
 * ```
 */

/**
 * UI 层 loading 事件。
 * - [Show] 携带 `cancelable`（UI 是否响应返回键/点击外部）与 `onCancel`（取消时请务必调用，用于联动取消协程）。
 * - [Hide] 关闭 loading。
 */
sealed interface LoadingEvent {
    data class Show(
        val cancelable: Boolean = true,
        val onCancel: () -> Unit = {}
    ) : LoadingEvent

    object Hide : LoadingEvent
}

/**
 * loading 发射器（并发计数 + 边沿触发版）。ViewModel 持有一个实例即可，UI 层订阅 [state]。
 *
 * 行为：
 * - 任一 [track] 的 Job 进入即 emit [LoadingEvent.Show]；所有被追踪的 Job 完成后才 emit [LoadingEvent.Hide]。
 * - 只要存在一个 `cancelable=false` 的 Job，整体 Show 就不可取消；该 Job 完成后若只剩可取消的 Job，状态自动恢复为可取消。
 * - 边沿触发：中途 track 进来的同 `cancelable` 的 Job 不会让 UI 收到重复 Show（依赖 [StateFlow] 的 equals 去重）。
 *   `cancelable` 标志由 false → true 或反之时才会 re-emit，UI 据此刷新 dialog 的 setCancelable。
 * - [LoadingEvent.Show.onCancel] 对整个 emitter 生命周期保持同一引用，触发时动态读取当前在途 Job 并一次性取消。
 * - 线程安全：内部用 `synchronized` 锁保护 `activeJobs` 与计数器。
 *
 * 非协程异步（OSS 回调等）想联动 loading，请用 `suspendCancellableCoroutine` 包成挂起函数后走 [launchApiWithLoading]，
 * 不要自己 emit 状态（会绕开计数导致混乱）。
 */
class LoadingEmitter {
    private val _state = MutableStateFlow<LoadingEvent>(LoadingEvent.Hide)
    val state: StateFlow<LoadingEvent> = _state.asStateFlow()

    private val lock = Any()
    private val activeJobs = LinkedHashSet<Job>()
    private var nonCancelableCount = 0

    /**
     * 稳定的取消入口：UI 拿到的 `onCancel` 始终是此函数引用，保证 [LoadingEvent.Show] 对象只因 `cancelable` 变化而变化，
     * 从而让 [StateFlow] 的 equals 去重对"重复 Show"生效。内部动态读取最新在途 Job。
     */
    private val cancelAll: () -> Unit = {
        val snapshot = synchronized(lock) { activeJobs.toList() }
        snapshot.forEach { it.cancel() }
    }

    /**
     * 纳入计数。Job 完成（正常/异常/取消）后自动从计数中移除；清零时 emit Hide。
     * 同一 Job 重复 track 会被忽略。通常由 [launchApiWithLoading] 调用，业务不需要直接调。
     */
    fun track(job: Job, cancelable: Boolean = true) {
        synchronized(lock) {
            if (!activeJobs.add(job)) return
            if (!cancelable) nonCancelableCount++
            emitShowLocked()
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (activeJobs.remove(job) && !cancelable) nonCancelableCount--
                if (activeJobs.isEmpty()) {
                    _state.value = LoadingEvent.Hide
                } else {
                    emitShowLocked()
                }
            }
        }
    }

    private fun emitShowLocked() {
        _state.value = LoadingEvent.Show(
            cancelable = nonCancelableCount == 0,
            onCancel = cancelAll
        )
    }
}

/**
 * 在当前 scope（推荐 `viewModelScope`）启动 API 协程，并自动驱动 [LoadingEmitter]：
 * - [LoadingEmitter.track] 纳入计数，UI 仅在"空闲 → 有任务"边沿收到 Show，所有任务完成后收到 Hide。
 * - UI 触发 [LoadingEvent.Show.onCancel] 会取消 emitter 当前所有在途协程（不是只取消这一个）。
 * - 协程完成 / 取消 / 异常时自动从计数中移除。
 * - 异常分发与 [launchApi] 一致，未消费的异常走 [ApiErrorHandler]。
 *
 * 单请求：
 * ```
 * fun joinRoom(id: String) = viewModelScope.launchApiWithLoading(
 *     loading = loading,
 *     onApiError = { e -> (e.errorCode == "10006").also { if (it) _needPwd.tryEmit(id) } }
 * ) {
 *     val info = ApiService.joinRoom(id)
 *     _roomInfo.value = info
 * }
 *
 * // 关键写操作禁止用户中途取消
 * viewModelScope.launchApiWithLoading(loading, cancelable = false) {
 *     ApiService.pay(orderId)
 * }
 * ```
 *
 * 并发（共享同一 emitter）：
 * ```
 * // 方式 1：多次 launch，各自独立。UI 只显示一次 loading，全部完成后才消失。
 * fun refreshAll() {
 *     viewModelScope.launchApiWithLoading(loading) { _userInfo.value = ApiService.getUser() }
 *     viewModelScope.launchApiWithLoading(loading) { _balance.value  = ApiService.getBalance() }
 *     viewModelScope.launchApiWithLoading(loading) { _banners.value  = ApiService.getBanners() }
 * }
 *
 * // 方式 2：并发里混入一个不可取消请求。只要 pay 还在跑，loading 就不可取消；
 * // pay 完成后若还剩可取消请求，UI 自动恢复为可取消。
 * fun checkout() {
 *     viewModelScope.launchApiWithLoading(loading) { ApiService.refreshCart() }
 *     viewModelScope.launchApiWithLoading(loading, cancelable = false) { ApiService.pay(orderId) }
 * }
 *
 * // 方式 3：block 内并发子协程。结果需合并时用此法；注意用 supervisorScope 避免一个失败全挂。
 * fun loadDetail(id: String) = viewModelScope.launchApiWithLoading(loading) {
 *     supervisorScope {
 *         val a = async { ApiService.getProfile(id) }
 *         val b = async { ApiService.getFollowers(id) }
 *         _detail.value = Detail(a.await(), b.await())
 *     }
 * }
 *
 * // 用户点返回键取消时：方式 1/2 会取消所有在途协程；方式 3 只取消外层 Job（supervisorScope 会将取消传给 async）。
 * ```
 */
fun CoroutineScope.launchApiWithLoading(
    loading: LoadingEmitter,
    cancelable: Boolean = true,
    onApiError: ((ApiException) -> Boolean)? = null,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val job = launch(
        context = if (onApiError == null) DefaultApiHandler else apiHandler(onApiError),
        block = block
    )
    loading.track(job, cancelable)
    return job
}

/**
 * 方式一，使用必须要ViewModel实现[LoadingOwner]接口，才能用委托[autoLoadingViewModel]
 * UI 层工具（可选）：把 [LoadingEmitter] 绑到 [LoadingDialog]。
 *
 * 在 Activity/Fragment 初始化时调用一次。scope 建议传 `lifecycleScope`（随页面生命周期自动取消）。
 * 不想用 [LoadingDialog] 的业务可自行 collect [LoadingEmitter.state]，无需调用本方法。
 *
 * 更推荐直接用 [autoLoadingViewModel] 委托：在声明 ViewModel 时一行完成绑定，无需手写此方法。
 *
 * 方式二，每次需要在Activity/fragment中调用bindLoadingDialog(viewModel.loading, lifecycleScope),无需使用[autoLoadingViewModel],
 * 使用正常方式获取ViewModel
 * ```
 * // Activity
 * bindLoadingDialog(viewModel.loading, lifecycleScope)
 *
 * // Fragment（注意用 fragment.lifecycleScope，不是 viewLifecycleOwner.lifecycleScope，
 * // 否则视图重建场景下 lazy 绑定会失效）
 * requireActivity().bindLoadingDialog(viewModel.loading, lifecycleScope)
 * ```
 */
interface LoadingController {
    fun show(activity: AppCompatActivity, cancelable: Boolean, onCancel: () -> Unit)
    fun dismiss(activity: AppCompatActivity)

    companion object {
        var default: LoadingController = DefaultLoadingController
    }
}

object DefaultLoadingController : LoadingController {
    override fun show(activity: AppCompatActivity, cancelable: Boolean, onCancel: () -> Unit) {
        LoadingDialog.showLoadingDialog(activity, cancelable, onCancel)
    }

    override fun dismiss(activity: AppCompatActivity) {
        LoadingDialog.dismissLoading(activity)
    }
}

fun AppCompatActivity.bindLoadingDialog(
    emitter: LoadingEmitter,
    scope: CoroutineScope,
    controller: LoadingController = LoadingController.default,
): Job {
    return scope.launch {
        try {
            emitter.state.collect { event ->
                when (event) {
                    is LoadingEvent.Show -> {
                        if (isDestroyed || isFinishing) return@collect
                        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collect
                        runCatching { controller.show(this@bindLoadingDialog, event.cancelable, event.onCancel) }
                    }
                    LoadingEvent.Hide -> {
                        if (!isDestroyed) runCatching { controller.dismiss(this@bindLoadingDialog) }
                    }
                }
            }
        } finally {
            runCatching { controller.dismiss(this@bindLoadingDialog) }
        }
    }
}

/* ─────────────────────────── 零记忆负担：委托式声明 ────────────────────────────
 * 痛点：每个页面手写 bindLoadingDialog 会忘。
 * 解法：把绑定动作绑到 "声明 viewModel"的属性委托上——此动作业务本来就要写，无法遗漏。
 *
 * 约定：
 * - ViewModel 需要 loading 就实现 [LoadingOwner]，暴露 `loading: LoadingEmitter`。
 * - Activity/Fragment 用 `by autoLoadingViewModel<T>()` 代替 `by getViewModel<T>()`。
 * - VM 首次被访问时自动 [bindLoadingDialog]；不实现 [LoadingOwner] 的 VM 不受影响，仅做 lazy 创建。
 *
 * ```
 * class UserDetailVM : ViewModel(), LoadingOwner {
 *     override val loading = LoadingEmitter()
 *     fun load(id: String) = viewModelScope.launchApiWithLoading(loading) {
 *         _detail.value = ApiService.getUserDetail(id)
 *     }
 * }
 *
 * class UserDetailActivity : BaseActivity<ActivityUserDetailBinding>() {
 *     private val viewModel by autoLoadingViewModel<UserDetailVM>()
 *     override fun loadData() = viewModel.load(requireNotNull(id))
 * }
 *
 * class UserDetailFragment : BaseFragment<FragmentUserDetailBinding>() {
 *     private val viewModel by autoLoadingViewModel<UserDetailVM>()
 *     override fun loadData() = viewModel.load(requireNotNull(id))
 * }
 * ```
 */

/**
 * ViewModel 实现此接口即声明自己需要 loading UI 支持。
 * 与 [autoLoadingViewModel] 配合使用可实现自动绑定。
 */
interface LoadingOwner {
    val loading: LoadingEmitter
}

/**
 * 在此基础上额外完成：VM 实现 [LoadingOwner] 时自动 [bindLoadingDialog]。
 * 绑定使用 `lifecycleScope`，随 Activity 销毁自动取消。
 */

/* ──────────────── PageState 自动绑定到 StateLayout（与 LoadingDialog 对偶） ──────────────── */

/**
 * 在 lifecycle 进入 STARTED 时把 [emitter] 绑到 [provider] 提供的 StateLayout。
 * - 已 ≥STARTED：立即绑
 * - 未到 STARTED：注册 ON_START 一次性回调，触发后自动注销
 *
 * 用 `lifecycleScope` 收集 emitter，随 Activity 销毁自动取消。
 */
@PublishedApi
internal fun AppCompatActivity.autoBindPageState(
    emitter: PageStateEmitter,
    provider: () -> StateLayout,
) {
    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        provider().bindPageState(emitter, lifecycleScope)
        return
    }
    lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            provider().bindPageState(emitter, lifecycleScope)
            owner.lifecycle.removeObserver(this)
        }
    })
}

/**
 * Fragment 版：观察 [Fragment.viewLifecycleOwnerLiveData]，每次 view 重建都会拿到新的 viewLifecycleOwner。
 * 在新 VLO 进入 STARTED 时绑定（此时 onViewCreated/initView 已运行，StateLayout 已就绪）。
 * 用 `viewLifecycleOwner.lifecycleScope`，view 销毁时自动取消，避免操作已 detach 的 view。
 */
@PublishedApi
internal fun Fragment.autoBindPageState(
    emitter: PageStateEmitter,
    provider: () -> StateLayout,
) {
    viewLifecycleOwnerLiveData.observe(this) { vlo ->
        vlo ?: return@observe
        if (vlo.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            provider().bindPageState(emitter, vlo.lifecycleScope)
            return@observe
        }
        vlo.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                provider().bindPageState(emitter, vlo.lifecycleScope)
                owner.lifecycle.removeObserver(this)
            }
        })
    }
}

/**
 * 在此基础上额外完成：
 * - VM 实现 [LoadingOwner] 时自动 [bindLoadingDialog]
 * - VM 实现 [PageStateOwner] 且 Activity 实现 [IStateLayoutOwner] 时自动 [bindPageState]
 *
 * 绑定使用 `lifecycleScope`，随 Activity 销毁自动取消。
 */
inline fun <reified VM> AppCompatActivity.autoLoadingViewModel(): Lazy<VM>
        where VM : BaseViewModel = lazy {
    val vm = ViewModelProvider(this)[VM::class.java]
    (vm as? LoadingOwner)?.let { bindLoadingDialog(it.loading, lifecycleScope) }
    if (this is IStateLayoutOwner) {
        (vm as? PageStateOwner)?.let { autoBindPageState(it.pageState) { stateLayout } }
    }
    vm
}

/**
 * 在此基础上额外完成：
 * - VM 实现 [LoadingOwner] 时自动 [bindLoadingDialog]（dialog 宿主为 `requireActivity()`）
 * - VM 实现 [PageStateOwner] 且 Fragment 实现 [IStateLayoutOwner] 时自动 [bindPageState]
 *   （随 viewLifecycleOwner 自动 re-bind，view 重建场景安全）
 *
 * VM 作用域默认是 Fragment 自身；如需与宿主 Activity 共享，用 [autoLoadingActivityViewModel]。
 */
inline fun <reified VM> Fragment.autoLoadingViewModel(): Lazy<VM>
        where VM : BaseViewModel = lazy {
    val vm = ViewModelProvider(this)[VM::class.java]
    (vm as? LoadingOwner)?.let {
        (requireActivity() as? AppCompatActivity)?.bindLoadingDialog(it.loading, lifecycleScope)
    }
    if (this is IStateLayoutOwner) {
        (vm as? PageStateOwner)?.let { autoBindPageState(it.pageState) { stateLayout } }
    }
    vm
}

/**
 * Fragment 版（Activity 作用域）。VM 与宿主 Activity 共享 ViewModelStore。
 * Loading dialog 与 PageState 绑定都发生在当前 Fragment 上（多 Fragment 共享同一 VM 时按访问顺序）。
 */
inline fun <reified VM> Fragment.autoLoadingActivityViewModel(): Lazy<VM>
        where VM : BaseViewModel = lazy {
    val vm = ViewModelProvider(requireActivity())[VM::class.java]
    (vm as? LoadingOwner)?.let {
        (requireActivity() as? AppCompatActivity)?.bindLoadingDialog(it.loading, lifecycleScope)
    }
    if (this is IStateLayoutOwner) {
        (vm as? PageStateOwner)?.let { autoBindPageState(it.pageState) { stateLayout } }
    }
    vm
}
