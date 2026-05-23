package com.lhj.pagingutil

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * 链式 Builder 风格的 Paging 接入助手：
 *
 * - ConcatAdapter 拼装（业务头部 / 尾部 / LoadState 尾部）
 * - GridLayoutManager 头尾自动跨列
 * - 通过 PagingRefreshAdapter 接入任意刷新框架（双源协调）
 * - LoadState 自动驱动尾部 Adapter
 * - 错误 toast 去重
 * - 内置 PagingPatcher，配合 [PagingController] 一行做局部更新与乐观更新
 *
 * ViewModel 示例（继承 [BasePagingSource] 写一个分页源）：
 * ```
 * class UserPagingSource : BasePagingSource<UserItem>() {
 *     override suspend fun fetch(page: Int, pageSize: Int): Pair<List<UserItem>, Boolean> {
 *         val resp = HttpHelper.getUserList(page, pageSize)
 *         return resp.list.orEmpty() to (resp.hasMore == true)
 *     }
 * }
 *
 * class UserListVM : ViewModel() {
 *     val pagingFlow: Flow<PagingData<UserItem>> = Pager(
 *         PagingConfig(pageSize = 20, enablePlaceholders = false)
 *     ) { UserPagingSource() }.flow.cachedIn(viewModelScope)
 * }
 * ```
 *
 * Fragment 接入示例：
 * ```
 * val paging = PagingHelper.with<UserItem>(viewLifecycleOwner)
 *     .recyclerView(mBinding.rv)
 *     .refreshAdapter(SmartRefreshAdapter(mBinding.refreshLayout))
 *     .pagingAdapter(userAdapter)
 *     .pagingFlow(vm.pagingFlow)
 *     .keyOf { it.userId }
 *     .loadStateFooter { CommonLoadStateAdapter(onRetry = it) }
 *     .start()
 * ```
 */
class PagingHelper<T : Any> private constructor(private val owner: LifecycleOwner) {

    private data class Section(val adapter: RecyclerView.Adapter<*>, val spanFull: Boolean)

    private var recyclerView: RecyclerView? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var refreshAdapter: PagingRefreshAdapter? = null
    private var pagingAdapter: PagingDataAdapter<T, *>? = null
    private var pagingFlow: Flow<PagingData<T>>? = null

    private val headers = mutableListOf<Section>()
    private val footers = mutableListOf<Section>()
    private var loadStateFooterFactory: ((retry: () -> Unit) -> LoadStateAdapter<*>)? = null

    private var onHeaderRefresh: (suspend () -> Unit)? = null
    private var onLoadError: ((Throwable) -> Unit)? = null
    private var onEmpty: ((Boolean) -> Unit)? = null
    private var onLoadStateChange: ((CombinedLoadStates) -> Unit)? = null
    private var distinctErrorToast: Boolean = true

    private var keyOf: ((T) -> Any)? = null
    private var externalPatcher: PagingPatcher<Any, T>? = null
    private var clearPatchesOnRefresh: Boolean = true
    private var headerRefreshTimeoutMs: Long = DEFAULT_HEADER_REFRESH_TIMEOUT_MS
    private var pageStateHandler: PageStateHandler? = null
    private var emptyTextProvider: (() -> CharSequence?)? = null
    private var errorTextProvider: ((Throwable) -> CharSequence?)? = null
    private var dragSortLongPress: Boolean = true
    private var dragSortVibrate: Boolean = true
    private var dragSortCanDrag: ((item: T, localPos: Int) -> Boolean)? = null
    private var dragSortOnMoved: ((fromKey: Any, toKey: Any, fromLocal: Int, toLocal: Int) -> Unit)? = null
    private var customDragTouchHelperFactory: ((pa: PagingDataAdapter<T, *>) -> ItemTouchHelper)? = null
    private var itemAnimatorConfigured: Boolean = false
    private var itemAnimator: RecyclerView.ItemAnimator? = PagingItemAnimator()
    private var disableAnimatorOnRefresh: Boolean = true
    private var chatMode: Boolean = false

