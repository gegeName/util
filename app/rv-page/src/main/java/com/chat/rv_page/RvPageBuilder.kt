package com.chat.rv_page

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.CombinedLoadStates
import androidx.paging.LoadStateAdapter
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import com.chat.pagingutil.BaseMultiPagingAdapter
import com.chat.pagingutil.BasePagingAdapter
import com.chat.pagingutil.MultiTypeItem
import com.chat.pagingutil.PageStateHandler
import com.chat.pagingutil.PagingHelper
import com.chat.pagingutil.PagingPatcher
import com.chat.pagingutil.PagingRefreshAdapter
import com.chat.pagingutil.SingleItemBindingAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Objects

/**
 * 页面级 DSL 入口。
 *
 * ```
 * val page = RvPage.with(viewLifecycleOwner)
 *     .recyclerView(mBinding.rv)
 *     .refreshAdapter(SmartRefreshAdapter(mBinding.srl))
 *     .pageState(mBinding.pageStateView)
 *     .sections {
 *         single<Banner, ItemBannerBinding>(ItemBannerBinding::inflate, tag = "banner") { ... }
 *         list<MenuItem, ItemMenuBinding>(ItemMenuBinding::inflate, tag = "menu") {
 *             keyOf { it.id }; ...
 *         }
 *         pagingList<FeedItem, ItemFeedBinding>(ItemFeedBinding::inflate, tag = "feed") {
 *             flow = vm.feedFlow; keyOf { it.id }; ...
 *         }
 *     }
 *     .start()
 *
 * page.paging<FeedItem>("feed")?.optimisticDelete(...)
 * page.addSection("promo", PromoAdapter(), at = 1)
 * page.hideSection("banner")
 * page.refresh()
 * ```
 *
 * 约束:
 * - 一个页面最多 1 个 paging section(`pagingList` + `pagingMultiList` 加起来);违反时 [start] 抛错。
 * - section tag 在同一页面内唯一;不传则自动 `section_0` / `section_1` … 。
 */
class RvPageBuilder private constructor(private val owner: LifecycleOwner) {

    companion object {
        /** 创建实例；[owner] 通常传 viewLifecycleOwner（Fragment）或 Activity 自身 */
        fun with(owner: LifecycleOwner) = RvPageBuilder(owner)

        /**
         * 由 [keyOf] 派生的 [DiffUtil.ItemCallback]:`areItemsTheSame = keyOf 相等`,
         * `areContentsTheSame = Objects.equals`(业务侧 T 实现 `equals`,data class 默认满足)。
         */
        internal fun <T : Any> autoDiff(keyOf: (T) -> Any): DiffUtil.ItemCallback<T> =
            object : DiffUtil.ItemCallback<T>() {
                override fun areItemsTheSame(oldItem: T, newItem: T): Boolean =
                    Objects.equals(keyOf(oldItem), keyOf(newItem))

                override fun areContentsTheSame(oldItem: T, newItem: T): Boolean =
                    Objects.equals(oldItem, newItem)
            }
    }

    private var recyclerView: RecyclerView? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var refreshAdapter: PagingRefreshAdapter? = null
    private var pageStateHandler: PageStateHandler? = null
    private var emptyTextProvider: (() -> CharSequence?)? = null
    private var errorTextProvider: ((Throwable) -> CharSequence?)? = null
    private var onHeaderRefresh: (suspend () -> Unit)? = null
    private var hideAuxOnPageState: Boolean = true
    private var awaitStaticBeforePagingMs: Long = 0L
    private var onLoadStateChange: ((CombinedLoadStates) -> Unit)? = null
    private var headerRefreshTimeoutMs: Long? = null
    private var itemAnimator: RecyclerView.ItemAnimator? = null
    private var itemAnimatorConfigured: Boolean = false
    private var disableAnimatorOnRefresh: Boolean = true
    private var onStickyHeaderSetup: ((View) -> Unit)? = null
    private var onStickyHeaderTeardown: ((View) -> Unit)? = null

    private val sectionDefs = mutableListOf<SectionDef>()

    fun recyclerView(rv: RecyclerView) = apply { recyclerView = rv }
    fun layoutManager(lm: RecyclerView.LayoutManager) = apply { layoutManager = lm }
    fun refreshAdapter(adapter: PagingRefreshAdapter?) = apply { refreshAdapter = adapter }

    /** 复用现有 [PageStateHandler]；空态/错误/加载中由 helper 自动驱动 */
    fun pageState(handler: PageStateHandler) = apply { pageStateHandler = handler }

    fun emptyText(provider: () -> CharSequence?) = apply { emptyTextProvider = provider }
    fun emptyText(text: CharSequence) = apply { emptyTextProvider = { text } }
    fun errorText(provider: (Throwable) -> CharSequence?) = apply { errorTextProvider = provider }
    fun errorText(text: CharSequence) = apply { errorTextProvider = { text } }

    /** 下拉刷新时额外执行的头部接口;纯静态页面也可用。 */
    fun onHeaderRefresh(block: suspend () -> Unit) = apply { onHeaderRefresh = block }

    /**
     * 分页 loading / empty / error 时是否临时摘掉 static section / loadStateFooter;默认 true。
     * 想在分页模式下用 [RvPageController.hideSection] 必须显式传 false。
     */
    fun hideAuxOnPageState(hide: Boolean) = apply { hideAuxOnPageState = hide }

    /**
     * 启动时延迟收集 paging flow,等所有 static section 都首次有数据(或超时)再放行,
     * 避免"paging 比 static 接口快、列表先画再被后到的 header 挤下去"的位移动画。
     *
     * 0 = 关(默认);>0 = 启用且最长等 [timeoutMs] 毫秒。超时后无论 static 是否到齐都放行 paging,
     * 避免某个 static 接口挂掉导致评论永不出现。
     *
     * 不影响 static section 自身渲染:它们一到就画,仅推迟 paging。
     */
    fun awaitStaticBeforePaging(timeoutMs: Long) = apply {
        awaitStaticBeforePagingMs = timeoutMs.coerceAtLeast(0L)
    }

    fun onLoadState(block: (CombinedLoadStates) -> Unit) = apply { onLoadStateChange = block }

    /** [onHeaderRefresh] 超时阈值;ms;不传走 [PagingHelper] 默认 10 秒。 */
    fun headerRefreshTimeout(ms: Long) = apply { headerRefreshTimeoutMs = ms }

    /** 自定义 ItemAnimator;显式 null 关闭所有动画。 */
    fun itemAnimator(animator: RecyclerView.ItemAnimator?) = apply {
        itemAnimatorConfigured = true
        itemAnimator = animator
    }

    /** 下拉刷新期间是否临时关闭 ItemAnimator;默认 true。 */
    fun disableAnimatorOnRefresh(disable: Boolean) = apply { disableAnimatorOnRefresh = disable }

    /** 某 view 被提升为 sticky header 时回调;通常用于加阴影 / 抬高 elevation / 改背景。 */
    fun onStickyHeaderSetup(block: (View) -> Unit) = apply { onStickyHeaderSetup = block }

    /** 某 view 退出 sticky 状态时回调;撤销 [onStickyHeaderSetup] 的副作用。 */
    fun onStickyHeaderTeardown(block: (View) -> Unit) = apply { onStickyHeaderTeardown = block }

    /** 在 block 内声明各 section。 */
    fun sections(block: SectionsScope.() -> Unit) = apply {
        SectionsScope(sectionDefs).block()
    }

    /** 完成配置并装配。 */
    fun start(): RvPageController {
        val rv = requireNotNull(recyclerView) { "recyclerView 不能为空" }
        require(sectionDefs.isNotEmpty()) { "至少声明一个 section" }
        val tagSet = mutableSetOf<String>()
        sectionDefs.forEach {
            require(tagSet.add(it.tag)) { "section tag 重复: ${it.tag}" }
        }
        val pagingDefs = sectionDefs.filterIsInstance<SectionDef.Paging<*>>()
        require(pagingDefs.size <= 1) {
            "同一页面最多 1 个分页 section，发现 ${pagingDefs.size} 个: " +
                    pagingDefs.joinToString { it.tag }
        }
        val controller = if (pagingDefs.size == 1) buildWithPaging(rv) else buildStatic(rv)
        installVisibilityTracker(rv)
        installStickyHeaders(rv)
        return controller
    }