    companion object {
        /** onHeaderRefresh 的默认超时（10 秒）。超时后 onHeaderRefresh 抛 TimeoutCancellationException，coordinator 按头部失败处理 */
        const val DEFAULT_HEADER_REFRESH_TIMEOUT_MS: Long = 10_000L

        /** 创建 Helper 实例；[owner] 用于驱动生命周期与 coroutineScope，通常传 Fragment 的 viewLifecycleOwner 或 Activity 自身 */
        fun <T : Any> with(owner: LifecycleOwner) = PagingHelper<T>(owner)
    }

    /** 必填：承载分页数据的 RecyclerView */
    fun recyclerView(rv: RecyclerView) = apply { recyclerView = rv }
    /** 可选：自定义 LayoutManager；不传默认 LinearLayoutManager。GridLayoutManager 会自动让 spanFull 的头/尾跨整行 */
    fun layoutManager(lm: RecyclerView.LayoutManager) = apply { layoutManager = lm }

    /** 通用：传入任意实现了 [PagingRefreshAdapter] 的刷新适配器（接入自家或第三方框架时用） */
    fun refreshAdapter(adapter: PagingRefreshAdapter?) = apply { refreshAdapter = adapter }

    /** 必填：业务自己的 PagingDataAdapter，推荐继承 [BasePagingAdapter] / [BaseMultiPagingAdapter] */
    fun pagingAdapter(a: PagingDataAdapter<T, *>) = apply { pagingAdapter = a }
    /** 必填：ViewModel 暴露的 `Flow<PagingData<T>>`，必须在 VM 内 `cachedIn(viewModelScope)`，避免重订阅时重新拉首页 */
    fun pagingFlow(f: Flow<PagingData<T>>) = apply { pagingFlow = f }

    /** 添加业务头部（Banner / 公告 / 分组标题等）；spanFull=true 在 GridLayoutManager 下跨整行 */
    fun addHeader(adapter: RecyclerView.Adapter<*>, spanFull: Boolean = true) =
        apply { headers.add(Section(adapter, spanFull)) }

    /** 添加业务尾部（"已加载全部"卡片、推荐位等） */
    fun addFooter(adapter: RecyclerView.Adapter<*>, spanFull: Boolean = true) =
        apply { footers.add(Section(adapter, spanFull)) }

    /** 自动拼接 LoadStateAdapter 到最后；factory 接收 retry 回调 */
    fun loadStateFooter(factory: (retry: () -> Unit) -> LoadStateAdapter<*>) =
        apply { loadStateFooterFactory = factory }

    /** 下拉刷新时，除了 paging.refresh()，额外要做的事（头部接口、配置等） */
    fun onHeaderRefresh(block: suspend () -> Unit) = apply { onHeaderRefresh = block }

    /** refresh 失败回调；distinct=true 会自动去重相同异常，避免反复弹 toast */
    fun onLoadError(distinct: Boolean = true, block: (Throwable) -> Unit) =
        apply { distinctErrorToast = distinct; onLoadError = block }

    /** 列表为空时回调（仅基于 PagingAdapter 的 itemCount，不含头部） */
    fun onEmpty(block: (Boolean) -> Unit) = apply { onEmpty = block }

    /** 进阶：拿到完整 LoadState，自定义骨架屏 / 空态 / 错误页 */
    fun onLoadState(block: (CombinedLoadStates) -> Unit) = apply { onLoadStateChange = block }

    /**
     * 必填（使用 update / delete / insertHead / optimistic* 时）：item 唯一键提取器。
     *
     * 与 [patcher] 二选一：传了 [patcher] 时本字段可省（内部从外部 patcher 取 keyOf）。
     */
    fun keyOf(extractor: (T) -> Any) = apply { keyOf = extractor }