    /**
     * 任一 section 声明 sticky 谓词时挂 [StickyHeaderItemDecoration]:把全局 position 翻译成
     * (section, localPos),用 section 自带的谓词逐项判定。
     */
    private fun installStickyHeaders(rv: RecyclerView) {
        if (sectionDefs.none { it.isStickyHeaderAt != null }) return
        val concat = rv.adapter as? ConcatAdapter ?: return
        val setupCb = onStickyHeaderSetup
        val teardownCb = onStickyHeaderTeardown
        val callbacks = object : StickyHeaderCallbacks {
            override fun isStickyHeader(position: Int): Boolean {
                var offset = 0
                for (a in concat.adapters) {
                    val count = a.itemCount
                    if (position < offset + count) {
                        val def = sectionDefs.firstOrNull { it.concatAdapter === a }
                            ?: return false
                        val pred = def.isStickyHeaderAt ?: return false
                        return pred(position - offset)
                    }
                    offset += count
                }
                return false
            }

            override fun setupStickyHeaderView(stickyHeader: View) {
                setupCb?.invoke(stickyHeader)
            }

            override fun teardownStickyHeaderView(stickyHeader: View) {
                teardownCb?.invoke(stickyHeader)
            }
        }
        rv.addItemDecoration(StickyHeaderItemDecoration(callbacks))
    }

    private fun gatePagingFlow(
        realFlow: Flow<PagingData<Any>>,
        staticAdapters: List<RecyclerView.Adapter<*>>,
        timeoutMs: Long
    ): Flow<PagingData<Any>> {
        if (timeoutMs <= 0) return realFlow
        val waiting = staticAdapters.filter { it.itemCount == 0 }
        if (waiting.isEmpty()) return realFlow

        val ready = CompletableDeferred<Unit>()
        val pending = waiting.toMutableSet()
        val observers = mutableMapOf<RecyclerView.Adapter<*>, RecyclerView.AdapterDataObserver>()

        waiting.forEach { adapter ->
            val ob = object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = check()
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = check()
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = check()
                private fun check() {
                    if (adapter.itemCount > 0) {
                        pending.remove(adapter)
                        runCatching { adapter.unregisterAdapterDataObserver(this) }
                        observers.remove(adapter)
                        if (pending.isEmpty() && !ready.isCompleted) ready.complete(Unit)
                    }
                }
            }
            observers[adapter] = ob
            adapter.registerAdapterDataObserver(ob)
        }