    /**
     * 可选：传入业务自己创建并持有的 [PagingPatcher]（通常放在 ViewModel 里），
     * 让本地补丁（update / delete / insertHead 等）跨 View 重建（屏幕旋转 / 返回再进）保留。
     *
     * 外部 patcher 优先级高于 [keyOf]。建议把 [keyOf] 写在 patcher 构造里，PagingHelper 这里就不用再传：
     *
     * ```
     * class UserListVM : ViewModel() {
     *     val patcher = PagingPatcher<Any, UserItem> { it.id }   // ← 跟着 VM 一起活
     *     val pagingFlow = pagingFlowOf { p, s -> api.users(p, s).run { list to hasMore } }
     * }
     *
     * PagingHelper.with<UserItem>(this)
     *     .pagingAdapter(adapter)
     *     .pagingFlow(vm.pagingFlow)
     *     .patcher(vm.patcher)         // ← 而不是 .keyOf { ... }
     *     ...
     *     .start()
     * ```
     *
     * 进程被杀重启仍然会丢，要持久化得叠 Room/DataStore，patcher 本身只活在内存。
     */
    fun patcher(patcher: PagingPatcher<Any, T>) = apply { externalPatcher = patcher }

    /** 下拉刷新时是否清空所有本地补丁，默认 true */
    fun clearPatchesOnRefresh(clear: Boolean) = apply { clearPatchesOnRefresh = clear }

    /**
     * 可选：传入任意 [PageStateHandler] 实现，由 helper 自动管理 加载中 / 空数据 / 加载失败 / 内容 4 种全屏占位态。
     *
     * 框架只通过接口的 4 个方法操作业务的 View，不绑死任何具体实现。
     * 业务可以：
     * - 直接用项目自带的 [PagingStateLayout]（最简单）
     * - 让自家通用空页 / 错误页 View 实现 [PageStateHandler]，保持原有 UI 资产
     * - 写一个轻适配器把第三方库（如 LoadSir / MultipleStatusView 等）包成 [PageStateHandler]
     *
     * 状态判定（基于 pagingAdapter.itemCount + pa.loadStateFlow.refresh / append）：
     * - 列表为空 + refresh = Loading                                 → showLoading()
     * - 列表为空 + refresh = Error                                   → showError(throwable, text)
     * - 列表为空 + refresh = NotLoading + append.endOfPaginationReached → showEmpty(text)
     * - 其他（有数据）                                                → showContent()
     *
     * 文案：通过 [emptyText] / [errorText] 注入，文本会作为参数传给 handler。handler 自身可在收到 null 时回退到内部默认文案。
     */
    fun pageState(handler: PageStateHandler) = apply { pageStateHandler = handler }

    /**
     * 配置空数据文案。每次进入空态时都会调用这个 provider，因此可以返回随状态变化的动态文案。
     *
     * 示例：
     * ```
     * .emptyText { "没有 \"$keyword\" 相关结果" }
     * ```
     */
    fun emptyText(provider: () -> CharSequence?) = apply { emptyTextProvider = provider }

    /** 静态空数据文案的便捷重载 */
    fun emptyText(text: CharSequence) = apply { emptyTextProvider = { text } }

    /**
     * 配置加载失败文案。可根据异常类型返回不同提示，如：
     * ```
     * .errorText { e ->
     *     when (e) {
     *         is UnknownHostException -> "网络不给力，请检查后重试"
     *         is HttpException -> "服务异常 ${e.code()}"
     *         else -> e.message
     *     }
     * }
     * ```
     * provider 返回 null 时由 handler 自身回退（一般是 throwable.message）。
     */
    fun errorText(provider: (Throwable) -> CharSequence?) = apply { errorTextProvider = provider }

    /** 静态加载失败文案的便捷重载 */
    fun errorText(text: CharSequence) = apply { errorTextProvider = { text } }

    /**
     * 头部接口（[onHeaderRefresh]）的超时阈值，单位毫秒；默认 [DEFAULT_HEADER_REFRESH_TIMEOUT_MS]（10 秒）。
     *
     * 超时后 [onHeaderRefresh] 会抛出 TimeoutCancellationException，被 coordinator 视为头部失败，
     * 同时回调 [onLoadError]，避免因头部一直 suspend 导致下拉刷新动画永远不消失。
     *
     * 传 0 或负数表示不超时（不推荐）；典型业务取值 5_000 ~ 15_000ms。
     */
    fun headerRefreshTimeout(ms: Long) = apply { headerRefreshTimeoutMs = ms }

    /**
     * 自定义 RecyclerView 的 [RecyclerView.ItemAnimator]。
     *
     * 默认 [PagingItemAnimator]：关闭 change 动画（避免局部 update 闪白），保留 add/remove/move。
     * 不显式调用本方法时使用默认值；显式 `.itemAnimator(null)` 可关闭所有动画（首屏骨架场景常用）。
     */
    fun itemAnimator(animator: RecyclerView.ItemAnimator?) = apply {
        itemAnimatorConfigured = true
        itemAnimator = animator
    }

    /**
     * 下拉刷新期间是否临时关闭 ItemAnimator，避免大批 diff 触发的连串动画导致卡顿与闪烁；
     * 默认 true。刷新动画结束后自动还原为 [itemAnimator] 配置的实例。
     *
     * 大多数业务保持默认即可；要保留刷新动画（例如商品列表想看到新品淡入）就传 false。
     */
    fun disableAnimatorOnRefresh(disable: Boolean) = apply { disableAnimatorOnRefresh = disable }

    /**
     * 聊天模式:自动给 LinearLayoutManager 设 `reverseLayout=true` + `stackFromEnd=true`,
     * 业务侧不必再手动写这两行。
     *
     * 视觉效果:
     * - adapter index 0 渲染在屏幕**底部**,index N 渲染在**顶部**
     * - 数据不满屏时整体贴底(stackFromEnd)
     * - 用户向上滚动 = 走 Paging 的 APPEND 方向 = 加载更早消息
     *
     * 数据约定(配合本模式时):
     * - PagingSource 返回顺序应当是 **[最新, ..., 较早]**,即 index 0 是最新一条
     * - REFRESH 拉最新一页, hasMore=true 表示"还有更早历史"
     * - 此时单向 [BasePagingSource.fetch] 就够用,**不需要** [BasePagingSource.fetchBidirectional] / PREPEND
     * - 新消息到达调 [PagingController.insertHead] —— index 0 即视觉底部,新消息从底部冒出
     *
     * 行为细节:
     * - 没传 [layoutManager](...) → 自动建一个带 chat 配置的 LinearLayoutManager
     * - 传了 LinearLayoutManager → 把它的 reverseLayout / stackFromEnd 强制为 true
     * - 传了 GridLayoutManager / StaggeredGridLayoutManager → **不动**(聊天用 grid 不常见,
     *   业务想用得自己配 reverseLayout)
     *
     * @param enabled 默认 true
     */
    fun chatMode(enabled: Boolean = true) = apply { chatMode = enabled }

    /**
     * 启用拖动排序（基于 [androidx.recyclerview.widget.ItemTouchHelper]）。
     *
     * 语义：
     * - 只能拖 paging 区内的 item，header / footer / loadState footer 不参与
     * - 拖动只动视图层（notifyItemMoved），不会改动 PagingData 流
     * - 要让顺序持久化，必须在 [onMoved] 里调服务端接口；服务端下次返回新顺序通过 paging 落地
     * - 下拉刷新后本地视图层的变动会被新数据覆盖（符合"服务端是权威顺序"这个模型）
     *
     * @param longPressEnabled true = 长按任意 item 即可开拖；false = 需要业务在 ViewHolder 的拖动手柄上
     *                          调 [DragSortHelper.startDrag]
     * @param vibrateOnDragStart true（默认）= 拖动启动瞬间触发系统触感（HapticFeedback），
     *                          自动遵循用户系统触感设置；不需要权限
     * @param canDrag 业务自定义"该 item 是否参与拖动"。返回 false 时该项既不能被拖动，也不能被
     *                其他项拖到（防止别的项越过锁定项把它跳过去）。null = 全部可拖。例如：
     *                ```
     *                .enableDragSort(canDrag = { item, _ -> !item.isPinned }) { ... }
     *                ```
     * @param onMoved (fromKey, toKey, fromLocalPos, toLocalPos) 拖动时的每一步回调；业务在这里调服务端
     */
    fun enableDragSort(
        longPressEnabled: Boolean = true,
        vibrateOnDragStart: Boolean = true,
        canDrag: ((item: T, localPos: Int) -> Boolean)? = null,
        onMoved: (fromKey: Any, toKey: Any, fromLocal: Int, toLocal: Int) -> Unit
    ) = apply {
        dragSortLongPress = longPressEnabled
        dragSortVibrate = vibrateOnDragStart
        dragSortCanDrag = canDrag
        dragSortOnMoved = onMoved
    }