        return flow {
            try {
                withTimeoutOrNull(timeoutMs) { ready.await() }
                emitAll(realFlow)
            } finally {
                observers.entries.toList().forEach { (a, o) ->
                    runCatching { a.unregisterAdapterDataObserver(o) }
                }
                observers.clear()
            }
        }
    }

    private fun installVisibilityTracker(rv: RecyclerView) {
        val subs = sectionDefs.filter { it.visibilityDispatch != null }
        if (subs.isEmpty()) return
        val tracker = VisibilityTracker(rv)
        subs.forEach { def ->
            tracker.register(
                def.concatAdapter,
                def.visibilityThresholdPercent,
                def.visibilityDispatch!!
            )
        }
    }

    private fun buildWithPaging(rv: RecyclerView): RvPageController {
        val pagingIndex = sectionDefs.indexOfFirst { it is SectionDef.Paging<*> }

        @Suppress("UNCHECKED_CAST")
        val pagingDef = sectionDefs[pagingIndex] as SectionDef.Paging<Any>
        val headersDef = sectionDefs.subList(0, pagingIndex)
        val footersDef = sectionDefs.subList(pagingIndex + 1, sectionDefs.size)

        val gatedFlow = gatePagingFlow(
            realFlow = pagingDef.flow,
            staticAdapters = (headersDef + footersDef).map { it.concatAdapter },
            timeoutMs = awaitStaticBeforePagingMs
        )

        val helper = PagingHelper.with<Any>(owner)
            .recyclerView(rv)
            .pagingAdapter(pagingDef.pagingAdapter)
            .pagingFlow(gatedFlow)
        layoutManager?.let { helper.layoutManager(it) }
        refreshAdapter?.let { helper.refreshAdapter(it) }
        pageStateHandler?.let { helper.pageState(it) }
        emptyTextProvider?.let { helper.emptyText(it) }
        errorTextProvider?.let { helper.errorText(it) }
        onHeaderRefresh?.let { helper.onHeaderRefresh(it) }
        pagingDef.keyOf?.let { helper.keyOf(it) }
        pagingDef.patcher?.let { helper.patcher(it) }
        helper.clearPatchesOnRefresh(pagingDef.clearPatchesOnRefresh)
        pagingDef.onLoadError?.let {
            helper.onLoadError(
                distinct = pagingDef.distinctErrorToast,
                block = it
            )
        }
        pagingDef.onEmpty?.let { helper.onEmpty(it) }
        pagingDef.loadStateFooterFactory?.let { helper.loadStateFooter(it) }
        onLoadStateChange?.let { helper.onLoadState(it) }
        headerRefreshTimeoutMs?.let { helper.headerRefreshTimeout(it) }
        if (itemAnimatorConfigured) helper.itemAnimator(itemAnimator)
        helper.disableAnimatorOnRefresh(disableAnimatorOnRefresh)
        if (pagingDef.chatMode) helper.chatMode(true)
        pagingDef.dragSortFactory?.let { helper.dragSort(it) }
        pagingDef.dragSortConfig?.let {
            helper.enableDragSort(
                longPressEnabled = it.longPressEnabled,
                vibrateOnDragStart = it.vibrateOnDragStart,
                canDrag = it.canDrag,
                onMoved = it.onMoved
            )
        }
        helper.hideAuxOnPageState(hideAuxOnPageState)

        headersDef.forEach { helper.addHeader(it.concatAdapter, spanFull = it.spanFull) }
        footersDef.forEach { helper.addFooter(it.concatAdapter, spanFull = it.spanFull) }

        val pagingController = helper.start()
        // 把 controller 绑回 PagingSectionHandle，业务通过 handle.controller 拿
        pagingDef.handle.bind(pagingController)
        val concat = rv.adapter as ConcatAdapter

        val entries = sectionDefs.map {
            RvPageController.SectionEntry(
                tag = it.tag,
                adapter = it.adapter,
                spanFull = it.spanFull,
                visible = true,
                concatEntry = it.concatAdapter
            )
        }.toMutableList()

        return RvPageController(
            recyclerView = rv,
            concatAdapter = concat,
            entries = entries,
            pagingController = pagingController,
            pagingTag = pagingDef.tag,
            refreshAdapter = refreshAdapter
        )
    }

    private fun buildStatic(rv: RecyclerView): RvPageController {
        val concat = ConcatAdapter(
            ConcatAdapter.Config.Builder().setIsolateViewTypes(true).build(),
            *sectionDefs.map { it.concatAdapter }.toTypedArray()
        )
        val lm = layoutManager ?: LinearLayoutManager(rv.context)
        rv.layoutManager = lm
        rv.adapter = concat
        when (lm) {
            is GridLayoutManager -> configSpanSize(lm, concat)
            is StaggeredGridLayoutManager -> configStaggered(rv, concat)
        }

        val entries = sectionDefs.map {
            RvPageController.SectionEntry(
                tag = it.tag,
                adapter = it.adapter,
                spanFull = it.spanFull,
                visible = true,
                concatEntry = it.concatAdapter
            )
        }.toMutableList()

        pageStateHandler?.let { handler ->
            handler.bindRetry { /* 静态模式无 paging.retry()，留给业务自己 refresh */ }
            updateStaticPageState(handler, concat)
            concat.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = updateStaticPageState(handler, concat)
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                    updateStaticPageState(handler, concat)

                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) =
                    updateStaticPageState(handler, concat)
            })
        }

        refreshAdapter?.let { ra ->
            ra.setLoadMoreEnabled(false)
            ra.setOnRefreshListener {
                val cb = onHeaderRefresh
                if (cb == null) {
                    ra.finishRefresh(true)
                    return@setOnRefreshListener
                }
                owner.lifecycleScope.launch {
                    val ok = runCatching { cb.invoke() }.isSuccess
                    ra.finishRefresh(ok)
                }
            }
        }

        return RvPageController(
            recyclerView = rv,
            concatAdapter = concat,
            entries = entries,
            pagingController = null,
            pagingTag = null,
            refreshAdapter = refreshAdapter
        )
    }

    private fun updateStaticPageState(handler: PageStateHandler, concat: ConcatAdapter) {
        if (concat.itemCount == 0) handler.showEmpty(emptyTextProvider?.invoke())
        else handler.showContent()
    }

    private fun configSpanSize(lm: GridLayoutManager, concat: ConcatAdapter) {
        val spanCount = lm.spanCount
        val userLookup = lm.spanSizeLookup
        val defByAdapter: Map<RecyclerView.Adapter<*>, SectionDef> =
            sectionDefs.associateBy { it.concatAdapter }
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                var offset = 0
                var inner: RecyclerView.Adapter<out RecyclerView.ViewHolder>? = null
                var localPos = position
                for (a in concat.adapters) {
                    val count = a.itemCount
                    if (position < offset + count) {
                        inner = a; localPos = position - offset; break
                    }
                    offset += count
                }
                inner ?: return 1
                val def = defByAdapter[inner]
                // per-item span 优先;否则回退 section 级 spanFull;最后回退用户 lookup
                def?.spanSizeFor?.let { provider ->
                    return provider(localPos, spanCount).coerceIn(1, spanCount)
                }
                if (def?.spanFull == true) return spanCount
                return userLookup.getSpanSize(localPos)
            }
        }
    }

    private fun configStaggered(rv: RecyclerView, concat: ConcatAdapter) {
        val fullSpanAdapters = sectionDefs.filter { it.spanFull }.map { it.concatAdapter }.toSet()
        if (fullSpanAdapters.isEmpty()) return
        rv.addOnChildAttachStateChangeListener(object :
            RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                val pos = rv.getChildAdapterPosition(view)
                if (pos == RecyclerView.NO_POSITION) return
                var offset = 0
                var inner: RecyclerView.Adapter<out RecyclerView.ViewHolder>? = null
                for (a in concat.adapters) {
                    val count = a.itemCount
                    if (pos < offset + count) {
                        inner = a; break
                    }
                    offset += count
                }
                if (inner == null || inner !in fullSpanAdapters) return
                val lp = view.layoutParams as? StaggeredGridLayoutManager.LayoutParams ?: return
                if (!lp.isFullSpan) {
                    lp.isFullSpan = true
                    view.layoutParams = lp
                }
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })
    }

    // ───── DSL Scope ─────

    /** `sections { }` block 的接收者,提供六种 section 工厂。 */
    class SectionsScope internal constructor(private val defs: MutableList<SectionDef>) {

        private var autoTagIndex = 0
        private fun nextTag(): String = "section_${autoTagIndex++}"

        /** 单格 section([SingleItemBindingAdapter] 承载)。 */
        fun <T : Any, VB : ViewBinding> single(
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            tag: String? = null,
            block: SingleBuilder<T, VB>.() -> Unit
        ): SingleSectionHandle<T> {
            val builder = SingleBuilder<T, VB>(inflate).apply(block)
            val adapter = builder.build()
            val visBlock = builder.visibilityBlock
            val visDispatch: ((Int, Boolean) -> Unit)? = visBlock?.let { b ->
                { _: Int, visible: Boolean ->
                    adapter.currentData()?.let { d -> b(d, visible) }
                }
            }
            val stickyPred: ((Int) -> Boolean)? =
                if (builder.stickyHeader) { pos -> pos == 0 } else null
            addInternal(
                adapter, tag, builder.spanFull,
                spanSizeFor = null,
                visibilityThreshold = builder.visibilityThreshold,
                visibilityDispatch = visDispatch,
                isStickyHeaderAt = stickyPred
            )
            return SingleSectionHandle(adapter)
        }

        /**
         * 单布局静态列表 section([BaseListAdapter] 承载)。
         *
         * ⚠ `diff` 与 `keyOf { }` 二选一必须给一个,否则 [start] 抛错。
         */
        fun <T : Any, VB : ViewBinding> list(
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            diff: DiffUtil.ItemCallback<T>? = null,
            tag: String? = null,
            block: ListBuilder<T, VB>.() -> Unit
        ): ListSectionHandle<T> {
            val builder = ListBuilder<T, VB>(inflate).apply(block)
            val effectiveDiff = diff
                ?: builder.keyOfExtractor?.let { autoDiff(it) }
                ?: error("list section: 工厂 diff 与 builder.keyOf { } 至少提供一个")
            val adapter = builder.build(effectiveDiff)
            val visBlock = builder.visibilityBlock
            val visDispatch: ((Int, Boolean) -> Unit)? = visBlock?.let { b ->
                { pos: Int, visible: Boolean ->
                    adapter.getItemOrNull(pos)?.let { item -> b(item, pos, visible) }
                }
            }
            val spanProvider = builder.spanSizeProvider
            val spanFor: ((Int, Int) -> Int)? = spanProvider?.let { p ->
                { pos: Int, total: Int ->
                    val item = adapter.getItemOrNull(pos)
                    if (item != null) p(item, pos, total) else 1
                }
            }
            val stickyPredicate = builder.stickyHeaderPredicate
            val stickyPred: ((Int) -> Boolean)? = when {
                stickyPredicate != null -> { pos ->
                    val item = adapter.getItemOrNull(pos)
                    item != null && stickyPredicate(item, pos)
                }

                builder.stickyHeader -> { pos -> pos == 0 }
                else -> null
            }
            addInternal(
                adapter, tag, builder.spanFull,
                spanSizeFor = spanFor,
                visibilityThreshold = builder.visibilityThreshold,
                visibilityDispatch = visDispatch,
                isStickyHeaderAt = stickyPred
            )
            return ListSectionHandle(adapter)
        }

        /**
         * 多布局静态列表 section([BaseMultiListAdapter] 承载)。
         *
         * ⚠ `diff` 与 `keyOf { }` 二选一必须给一个。
         */
        fun <T : Any> multiList(
            diff: DiffUtil.ItemCallback<T>? = null,
            tag: String? = null,
            block: MultiListBuilder<T>.() -> Unit
        ): MultiListSectionHandle<T> {
            val builder = MultiListBuilder<T>().apply(block)
            val effectiveDiff = diff
                ?: builder.keyOfExtractor?.let { autoDiff(it) }
                ?: error("multiList section: 工厂 diff 与 builder.keyOf { } 至少提供一个")
            val adapter = builder.build(effectiveDiff)
            val visBlock = builder.visibilityBlock
            val visDispatch: ((Int, Boolean) -> Unit)? = visBlock?.let { b ->
                { pos: Int, visible: Boolean ->
                    adapter.getItemOrNull(pos)?.let { item -> b(item, pos, visible) }
                }
            }
            val spanProvider = builder.spanSizeProvider
            val spanFor: ((Int, Int) -> Int)? = spanProvider?.let { p ->
                { pos: Int, total: Int ->
                    val item = adapter.getItemOrNull(pos)
                    if (item != null) p(item, pos, total) else 1
                }
            }
            val stickyPredicate = builder.stickyHeaderPredicate
            val stickyPred: ((Int) -> Boolean)? = when {
                stickyPredicate != null -> { pos ->
                    val item = adapter.getItemOrNull(pos)
                    item != null && stickyPredicate(item, pos)
                }

                builder.stickyHeader -> { pos -> pos == 0 }
                else -> null
            }
            addInternal(
                adapter, tag, builder.spanFull,
                spanSizeFor = spanFor,
                visibilityThreshold = builder.visibilityThreshold,
                visibilityDispatch = visDispatch,
                isStickyHeaderAt = stickyPred
            )
            return MultiListSectionHandle(adapter)
        }

        /**
         * 单布局分页 section([BasePagingAdapter] 承载,委托 [PagingHelper])。
         *
         * `diff` 可省;省则按 `keyOf { }` 或 `patcher.keyOf` 自动合成。
         */
        fun <T : Any, VB : ViewBinding> pagingList(
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            diff: DiffUtil.ItemCallback<T>? = null,
            tag: String? = null,
            block: PagingListBuilder<T, VB>.() -> Unit
        ): PagingSectionHandle<T> {
            val builder = PagingListBuilder<T, VB>(inflate).apply(block)
            val effectiveDiff = diff
                ?: effectivePagingKeyOf(builder)?.let { autoDiff(it) }
                ?: error("pagingList section: 工厂 diff 与 builder.keyOf { } / .patcher(...) 至少提供一个")
            val handle = PagingSectionHandle<T>()
            addPagingInternal(
                builder.buildAdapter(effectiveDiff),
                builder.flow!!,
                tag,
                builder,
                handle
            )
            return handle
        }

        /** 多布局分页 section([BaseMultiPagingAdapter] 承载)。`diff` 同 [pagingList] 可省。 */
        fun <T : Any> pagingMultiList(
            diff: DiffUtil.ItemCallback<T>? = null,
            tag: String? = null,
            block: PagingMultiListBuilder<T>.() -> Unit
        ): PagingSectionHandle<T> {
            val builder = PagingMultiListBuilder<T>().apply(block)
            val effectiveDiff = diff
                ?: effectivePagingKeyOf(builder)?.let { autoDiff(it) }
                ?: error("pagingMultiList section: 工厂 diff 与 builder.keyOf { } / .patcher(...) 至少提供一个")
            val handle = PagingSectionHandle<T>()
            addPagingInternal(
                builder.buildAdapter(effectiveDiff),
                builder.flow!!,
                tag,
                builder,
                handle
            )
            return handle
        }

        private fun <T : Any> effectivePagingKeyOf(cfg: PagingCommonConfig<T>): ((T) -> Any)? =
            cfg.keyOf ?: cfg.patcher?.keyOf

        /**
         * 直接挂业务自家 Adapter。
         * @param isStickyHeaderAt 谓词;null = 不参与 sticky;返回 true 的 localPos 会顶顶
         */
        fun custom(
            tag: String? = null,
            adapter: RecyclerView.Adapter<*>,
            spanFull: Boolean = true,
            isStickyHeaderAt: ((localPos: Int) -> Boolean)? = null
        ): CustomSectionHandle {
            addInternal(adapter, tag, spanFull, isStickyHeaderAt = isStickyHeaderAt)
            return CustomSectionHandle(adapter)
        }

        /**
         * 横向 carousel section:对外占整页一行,内部一个横向 RecyclerView。
         *
         * ⚠ `diff` 与 `keyOf { }` 二选一必须给一个。
         *
         * ```
         * carousel<TopicCard, ItemTopicBinding>(ItemTopicBinding::inflate) {
         *     keyOf { it.id }
         *     data = vm.topics
         *     heightDp = 120; itemSpacingDp = 8
         *     snap = CarouselSnap.LINEAR
         *     onBind { b, item, _ -> b.tv.text = item.title }
         *     onItemClick(throttleMs = 500, keyOf = { it.id }) { _, item, _ -> nav(item.url) }
         * }
         * ```
         */
        fun <T : Any, VB : ViewBinding> carousel(
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            diff: DiffUtil.ItemCallback<T>? = null,
            tag: String? = null,
            block: CarouselBuilder<T, VB>.() -> Unit
        ): CarouselSectionHandle<T> {
            val builder = CarouselBuilder<T, VB>(inflate).apply(block)
            val effectiveDiff = diff
                ?: builder.keyOfExtractor?.let { autoDiff(it) }
                ?: error("carousel section: 工厂 diff 与 builder.keyOf { } 至少提供一个")
            val carouselAdapter = builder.build(effectiveDiff)
            // carousel 整体作为单 item section;曝光以"carousel 整行"为粒度,业务一般也是这么算的
            val visBlock = builder.visibilityBlock
            val visDispatch: ((Int, Boolean) -> Unit)? = visBlock?.let { b ->
                { _: Int, visible: Boolean -> b(visible) }
            }
            addInternal(
                carouselAdapter, tag, builder.spanFull,
                spanSizeFor = null,
                visibilityThreshold = builder.visibilityThreshold,
                visibilityDispatch = visDispatch
            )
            return CarouselSectionHandle(carouselAdapter)
        }

        private fun addInternal(
            adapter: RecyclerView.Adapter<*>,
            tag: String?,
            spanFull: Boolean,
            spanSizeFor: ((localPos: Int, totalSpan: Int) -> Int)? = null,
            visibilityThreshold: Int = 50,
            visibilityDispatch: ((localPos: Int, visible: Boolean) -> Unit)? = null,
            isStickyHeaderAt: ((localPos: Int) -> Boolean)? = null
        ) {
            val def = SectionDef.Static(tag ?: nextTag(), adapter, spanFull)
            @Suppress("UNCHECKED_CAST")
            def.concatAdapter =
                GatingAdapter(adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>)
            def.spanSizeFor = spanSizeFor
            def.visibilityThresholdPercent = visibilityThreshold
            def.visibilityDispatch = visibilityDispatch
            def.isStickyHeaderAt = isStickyHeaderAt
            defs.add(def)
        }

        private fun <T : Any> addPagingInternal(
            adapter: PagingDataAdapter<T, *>,
            flow: Flow<PagingData<T>>,
            tag: String?,
            cfg: PagingCommonConfig<T>,
            handle: PagingSectionHandle<T>
        ) {
            @Suppress("UNCHECKED_CAST")
            val def = SectionDef.Paging(
                tag = tag ?: nextTag(),
                pagingAdapter = adapter as PagingDataAdapter<Any, *>,
                flow = flow as Flow<PagingData<Any>>,
                spanFull = cfg.spanFull,
                keyOf = cfg.keyOf?.let { fn -> { item: Any -> fn(item as T) } },
                onLoadError = cfg.onLoadError,
                distinctErrorToast = cfg.distinctErrorToast,
                onEmpty = cfg.onEmpty,
                loadStateFooterFactory = cfg.loadStateFooterFactory,
                chatMode = cfg.chatMode,
                patcher = cfg.patcher as PagingPatcher<Any, Any>?,
                clearPatchesOnRefresh = cfg.clearPatchesOnRefresh,
                dragSortConfig = cfg.dragSortConfig as PagingCommonConfig.DragSortConfig<Any>?,
                dragSortFactory = cfg.dragSortFactory as ((pa: PagingDataAdapter<Any, *>) -> ItemTouchHelper)?,
                handle = handle as PagingSectionHandle<Any>
            )
            val visBlock = cfg.visibilityBlock
            if (visBlock != null) {
                def.visibilityThresholdPercent = cfg.visibilityThreshold
                def.visibilityDispatch = { pos: Int, visible: Boolean ->
                    @Suppress("UNCHECKED_CAST")
                    val item = adapter.peek(pos) as T?
                    if (item != null) visBlock(item, pos, visible)
                }
            }
            val spanProvider = cfg.spanSizeProvider
            if (spanProvider != null) {
                def.spanSizeFor = { pos: Int, total: Int ->
                    @Suppress("UNCHECKED_CAST")
                    val item = adapter.peek(pos) as T?
                    if (item != null) spanProvider(item, pos, total) else 1
                }
            }
            val stickyPredicate = cfg.stickyHeaderPredicate
            def.isStickyHeaderAt = when {
                stickyPredicate != null -> { pos: Int ->
                    @Suppress("UNCHECKED_CAST")
                    val item = adapter.peek(pos) as T?
                    item != null && stickyPredicate(item, pos)
                }

                cfg.stickyHeader -> { pos: Int -> pos == 0 }
                else -> null
            }
            defs.add(def)
        }
    }

    // ───── Section builders ─────

    /**
     * 单格 section builder。
     *
     * @property data 要显示的数据;null 时该 section 不占格
     * @property spanFull GridLayoutManager 下是否跨整行,默认 true
     */
    class SingleBuilder<T : Any, VB : ViewBinding> internal constructor(
        private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB
    ) {
        var data: T? = null
        var spanFull: Boolean = true

        /** 是否作为 sticky header(滚动时顶顶);需要业务在 builder.start 时启用 LinearLayoutManager。 */
        var stickyHeader: Boolean = false
        private var onBind: ((VB, T) -> Unit)? = null
        private var onBindPayloads: ((VB, T, MutableList<Any>) -> Unit)? = null
        private var onViewHolderCreated: ((SingleItemBindingAdapter.VH<VB>, VB) -> Unit)? = null
        private var itemClickThrottle: Long = 0L
        private var onItemClick: ((View, T) -> Unit)? = null
        private var onItemLongClick: ((View, T) -> Boolean)? = null
        private var childIds: IntArray = IntArray(0)
        private var childLongIds: IntArray = IntArray(0)
        private var itemChildClickThrottle: Long = 0L
        private var onItemChildClick: ((View, T) -> Unit)? = null
        private var onItemChildLongClick: ((View, T) -> Boolean)? = null
        internal var visibilityThreshold: Int = 50
        internal var visibilityBlock: ((data: T, visible: Boolean) -> Unit)? = null

        /**
         * 单格曝光回调:可见比例跨过 [thresholdPercent] 阈值时触发。
         * @param thresholdPercent 1..100
         */
        fun onVisibilityChanged(
            thresholdPercent: Int = 50,
            block: (data: T, visible: Boolean) -> Unit
        ) {
            visibilityThreshold = thresholdPercent; visibilityBlock = block
        }

        fun onBind(block: (binding: VB, data: T) -> Unit) {
            onBind = block
        }

        fun onBindPayloads(block: (binding: VB, data: T, payloads: MutableList<Any>) -> Unit) {
            onBindPayloads = block
        }

        fun onViewHolderCreated(block: (holder: SingleItemBindingAdapter.VH<VB>, binding: VB) -> Unit) {
            onViewHolderCreated = block
        }

        fun onItemClick(throttleMs: Long = 0L, block: (View, T) -> Unit) {
            itemClickThrottle = throttleMs; onItemClick = block
        }

        fun onItemLongClick(block: (View, T) -> Boolean) {
            onItemLongClick = block
        }

        fun childClickIds(vararg ids: Int) {
            childIds = ids
        }

        fun childLongClickIds(vararg ids: Int) {
            childLongIds = ids
        }

        fun onItemChildClick(throttleMs: Long = 0L, block: (View, T) -> Unit) {
            itemChildClickThrottle = throttleMs; onItemChildClick = block
        }

        fun onItemChildLongClick(block: (View, T) -> Boolean) {
            onItemChildLongClick = block
        }

        internal fun build(): SingleItemBindingAdapter<T, VB> {
            val onBindCb = requireNotNull(onBind) { "single section 必须 onBind { ... }" }
            val onBindPayloadsCb = onBindPayloads
            val onCreateCb = onViewHolderCreated
            val adapter = object : SingleItemBindingAdapter<T, VB>(inflater) {
                override fun onBind(binding: VB, data: T) = onBindCb(binding, data)
                override fun onBind(binding: VB, data: T, payloads: MutableList<Any>) {
                    if (onBindPayloadsCb != null) onBindPayloadsCb(binding, data, payloads)
                    else super.onBind(binding, data, payloads)
                }

                override fun onViewHolderCreated(holder: VH<VB>, binding: VB) {
                    onCreateCb?.invoke(holder, binding)
                }
            }
            onItemClick?.let { adapter.setOnItemClickListener(itemClickThrottle, it) }
            onItemLongClick?.let { adapter.setOnItemLongClickListener(it) }
            if (childIds.isNotEmpty()) {
                adapter.addChildClickViewIds(*childIds)
                onItemChildClick?.let {
                    adapter.setOnItemChildClickListener(
                        itemChildClickThrottle,
                        it
                    )
                }
            }
            if (childLongIds.isNotEmpty()) {
                adapter.addChildLongClickViewIds(*childLongIds)
                onItemChildLongClick?.let { adapter.setOnItemChildLongClickListener(it) }
            }
            adapter.submit(data)
            return adapter
        }
    }

    /** 单布局静态列表 builder。 */
    class ListBuilder<T : Any, VB : ViewBinding> internal constructor(
        private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB
    ) {
        var data: List<T> = emptyList()
        var spanFull: Boolean = true

        /** true = section 首个 item 作为 sticky header(等价 `isStickyHeader { _, pos -> pos == 0 }`)。 */
        var stickyHeader: Boolean = false

        internal var stickyHeaderPredicate: ((item: T, localPos: Int) -> Boolean)? = null

        /**
         * 自定义 sticky header 判定:任意满足谓词的 item 在滚动时顶顶,适合"每个日期分组首条都吸顶"
         * 这种场景。设了谓词后 [stickyHeader] 字段会被忽略。
         */
        fun isStickyHeader(predicate: (item: T, localPos: Int) -> Boolean) {
            stickyHeaderPredicate = predicate
        }

        internal var keyOfExtractor: ((T) -> Any)? = null

        /** 主键提取器,工厂未传 `diff` 时按 `keyOf` + `equals` 自动合成 diff。 */
        fun keyOf(extractor: (T) -> Any) {
            keyOfExtractor = extractor
        }

        private var onBind: ((VB, T, Int) -> Unit)? = null
        private var onBindPayloads: ((VB, T, Int, MutableList<Any>) -> Unit)? = null
        private var onViewHolderCreated: ((BaseListAdapter.BindingHolder<VB>, VB) -> Unit)? = null
        private var itemClickThrottle: Long = 0L
        private var itemClickKeyOf: ((T) -> Any)? = null
        private var onItemClick: ((View, T, Int) -> Unit)? = null
        private var onItemLongClick: ((View, T, Int) -> Boolean)? = null
        private var childIds: IntArray = IntArray(0)
        private var childLongIds: IntArray = IntArray(0)
        private var itemChildClickThrottle: Long = 0L
        private var itemChildClickKeyOf: ((T) -> Any)? = null
        private var onItemChildClick: ((View, T, Int) -> Unit)? = null
        private var onItemChildLongClick: ((View, T, Int) -> Boolean)? = null
        internal var visibilityThreshold: Int = 50
        internal var visibilityBlock: ((item: T, position: Int, visible: Boolean) -> Unit)? = null
        internal var spanSizeProvider: ((item: T, localPos: Int, totalSpan: Int) -> Int)? = null

        /**
         * 单 item 曝光回调:可见比例跨过 [thresholdPercent] 阈值时触发。
         * @param thresholdPercent 1..100
         */
        fun onItemVisibilityChanged(
            thresholdPercent: Int = 50,
            block: (item: T, position: Int, visible: Boolean) -> Unit
        ) {
            visibilityThreshold = thresholdPercent; visibilityBlock = block
        }

        /** per-item 跨列数;优先级高于 [spanFull],仅 GridLayoutManager 生效。 */
        fun spanSize(provider: (item: T, localPos: Int, totalSpan: Int) -> Int) {
            spanSizeProvider = provider
        }

        fun onBind(block: (binding: VB, item: T, position: Int) -> Unit) {
            onBind = block
        }

        fun onBindPayloads(block: (binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit) {
            onBindPayloads = block
        }

        fun onViewHolderCreated(block: (holder: BaseListAdapter.BindingHolder<VB>, binding: VB) -> Unit) {
            onViewHolderCreated = block
        }

        fun onItemClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemClickThrottle = throttleMs; itemClickKeyOf = keyOf; onItemClick = block
        }

        fun onItemLongClick(block: (View, T, Int) -> Boolean) {
            onItemLongClick = block
        }

        fun childClickIds(vararg ids: Int) {
            childIds = ids
        }

        fun childLongClickIds(vararg ids: Int) {
            childLongIds = ids
        }

        fun onItemChildClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemChildClickThrottle = throttleMs; itemChildClickKeyOf = keyOf; onItemChildClick =
                block
        }

        fun onItemChildLongClick(block: (View, T, Int) -> Boolean) {
            onItemChildLongClick = block
        }

        internal fun build(diff: DiffUtil.ItemCallback<T>): BaseListAdapter<T, VB> {
            val onBindCb = requireNotNull(onBind) { "list section 必须 onBind { ... }" }
            val onBindPayloadsCb = onBindPayloads
            val onCreateCb = onViewHolderCreated
            val adapter = object : BaseListAdapter<T, VB>(diff, inflater) {
                override fun onBind(binding: VB, item: T, position: Int) =
                    onBindCb(binding, item, position)

                override fun onBind(
                    binding: VB,
                    item: T,
                    position: Int,
                    payloads: MutableList<Any>
                ) {
                    if (onBindPayloadsCb != null) onBindPayloadsCb(
                        binding,
                        item,
                        position,
                        payloads
                    )
                    else super.onBind(binding, item, position, payloads)
                }

                override fun onViewHolderCreated(holder: BindingHolder<VB>, binding: VB) {
                    onCreateCb?.invoke(holder, binding)
                }
            }
            onItemClick?.let {
                adapter.setOnItemClickListener(
                    itemClickThrottle,
                    itemClickKeyOf,
                    it
                )
            }
            onItemLongClick?.let { adapter.setOnItemLongClickListener(it) }
            if (childIds.isNotEmpty()) {
                adapter.addChildClickViewIds(*childIds)
                onItemChildClick?.let {
                    adapter.setOnItemChildClickListener(
                        itemChildClickThrottle,
                        itemChildClickKeyOf,
                        it
                    )
                }
            }
            if (childLongIds.isNotEmpty()) {
                adapter.addChildLongClickViewIds(*childLongIds)
                onItemChildLongClick?.let { adapter.setOnItemChildLongClickListener(it) }
            }
            adapter.submit(data)
            return adapter
        }
    }

    /** 多布局静态列表 builder。 */
    class MultiListBuilder<T : Any> internal constructor() {
        var data: List<T> = emptyList()
        var spanFull: Boolean = true

        /** 详见 [ListBuilder.stickyHeader]。 */
        var stickyHeader: Boolean = false

        internal var stickyHeaderPredicate: ((item: T, localPos: Int) -> Boolean)? = null

        /** 详见 [ListBuilder.isStickyHeader]。 */
        fun isStickyHeader(predicate: (item: T, localPos: Int) -> Boolean) {
            stickyHeaderPredicate = predicate
        }

        internal var keyOfExtractor: ((T) -> Any)? = null
        private val typeRegistrations =
            mutableListOf<(BaseMultiListAdapter<T>) -> Unit>()

        /** 详见 [ListBuilder.keyOf] */
        fun keyOf(extractor: (T) -> Any) {
            keyOfExtractor = extractor
        }


        private var itemClickThrottle: Long = 0L
        private var itemClickKeyOf: ((T) -> Any)? = null
        private var onItemClick: ((View, T, Int) -> Unit)? = null
        private var onItemLongClick: ((View, T, Int) -> Boolean)? = null
        private var childIds: IntArray = IntArray(0)
        private var childLongIds: IntArray = IntArray(0)
        private var itemChildClickThrottle: Long = 0L
        private var itemChildClickKeyOf: ((T) -> Any)? = null
        private var onItemChildClick: ((View, T, Int) -> Unit)? = null
        private var onItemChildLongClick: ((View, T, Int) -> Boolean)? = null
        internal var visibilityThreshold: Int = 50
        internal var visibilityBlock: ((item: T, position: Int, visible: Boolean) -> Unit)? = null
        internal var spanSizeProvider: ((item: T, localPos: Int, totalSpan: Int) -> Int)? = null

        /** 详见 [ListBuilder.onItemVisibilityChanged] */
        fun onItemVisibilityChanged(
            thresholdPercent: Int = 50,
            block: (item: T, position: Int, visible: Boolean) -> Unit
        ) {
            visibilityThreshold = thresholdPercent; visibilityBlock = block
        }

        /** 详见 [ListBuilder.spanSize] */
        fun spanSize(provider: (item: T, localPos: Int, totalSpan: Int) -> Int) {
            spanSizeProvider = provider
        }

        /** typeValue 版：要求 T 实现 [MultiTypeItem] */
        fun <VB : ViewBinding> addType(
            typeValue: Int,
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            onCreate: ((binding: VB) -> Unit)? = null,
            onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
            onBind: (binding: VB, item: T, position: Int) -> Unit
        ) {
            typeRegistrations.add { adapter ->
                adapter.addType(typeValue, inflate, onCreate, onBindPayloads, onBind)
            }
        }

        /** isMine 谓词版 */
        fun <VB : ViewBinding> addType(
            isMine: (T) -> Boolean,
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            viewType: Int = -1,
            onCreate: ((binding: VB) -> Unit)? = null,
            onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
            onBind: (binding: VB, item: T, position: Int) -> Unit
        ) {
            typeRegistrations.add { adapter ->
                adapter.addType(isMine, inflate, viewType, onCreate, onBindPayloads, onBind)
            }
        }

        fun onItemClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemClickThrottle = throttleMs; itemClickKeyOf = keyOf; onItemClick = block
        }

        fun onItemLongClick(block: (View, T, Int) -> Boolean) {
            onItemLongClick = block
        }

        fun childClickIds(vararg ids: Int) {
            childIds = ids
        }

        fun childLongClickIds(vararg ids: Int) {
            childLongIds = ids
        }

        fun onItemChildClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemChildClickThrottle = throttleMs; itemChildClickKeyOf = keyOf; onItemChildClick =
                block
        }

        fun onItemChildLongClick(block: (View, T, Int) -> Boolean) {
            onItemChildLongClick = block
        }

        internal fun build(diff: DiffUtil.ItemCallback<T>): BaseMultiListAdapter<T> {
            require(typeRegistrations.isNotEmpty()) { "multiList section 至少 addType<...> { ... } 一次" }
            val adapter = object : BaseMultiListAdapter<T>(diff) {}
            typeRegistrations.forEach { it(adapter) }
            onItemClick?.let {
                adapter.setOnItemClickListener(
                    itemClickThrottle,
                    itemClickKeyOf,
                    it
                )
            }
            onItemLongClick?.let { adapter.setOnItemLongClickListener(it) }
            if (childIds.isNotEmpty()) {
                adapter.addChildClickViewIds(*childIds)
                onItemChildClick?.let {
                    adapter.setOnItemChildClickListener(
                        itemChildClickThrottle,
                        itemChildClickKeyOf,
                        it
                    )
                }
            }
            if (childLongIds.isNotEmpty()) {
                adapter.addChildLongClickViewIds(*childLongIds)
                onItemChildLongClick?.let { adapter.setOnItemChildLongClickListener(it) }
            }
            adapter.submit(data)
            return adapter
        }
    }

    /** [PagingListBuilder] / [PagingMultiListBuilder] 的共享配置。 */
    abstract class PagingCommonConfig<T : Any> {
        var flow: Flow<PagingData<T>>? = null
        var spanFull: Boolean = true
        var chatMode: Boolean = false

        /** 详见 [ListBuilder.stickyHeader]。 */
        var stickyHeader: Boolean = false

        internal var stickyHeaderPredicate: ((item: T, localPos: Int) -> Boolean)? = null

        /** 详见 [ListBuilder.isStickyHeader]。 */
        fun isStickyHeader(predicate: (item: T, localPos: Int) -> Boolean) {
            stickyHeaderPredicate = predicate
        }

        internal var keyOf: ((T) -> Any)? = null
        internal var onLoadError: ((Throwable) -> Unit)? = null
        internal var distinctErrorToast: Boolean = true
        internal var onEmpty: ((Boolean) -> Unit)? = null
        internal var loadStateFooterFactory: ((retry: () -> Unit) -> LoadStateAdapter<*>)? = null
        internal var patcher: PagingPatcher<Any, T>? = null
        internal var clearPatchesOnRefresh: Boolean = true
        internal var dragSortConfig: DragSortConfig<T>? = null
        internal var dragSortFactory: ((pa: PagingDataAdapter<T, *>) -> ItemTouchHelper)? = null
        internal var visibilityThreshold: Int = 50
        internal var visibilityBlock: ((item: T, position: Int, visible: Boolean) -> Unit)? = null
        internal var spanSizeProvider: ((item: T, localPos: Int, totalSpan: Int) -> Int)? = null

        /** 详见 [ListBuilder.onItemVisibilityChanged] */
        fun onItemVisibilityChanged(
            thresholdPercent: Int = 50,
            block: (item: T, position: Int, visible: Boolean) -> Unit
        ) {
            visibilityThreshold = thresholdPercent; visibilityBlock = block
        }

        /** 详见 [ListBuilder.spanSize] */
        fun spanSize(provider: (item: T, localPos: Int, totalSpan: Int) -> Int) {
            spanSizeProvider = provider
        }

        fun keyOf(extractor: (T) -> Any) {
            keyOf = extractor
        }

        /** VM 持有的 [PagingPatcher];不传时框架按 [keyOf] 自建。 */
        fun patcher(patcher: PagingPatcher<Any, T>) {
            this.patcher = patcher
        }

        /** 下拉刷新时是否清空所有本地补丁,默认 true。 */
        fun clearPatchesOnRefresh(clear: Boolean) {
            clearPatchesOnRefresh = clear
        }

        /** 启用拖动排序,见 [PagingHelper.enableDragSort]。 */
        fun enableDragSort(
            longPressEnabled: Boolean = true,
            vibrateOnDragStart: Boolean = true,
            canDrag: ((item: T, localPos: Int) -> Boolean)? = null,
            onMoved: (fromKey: Any, toKey: Any, fromLocal: Int, toLocal: Int) -> Unit
        ) {
            dragSortConfig = DragSortConfig(longPressEnabled, vibrateOnDragStart, canDrag, onMoved)
        }

        /** 完全自定义拖动行为;与 [enableDragSort] 互斥。 */
        fun dragSort(factory: (pa: PagingDataAdapter<T, *>) -> ItemTouchHelper) {
            dragSortFactory = factory
        }

        fun onLoadError(distinct: Boolean = true, block: (Throwable) -> Unit) {
            distinctErrorToast = distinct; onLoadError = block
        }

        fun onEmpty(block: (Boolean) -> Unit) {
            onEmpty = block
        }

        fun loadStateFooter(factory: (retry: () -> Unit) -> LoadStateAdapter<*>) {
            loadStateFooterFactory = factory
        }

        class DragSortConfig<T : Any>(
            val longPressEnabled: Boolean,
            val vibrateOnDragStart: Boolean,
            val canDrag: ((item: T, localPos: Int) -> Boolean)?,
            val onMoved: (fromKey: Any, toKey: Any, fromLocal: Int, toLocal: Int) -> Unit
        )
    }

    /** 单布局分页 builder。 */
    class PagingListBuilder<T : Any, VB : ViewBinding> internal constructor(
        private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB
    ) : PagingCommonConfig<T>() {
        private var onBind: ((VB, T, Int) -> Unit)? = null
        private var onBindPayloads: ((VB, T, Int, MutableList<Any>) -> Unit)? = null
        private var onViewHolderCreated: ((BasePagingAdapter.BindingHolder<VB>, VB) -> Unit)? = null
        private var itemClickThrottle: Long = 0L
        private var itemClickKeyOf: ((T) -> Any)? = null
        private var onItemClick: ((View, T, Int) -> Unit)? = null
        private var onItemLongClick: ((View, T, Int) -> Boolean)? = null
        private var childIds: IntArray = IntArray(0)
        private var childLongIds: IntArray = IntArray(0)
        private var itemChildClickThrottle: Long = 0L
        private var itemChildClickKeyOf: ((T) -> Any)? = null
        private var onItemChildClick: ((View, T, Int) -> Unit)? = null
        private var onItemChildLongClick: ((View, T, Int) -> Boolean)? = null

        fun onBind(block: (binding: VB, item: T, position: Int) -> Unit) {
            onBind = block
        }

        fun onBindPayloads(block: (binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit) {
            onBindPayloads = block
        }

        fun onViewHolderCreated(block: (holder: BasePagingAdapter.BindingHolder<VB>, binding: VB) -> Unit) {
            onViewHolderCreated = block
        }

        fun onItemClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemClickThrottle = throttleMs; itemClickKeyOf = keyOf; onItemClick = block
        }

        fun onItemLongClick(block: (View, T, Int) -> Boolean) {
            onItemLongClick = block
        }

        fun childClickIds(vararg ids: Int) {
            childIds = ids
        }

        fun childLongClickIds(vararg ids: Int) {
            childLongIds = ids
        }

        fun onItemChildClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemChildClickThrottle = throttleMs; itemChildClickKeyOf = keyOf; onItemChildClick =
                block
        }

        fun onItemChildLongClick(block: (View, T, Int) -> Boolean) {
            onItemChildLongClick = block
        }

        internal fun buildAdapter(diff: DiffUtil.ItemCallback<T>): PagingDataAdapter<T, *> {
            require(flow != null) { "pagingList section 必须设置 flow" }
            val onBindCb = requireNotNull(onBind) { "pagingList section 必须 onBind { ... }" }
            val onBindPayloadsCb = onBindPayloads
            val onCreateCb = onViewHolderCreated
            val adapter = object : BasePagingAdapter<T, VB>(diff, inflater) {
                override fun onBind(binding: VB, item: T, position: Int) =
                    onBindCb(binding, item, position)

                override fun onBind(
                    binding: VB,
                    item: T,
                    position: Int,
                    payloads: MutableList<Any>
                ) {
                    if (onBindPayloadsCb != null) onBindPayloadsCb(
                        binding,
                        item,
                        position,
                        payloads
                    )
                    else super.onBind(binding, item, position, payloads)
                }

                override fun onViewHolderCreated(holder: BindingHolder<VB>, binding: VB) {
                    onCreateCb?.invoke(holder, binding)
                }
            }
            onItemClick?.let {
                adapter.setOnItemClickListener(
                    itemClickThrottle,
                    itemClickKeyOf,
                    it
                )
            }
            onItemLongClick?.let { adapter.setOnItemLongClickListener(it) }
            if (childIds.isNotEmpty()) {
                adapter.addChildClickViewIds(*childIds)
                onItemChildClick?.let {
                    adapter.setOnItemChildClickListener(
                        itemChildClickThrottle,
                        itemChildClickKeyOf,
                        it
                    )
                }
            }
            if (childLongIds.isNotEmpty()) {
                adapter.addChildLongClickViewIds(*childLongIds)
                onItemChildLongClick?.let { adapter.setOnItemChildLongClickListener(it) }
            }
            return adapter
        }
    }

    /** 多布局分页 builder。 */
    class PagingMultiListBuilder<T : Any> internal constructor() : PagingCommonConfig<T>() {
        private val typeRegistrations = mutableListOf<(BaseMultiPagingAdapter<T>) -> Unit>()

        private var itemClickThrottle: Long = 0L
        private var itemClickKeyOf: ((T) -> Any)? = null
        private var onItemClick: ((View, T, Int) -> Unit)? = null
        private var onItemLongClick: ((View, T, Int) -> Boolean)? = null
        private var childIds: IntArray = IntArray(0)
        private var childLongIds: IntArray = IntArray(0)
        private var itemChildClickThrottle: Long = 0L
        private var itemChildClickKeyOf: ((T) -> Any)? = null
        private var onItemChildClick: ((View, T, Int) -> Unit)? = null
        private var onItemChildLongClick: ((View, T, Int) -> Boolean)? = null

        fun <VB : ViewBinding> addType(
            typeValue: Int,
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            onCreate: ((binding: VB) -> Unit)? = null,
            onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
            onBind: (binding: VB, item: T, position: Int) -> Unit
        ) {
            typeRegistrations.add { adapter ->
                adapter.addType(typeValue, inflate, onCreate, onBindPayloads, onBind)
            }
        }

        fun <VB : ViewBinding> addType(
            isMine: (T) -> Boolean,
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            viewType: Int = -1,
            onCreate: ((binding: VB) -> Unit)? = null,
            onBindPayloads: ((binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit)? = null,
            onBind: (binding: VB, item: T, position: Int) -> Unit
        ) {
            typeRegistrations.add { adapter ->
                adapter.addType(isMine, inflate, viewType, onCreate, onBindPayloads, onBind)
            }
        }

        fun onItemClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemClickThrottle = throttleMs; itemClickKeyOf = keyOf; onItemClick = block
        }

        fun onItemLongClick(block: (View, T, Int) -> Boolean) {
            onItemLongClick = block
        }

        fun childClickIds(vararg ids: Int) {
            childIds = ids
        }

        fun childLongClickIds(vararg ids: Int) {
            childLongIds = ids
        }

        fun onItemChildClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemChildClickThrottle = throttleMs; itemChildClickKeyOf = keyOf; onItemChildClick =
                block
        }

        fun onItemChildLongClick(block: (View, T, Int) -> Boolean) {
            onItemChildLongClick = block
        }

        internal fun buildAdapter(diff: DiffUtil.ItemCallback<T>): PagingDataAdapter<T, *> {
            require(flow != null) { "pagingMultiList section 必须设置 flow" }
            require(typeRegistrations.isNotEmpty()) { "pagingMultiList section 至少 addType<...> { ... } 一次" }
            val adapter = object : BaseMultiPagingAdapter<T>(diff) {}
            typeRegistrations.forEach { it(adapter) }
            onItemClick?.let {
                adapter.setOnItemClickListener(
                    itemClickThrottle,
                    itemClickKeyOf,
                    it
                )
            }
            onItemLongClick?.let { adapter.setOnItemLongClickListener(it) }
            if (childIds.isNotEmpty()) {
                adapter.addChildClickViewIds(*childIds)
                onItemChildClick?.let {
                    adapter.setOnItemChildClickListener(
                        itemChildClickThrottle,
                        itemChildClickKeyOf,
                        it
                    )
                }
            }
            if (childLongIds.isNotEmpty()) {
                adapter.addChildLongClickViewIds(*childLongIds)
                onItemChildLongClick?.let { adapter.setOnItemChildLongClickListener(it) }
            }
            return adapter
        }
    }

    /** carousel section builder。 */
    class CarouselBuilder<T : Any, VB : ViewBinding> internal constructor(
        private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB
    ) {
        internal var keyOfExtractor: ((T) -> Any)? = null

        /** 详见 [ListBuilder.keyOf]。 */
        fun keyOf(extractor: (T) -> Any) {
            keyOfExtractor = extractor
        }

        /** 初始数据;运行时通过 [CarouselSectionHandle.submit] 更新。 */
        var data: List<T> = emptyList()

        /** 外层 GridLayoutManager 下是否跨整行;默认 true。 */
        var spanFull: Boolean = true

        /** 横向 RV 高度(dp);<=0 = wrap_content。 */
        var heightDp: Int = -1

        var paddingStartDp: Int = 0
        var paddingEndDp: Int = 0
        var paddingTopDp: Int = 0
        var paddingBottomDp: Int = 0

        /** item 间距(dp);0 = 紧贴。 */
        var itemSpacingDp: Int = 0

        /** 横向吸附模式;默认 [CarouselSnap.NONE]。 */
        var snap: CarouselSnap = CarouselSnap.NONE

        /** 跨 carousel 共享的 [RecyclerView.RecycledViewPool];不传则各自维护。 */
        var sharedPool: RecyclerView.RecycledViewPool? = null

        private var onBind: ((VB, T, Int) -> Unit)? = null
        private var onBindPayloads: ((VB, T, Int, MutableList<Any>) -> Unit)? = null
        private var onViewHolderCreated: ((BaseListAdapter.BindingHolder<VB>, VB) -> Unit)? = null
        private var itemClickThrottle: Long = 0L
        private var itemClickKeyOf: ((T) -> Any)? = null
        private var onItemClick: ((View, T, Int) -> Unit)? = null
        private var onItemLongClick: ((View, T, Int) -> Boolean)? = null
        private var childIds: IntArray = IntArray(0)
        private var childLongIds: IntArray = IntArray(0)
        private var itemChildClickThrottle: Long = 0L
        private var itemChildClickKeyOf: ((T) -> Any)? = null
        private var onItemChildClick: ((View, T, Int) -> Unit)? = null
        private var onItemChildLongClick: ((View, T, Int) -> Boolean)? = null
        internal var visibilityThreshold: Int = 50
        internal var visibilityBlock: ((visible: Boolean) -> Unit)? = null

        fun onBind(block: (binding: VB, item: T, position: Int) -> Unit) {
            onBind = block
        }

        fun onBindPayloads(block: (binding: VB, item: T, position: Int, payloads: MutableList<Any>) -> Unit) {
            onBindPayloads = block
        }

        fun onViewHolderCreated(block: (holder: BaseListAdapter.BindingHolder<VB>, binding: VB) -> Unit) {
            onViewHolderCreated = block
        }

        fun onItemClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemClickThrottle = throttleMs; itemClickKeyOf = keyOf; onItemClick = block
        }

        fun onItemLongClick(block: (View, T, Int) -> Boolean) {
            onItemLongClick = block
        }

        fun childClickIds(vararg ids: Int) {
            childIds = ids
        }

        fun childLongClickIds(vararg ids: Int) {
            childLongIds = ids
        }

        fun onItemChildClick(
            throttleMs: Long = 0L,
            keyOf: ((T) -> Any)? = null,
            block: (View, T, Int) -> Unit
        ) {
            itemChildClickThrottle = throttleMs; itemChildClickKeyOf = keyOf
            onItemChildClick = block
        }

        fun onItemChildLongClick(block: (View, T, Int) -> Boolean) {
            onItemChildLongClick = block
        }

        /** carousel 整行曝光回调,不带 item 粒度。 */
        fun onVisibilityChanged(
            thresholdPercent: Int = 50,
            block: (visible: Boolean) -> Unit
        ) {
            visibilityThreshold = thresholdPercent; visibilityBlock = block
        }

        internal fun build(diff: DiffUtil.ItemCallback<T>): CarouselAdapter<T> {
            val onBindCb = requireNotNull(onBind) { "carousel section 必须 onBind { ... }" }
            val onBindPayloadsCb = onBindPayloads
            val onCreateCb = onViewHolderCreated
            val innerAdapter = object : BaseListAdapter<T, VB>(diff, inflater) {
                override fun onBind(binding: VB, item: T, position: Int) =
                    onBindCb(binding, item, position)

                override fun onBind(
                    binding: VB,
                    item: T,
                    position: Int,
                    payloads: MutableList<Any>
                ) {
                    if (onBindPayloadsCb != null) onBindPayloadsCb(
                        binding,
                        item,
                        position,
                        payloads
                    )
                    else super.onBind(binding, item, position, payloads)
                }

                override fun onViewHolderCreated(holder: BindingHolder<VB>, binding: VB) {
                    onCreateCb?.invoke(holder, binding)
                }
            }
            onItemClick?.let {
                innerAdapter.setOnItemClickListener(itemClickThrottle, itemClickKeyOf, it)
            }
            onItemLongClick?.let { innerAdapter.setOnItemLongClickListener(it) }
            if (childIds.isNotEmpty()) {
                innerAdapter.addChildClickViewIds(*childIds)
                onItemChildClick?.let {
                    innerAdapter.setOnItemChildClickListener(
                        itemChildClickThrottle, itemChildClickKeyOf, it
                    )
                }
            }
            if (childLongIds.isNotEmpty()) {
                innerAdapter.addChildLongClickViewIds(*childLongIds)
                onItemChildLongClick?.let { innerAdapter.setOnItemChildLongClickListener(it) }
            }
            innerAdapter.submit(data)
            return CarouselAdapter(
                inner = innerAdapter,
                heightDp = heightDp,
                paddingStartDp = paddingStartDp,
                paddingTopDp = paddingTopDp,
                paddingEndDp = paddingEndDp,
                paddingBottomDp = paddingBottomDp,
                itemSpacingDp = itemSpacingDp,
                snap = snap,
                sharedPool = sharedPool
            )
        }
    }

    /** Section 在 builder 内的中间表示,不暴露给业务。 */
    sealed class SectionDef(
        val tag: String,
        val adapter: RecyclerView.Adapter<*>,
        val spanFull: Boolean
    ) {
        internal var spanSizeFor: ((localPos: Int, totalSpan: Int) -> Int)? = null
        internal var visibilityThresholdPercent: Int = 50
        internal var visibilityDispatch: ((localPos: Int, visible: Boolean) -> Unit)? = null

        /** 真正装到 ConcatAdapter 的 adapter:static 用 [GatingAdapter] 包装 [adapter],paging 直接 = pagingAdapter。 */
        internal var concatAdapter: RecyclerView.Adapter<*> = adapter

        /**
         * 该 section 内 [localPos] 是否作为 sticky header:`null` = section 完全不参与 sticky;
         * 非空时按谓词逐项判定(简单 `stickyHeader=true` 等价 `{ pos -> pos == 0 }`)。
         */
        internal var isStickyHeaderAt: ((localPos: Int) -> Boolean)? = null

        class Static(tag: String, adapter: RecyclerView.Adapter<*>, spanFull: Boolean) :
            SectionDef(tag, adapter, spanFull)

        class Paging<T : Any>(
            tag: String,
            val pagingAdapter: PagingDataAdapter<T, *>,
            val flow: Flow<PagingData<T>>,
            spanFull: Boolean,
            val keyOf: ((T) -> Any)?,
            val onLoadError: ((Throwable) -> Unit)?,
            val distinctErrorToast: Boolean,
            val onEmpty: ((Boolean) -> Unit)?,
            val loadStateFooterFactory: ((retry: () -> Unit) -> LoadStateAdapter<*>)?,
            val chatMode: Boolean,
            val patcher: PagingPatcher<Any, T>?,
            val clearPatchesOnRefresh: Boolean,
            val dragSortConfig: PagingCommonConfig.DragSortConfig<T>?,
            val dragSortFactory: ((pa: PagingDataAdapter<T, *>) -> ItemTouchHelper)?,
            val handle: PagingSectionHandle<T>
        ) : SectionDef(tag, pagingAdapter, spanFull)
    }
}

/** `RvPage.with(owner)` 的别名。 */
typealias RvPage = RvPageBuilder