    /**
     * 进阶用法：完全自定义拖动行为。helper 只负责把 [factory] 返回的 [ItemTouchHelper]
     * attach 到 RecyclerView，其余（getMovementFlags / onMove / 视图层 notifyItemMoved /
     * 触感 / Swipe / 自定义 onChildDraw 等）全部由业务自己实现。
     *
     * 与 [enableDragSort] 互斥；同时设置时本方法生效，[enableDragSort] 的参数被忽略。
     *
     * factory 参数：
     * - pa：业务的 [PagingDataAdapter]，用于 `peek` / `snapshot` / `notifyItemMoved`
     *        （注意 paging item 的位置请用 `holder.bindingAdapterPosition`，它已经是 paging 区的本地索引）
     *
     * factory 内创建的 ItemTouchHelper 业务自己持有引用即可，方便在 ViewHolder 拖动手柄上调
     * `touchHelper.startDrag(holder)`。
     *
     * 示例（自定义 swipe-to-delete + drag）：
     * ```
     * private lateinit var dragTouch: ItemTouchHelper
     *
     * paging.dragSort { pa ->
     *     ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
     *         ItemTouchHelper.UP or ItemTouchHelper.DOWN,
     *         ItemTouchHelper.LEFT
     *     ) {
     *         override fun onMove(rv, from, to): Boolean {
     *             val f = from.bindingAdapterPosition
     *             val t = to.bindingAdapterPosition
     *             pa.notifyItemMoved(f, t)
     *             vm.reorder(pa.peek(f)!!.id, pa.peek(t)!!.id)
     *             return true
     *         }
     *         override fun onSwiped(holder, dir) {
     *             vm.delete(pa.peek(holder.bindingAdapterPosition)!!.id)
     *         }
     *     }).also { dragTouch = it }
     * }
     * ```
     */
    fun dragSort(factory: (pa: PagingDataAdapter<T, *>) -> ItemTouchHelper) = apply {
        customDragTouchHelperFactory = factory
    }

    /** 完成所有配置后调用：组装 ConcatAdapter、绑定刷新框架、启动 PagingData 收集，返回对外操作句柄 [PagingController] */
    fun start(): PagingController<T> {
        val rv = requireNotNull(recyclerView) { "recyclerView 不能为空" }
        val pa = requireNotNull(pagingAdapter) { "pagingAdapter 不能为空" }
        val flow = requireNotNull(pagingFlow) { "pagingFlow 不能为空" }

        val patcher: PagingPatcher<Any, T>? = externalPatcher
            ?: keyOf?.let { PagingPatcher(it) }
        val wrappedFlow: Flow<PagingData<T>> = patcher?.wrap(flow) ?: flow
        val runner = KeyedRequestRunner(owner.lifecycleScope)

        val sections = mutableListOf<RecyclerView.Adapter<*>>()
        headers.forEach { sections.add(it.adapter) }
        sections.add(pa)
        footers.forEach { sections.add(it.adapter) }
        val loadStateFooter = loadStateFooterFactory?.invoke { pa.retry() }
        loadStateFooter?.let { sections.add(it) }

        val concat = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
            *sections.toTypedArray()
        )

        val lm = layoutManager ?: LinearLayoutManager(rv.context)
        if (chatMode && lm is LinearLayoutManager) {
            lm.reverseLayout = true
            lm.stackFromEnd = true
        }
        rv.layoutManager = lm
        when (lm) {
            is GridLayoutManager -> configSpanSize(lm, concat, loadStateFooter, pa)
            is StaggeredGridLayoutManager -> configStaggered(rv, concat, loadStateFooter, pa)
        }
        rv.adapter = concat

        rv.itemAnimator = itemAnimator

        var dragHelper: DragSortHelper<T>? = null
        val customFactory = customDragTouchHelperFactory
        val moved = dragSortOnMoved
        when {
            customFactory != null -> {
                customFactory.invoke(pa).attachToRecyclerView(rv)
            }
            moved != null -> {
                val key = patcher?.keyOf ?: keyOf
                requireNotNull(key) { "enableDragSort 需要先 .keyOf { ... } 或 .patcher(...) 提供主键" }
                dragHelper = DragSortHelper(
                    pagingAdapter = pa,
                    keyOf = key,
                    longPressEnabled = dragSortLongPress,
                    vibrateOnDragStart = dragSortVibrate,
                    canDrag = dragSortCanDrag,
                    onMoved = moved
                )
                dragHelper.touchHelper.attachToRecyclerView(rv)
            }
        }

        val ra = refreshAdapter
        ra?.setLoadMoreEnabled(false)
        val coordinator = RefreshCoordinator(ra)
        var userPullingRefresh = false
        ra?.setOnRefreshListener {
            userPullingRefresh = true
            val needHeader = onHeaderRefresh != null
            coordinator.start(needHeader)
            if (clearPatchesOnRefresh) patcher?.clearAll()
            if (needHeader) {
                owner.lifecycleScope.launch {
                    val timeoutMs = headerRefreshTimeoutMs
                    val result = runCatching {
                        if (timeoutMs > 0) {
                            withTimeout(timeoutMs) { onHeaderRefresh!!.invoke() }
                        } else {
                            onHeaderRefresh!!.invoke()
                        }
                    }
                    result.onFailure { onLoadError?.invoke(it) }
                    coordinator.headerDone(success = result.isSuccess)
                }
            }
            pa.refresh()
        }

        owner.lifecycleScope.launch {
            owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { wrappedFlow.collectLatest { pa.submitData(it) } }
                launch {
                    var lastErrorKey = 0
                    pageStateHandler?.bindRetry { pa.retry() }

                    pa.loadStateFlow.collectLatest { state ->
                        if (disableAnimatorOnRefresh) {
                            when (state.refresh) {
                                is LoadState.Loading -> if (rv.itemAnimator != null) rv.itemAnimator = null
                                else -> if (rv.itemAnimator == null) rv.itemAnimator = itemAnimator
                            }
                        }

                        val effectiveAppend = if (state.refresh is LoadState.Loading) {
                            LoadState.NotLoading(endOfPaginationReached = false)
                        } else {
                            state.append
                        }
                        loadStateFooter?.loadState = effectiveAppend

                        if (state.refresh !is LoadState.Loading) {
                            ra?.setRefreshEnabled(state.append !is LoadState.Loading)
                        }

                        pageStateHandler?.let { sv ->
                            val empty = pa.itemCount == 0
                            val refresh = state.refresh
                            when {
                                empty && refresh is LoadState.Loading && !userPullingRefresh -> sv.showLoading()
                                empty && refresh is LoadState.Error -> {
                                    val text = errorTextProvider?.invoke(refresh.error)
                                    sv.showError(refresh.error, text)
                                }
                                empty && refresh is LoadState.NotLoading
                                    && state.append.endOfPaginationReached -> {
                                    sv.showEmpty(emptyTextProvider?.invoke())
                                }
                                else -> sv.showContent()
                            }
                        }

                        onLoadStateChange?.invoke(state)
                        when (val r = state.refresh) {
                            is LoadState.NotLoading -> {
                                coordinator.pagingDone(true)
                                userPullingRefresh = false
                                onEmpty?.invoke(
                                    pa.itemCount == 0 && state.append.endOfPaginationReached
                                )
                            }
                            is LoadState.Error -> {
                                coordinator.pagingDone(false)
                                userPullingRefresh = false
                                val key = r.error.javaClass.hashCode() xor (r.error.message?.hashCode() ?: 0)
                                if (!distinctErrorToast || key != lastErrorKey) {
                                    lastErrorKey = key
                                    onLoadError?.invoke(r.error)
                                }
                                onEmpty?.invoke(pa.itemCount == 0)
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        return PagingController(
            adapter = pa,
            recyclerView = rv,
            refreshAdapter = ra,
            onHeaderRefresh = onHeaderRefresh,
            owner = owner,
            patcher = patcher,
            clearPatchesOnRefresh = clearPatchesOnRefresh,
            runner = runner,
            dragSortHelper = dragHelper
        )
    }

    /**
     * StaggeredGridLayoutManager 模式下的 span 适配。
     *
     * 与 GridLayoutManager 不同，StaggeredGridLayoutManager 没有 SpanSizeLookup —— item 占满整行
     * 是通过 [StaggeredGridLayoutManager.LayoutParams.isFullSpan] 设置的，且只能在 ViewHolder
     * 已经 attach 到 RecyclerView 之后再改。所以这里在 RecyclerView 上注册 onChildViewAttachedToWindow
     * 监听，attach 时按 ViewHolder 所属 inner adapter 判断要不要 fullSpan：
     * - spanFull 的业务头/尾 → fullSpan
     * - LoadStateAdapter（"加载中…" / "加载失败" / "没有更多了" 那个）→ fullSpan
     * - 业务 pagingAdapter 内部的 item → 不动；要某 item 跨整行，业务自己在 onBind 里把 LayoutParams 设 isFullSpan
     */
    private fun configStaggered(
        rv: RecyclerView,
        concat: ConcatAdapter,
        loadStateFooter: LoadStateAdapter<*>?,
        pagingAdapter: PagingDataAdapter<T, *>
    ) {
        val fullSpanAdapters = buildSet<RecyclerView.Adapter<*>> {
            headers.filter { it.spanFull }.forEach { add(it.adapter) }
            footers.filter { it.spanFull }.forEach { add(it.adapter) }
            loadStateFooter?.let { add(it) }
        }
        if (fullSpanAdapters.isEmpty()) return

        rv.addOnChildAttachStateChangeListener(object :
            RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val pos = rv.getChildAdapterPosition(view)
                if (pos == RecyclerView.NO_POSITION) return
                val inner = findInner(concat, pos) ?: return
                if (inner === pagingAdapter) return
                if (inner !in fullSpanAdapters) return
                val lp = view.layoutParams as? StaggeredGridLayoutManager.LayoutParams ?: return
                if (!lp.isFullSpan) {
                    lp.isFullSpan = true
                    view.layoutParams = lp
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    /** ConcatAdapter 全局 position → 承载它的内部 adapter */
    private fun findInner(
        concat: ConcatAdapter,
        position: Int
    ): RecyclerView.Adapter<out RecyclerView.ViewHolder>? {
        var offset = 0
        for (a in concat.adapters) {
            val count = a.itemCount
            if (position < offset + count) return a
            offset += count
        }
        return null
    }
    /**
     * GridLayoutManager 模式下让 spanFull 的 header / footer 与 LoadStateAdapter 跨整行。
     * 兼容外部自定义 SpanSize：进来前先抓住调用方在 LayoutManager 上预设的 spanSizeLookup，
     * 只对 spanFull 的 header / footer / LoadStateAdapter 强制跨整行；其余位置（尤其是
     * pagingAdapter 本身的 item）转成 adapter 内的本地 position 后委托回外部 lookup，
     * 让业务自己的"精选 item 占满 / 普通占一格"等需求继续生效。
     *
     * 调用方没设 lookup 时，外部 lookup 是 GridLayoutManager 自带的 DefaultSpanSizeLookup，
     * 默认返回 1，行为和原来一致。
     */
    private fun configSpanSize(
        lm: GridLayoutManager,
        concat: ConcatAdapter,
        loadStateFooter: LoadStateAdapter<*>?,
        pagingAdapter: PagingDataAdapter<T, *>
    ) {
        val spanCount = lm.spanCount
        val userLookup = lm.spanSizeLookup
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                var offset = 0
                var inner: RecyclerView.Adapter<out RecyclerView.ViewHolder>? = null
                var localPos = position
                for (a in concat.adapters) {
                    val count = a.itemCount
                    if (position < offset + count) {
                        inner = a
                        localPos = position - offset
                        break
                    }
                    offset += count
                }
                inner ?: return 1

                val isFullSpan = headers.any { it.adapter === inner && it.spanFull }
                    || footers.any { it.adapter === inner && it.spanFull }
                    || inner === loadStateFooter
                if (isFullSpan) return spanCount

                if (inner === pagingAdapter) return userLookup.getSpanSize(localPos)
                return 1
            }
        }
    }

}
