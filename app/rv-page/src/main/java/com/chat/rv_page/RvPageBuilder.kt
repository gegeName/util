package com.chat.rv_page

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadStateAdapter
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import com.chat.pagingutil.BaseMultiPagingAdapter
import com.chat.pagingutil.BasePagingAdapter
import com.chat.pagingutil.MultiTypeItem
import com.chat.pagingutil.PageStateHandler
import com.chat.pagingutil.PagingHelper
import com.chat.pagingutil.PagingRefreshAdapter
import com.chat.pagingutil.SingleItemBindingAdapter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 页面级 DSL 入口（"所有界面用 RecyclerView 布局"的工具类）。
 *
 * 把"多 section 装配 / 静态页 RV 化 / 分页 + 状态整合 / 动态增删 section"四件事合到一处，
 * 内部按需委托给 [PagingHelper]（有分页 section 时）或自建 [ConcatAdapter]（纯静态时）。
 * 与现有 [PagingHelper] 是**叠加关系**，不替代它，只在装配层做一层薄包装。
 *
 * 入口：
 * ```
 * val page = RvPage.with(viewLifecycleOwner)
 *     .recyclerView(mBinding.rv)
 *     .refreshAdapter(SmartRefreshAdapter(mBinding.srl))   // 可选
 *     .pageState(mBinding.pageStateView)                    // 可选，复用 PageStateHandler
 *     .sections {
 *         single<Banner, ItemBannerBinding>(ItemBannerBinding::inflate, tag = "banner") {
 *             data = vm.banner
 *             onBind { b, d -> b.iv.load(d.url) }
 *         }
 *         list<MenuItem, ItemMenuBinding>(ItemMenuBinding::inflate, diff = MENU_DIFF, tag = "menu") {
 *             data = vm.items
 *             onBind { b, item, _ -> b.tv.text = item.title }
 *             onItemClick(throttleMs = 600) { v, item, _ -> nav(item.route) }
 *         }
 *         pagingList<FeedItem, ItemFeedBinding>(ItemFeedBinding::inflate, diff = FEED_DIFF, tag = "feed") {
 *             flow = vm.feedFlow
 *             keyOf { it.id }
 *             onBind { b, item, _ -> b.tv.text = item.text }
 *             loadStateFooter { CommonLoadStateAdapter(onRetry = it) }
 *         }
 *     }
 *     .start()
 *
 * // 运行时：
 * page.paging<FeedItem>("feed")?.optimisticDelete(...)
 * page.addSection("promo", PromoAdapter(), at = 1)
 * page.hideSection("banner")
 * page.refresh()
 * ```
 *
 * ---
 *
 * ## 已知限制
 *
 * ### 1. 同一页面最多 1 个分页 section
 *
 * `pagingList` 与 `pagingMultiList` 加起来只能出现一次（[PagingHelper] 内部
 * `pagingAdapter()` 只能调一次，Paging 3 的设计就是"一个页面一个 `Flow<PagingData<T>>`"）。
 * 违反时 [start] 抛错并列出冲突 tag。
 *
 * **允许**：1 个 paging + N 个静态 section
 * ```
 * sections {
 *     single { ... }                  // Banner（静态）
 *     pagingList<Feed, ...> { ... }   // 主流（唯一分页）
 *     list<Promo, ...> { ... }        // 底部推荐（静态）
 * }
 * ```
 *
 * **禁止**：2 个 paging
 * ```
 * sections {
 *     pagingList<MyFollow, ...> { flow = vm.followFlow }    // ← 抛错
 *     pagingList<Recommend, ...> { flow = vm.recFlow }
 * }
 * ```
 *
 * **注意 `sections {}` 可链式多次调用**：每次调用都把声明的 section 追加到同一份列表，
 * paging 限制是**整个页面的总和**，分散到多次 `sections {}` 也躲不过：
 * ```
 * RvPage.with(owner)
 *     .sections { single(...); pagingList<A, ...> { flow = vm.flowA } }
 *     .sections { pagingList<B, ...> { flow = vm.flowB } }   // ← start() 时仍抛错
 *     .start()
 * ```
 * 这种写法和把所有 section 写在同一个 `sections {}` 块里**完全等价**——拆成多次只是组织代码
 * 的便利（例如按"头部 / 主列表 / 尾部"分块写），不会绕过任何校验。
 *
 * 真要"两个独立分页流"：用 TabLayout + ViewPager2 拆成两个 Fragment，或在服务端合流后用
 * [submitMultiList] 多类型 / sealed class 区分，或把其中一个改成静态全量拉。
 *
 * ### 2. `hideSection` 与 `hideAuxOnPageState` 在分页模式下的冲突
 *
 * [PagingHelper] 默认 `hideAuxOnPageState=true`，分页 loading/empty/error 时会自动把所有
 * header / footer / loadStateFooter 从 ConcatAdapter 摘掉，showContent 时按声明顺序**全部插回**。
 *
 * 而 [RvPageController.hideSection] 也是从 ConcatAdapter 摘掉 adapter——两个机制各管各的，
 * 互相不知道对方状态。冲突时间线：
 *
 * ```
 * T0  page.hideSection("banner")          → banner 被摘掉 ✓
 * T1  paging 进 loading（下拉刷新/首次进入）→ PagingHelper 把所有 aux 摘掉（无害）
 * T2  paging showContent（数据到达）       → PagingHelper 按声明顺序全部插回
 *                                            → banner 又被插回来了！hideSection 失效
 * ```
 *
 * 要在分页页面用 `hideSection`，必须显式 [hideAuxOnPageState]`(false)` 关掉自动管控；
 * 代价是空态 / loading 时 header/footer 不会自动让位给占位视图，需自行确保占位视图样式不冲突
 * （比如占位是覆盖整个 RV 的 overlay 而不是替换内容）。
 *
 * ### 3. section tag 唯一
 *
 * 每个 section 的 `tag` 在同一页面内必须唯一；不传 tag 时自动生成 `section_0` / `section_1` …。
 * 重复时 [start] 或 [RvPageController.addSection] 抛错。
 */
class RvPageBuilder private constructor(private val owner: LifecycleOwner) {

    companion object {
        /** 创建实例；[owner] 通常传 viewLifecycleOwner（Fragment）或 Activity 自身 */
        fun with(owner: LifecycleOwner) = RvPageBuilder(owner)
    }

    private var recyclerView: RecyclerView? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var refreshAdapter: PagingRefreshAdapter? = null
    private var pageStateHandler: PageStateHandler? = null
    private var emptyTextProvider: (() -> CharSequence?)? = null
    private var errorTextProvider: ((Throwable) -> CharSequence?)? = null
    private var onHeaderRefresh: (suspend () -> Unit)? = null
    private var hideAuxOnPageState: Boolean = true

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

    /**
     * 下拉刷新时除了 paging.refresh() 之外额外执行的头部接口。
     * 纯静态页面也可用：搭配 [refreshAdapter] 实现"下拉重拉静态数据"。
     */
    fun onHeaderRefresh(block: suspend () -> Unit) = apply { onHeaderRefresh = block }

    /**
     * 分页模式下进入 loading / empty / error 全屏占位态时，是否临时把 static section /
     * loadStateFooter 从 ConcatAdapter 摘掉；默认 true（行为对齐 [PagingHelper]）。
     *
     * **重要**：默认 true 时，[RvPageController.hideSection] 在状态切换时会被覆盖
     * （showContent 时被 helper 重新插回）。若业务需要在分页模式下手动 hide static section，
     * 请显式 `.hideAuxOnPageState(false)`。纯静态模式下本开关无效。
     */
    fun hideAuxOnPageState(hide: Boolean) = apply { hideAuxOnPageState = hide }

    /** DSL 入口：在 block 内声明各 section */
    fun sections(block: SectionsScope.() -> Unit) = apply {
        SectionsScope(sectionDefs).block()
    }

    /**
     * 完成配置并装配。
     * - 有分页 section → 委托 [PagingHelper]：paging 之前的 section 作为 headers，之后的作为 footers
     * - 无分页 section → 自建 [ConcatAdapter] + 可选 [PageStateHandler]
     */
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
        return if (pagingDefs.size == 1) buildWithPaging(rv) else buildStatic(rv)
    }

    private fun buildWithPaging(rv: RecyclerView): RvPageController {
        val pagingIndex = sectionDefs.indexOfFirst { it is SectionDef.Paging<*> }

        @Suppress("UNCHECKED_CAST")
        val pagingDef = sectionDefs[pagingIndex] as SectionDef.Paging<Any>
        val headersDef = sectionDefs.subList(0, pagingIndex)
        val footersDef = sectionDefs.subList(pagingIndex + 1, sectionDefs.size)

        val helper = PagingHelper.with<Any>(owner)
            .recyclerView(rv)
            .pagingAdapter(pagingDef.pagingAdapter)
            .pagingFlow(pagingDef.flow)
        layoutManager?.let { helper.layoutManager(it) }
        refreshAdapter?.let { helper.refreshAdapter(it) }
        pageStateHandler?.let { helper.pageState(it) }
        emptyTextProvider?.let { helper.emptyText(it) }
        errorTextProvider?.let { helper.errorText(it) }
        onHeaderRefresh?.let { helper.onHeaderRefresh(it) }
        pagingDef.keyOf?.let { helper.keyOf(it) }
        pagingDef.onLoadError?.let {
            helper.onLoadError(
                distinct = pagingDef.distinctErrorToast,
                block = it
            )
        }
        pagingDef.onEmpty?.let { helper.onEmpty(it) }
        pagingDef.loadStateFooterFactory?.let { helper.loadStateFooter(it) }
        if (pagingDef.chatMode) helper.chatMode(true)
        helper.hideAuxOnPageState(hideAuxOnPageState)

        headersDef.forEach { helper.addHeader(it.adapter, spanFull = it.spanFull) }
        footersDef.forEach { helper.addFooter(it.adapter, spanFull = it.spanFull) }

        val pagingController = helper.start()
        // 把 controller 绑回 PagingSectionHandle，业务通过 handle.controller 拿
        pagingDef.handle.bind(pagingController)
        val concat = rv.adapter as ConcatAdapter

        val entries = sectionDefs.map {
            RvPageController.SectionEntry(it.tag, it.adapter, it.spanFull, visible = true)
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
            *sectionDefs.map { it.adapter }.toTypedArray()
        )
        val lm = layoutManager ?: LinearLayoutManager(rv.context)
        rv.layoutManager = lm
        rv.adapter = concat
        when (lm) {
            is GridLayoutManager -> configSpanSize(lm, concat)
            is StaggeredGridLayoutManager -> configStaggered(rv, concat)
        }

        val entries = sectionDefs.map {
            RvPageController.SectionEntry(it.tag, it.adapter, it.spanFull, visible = true)
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
        val fullSpanAdapters = sectionDefs.filter { it.spanFull }.map { it.adapter }.toSet()
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
                if (inner in fullSpanAdapters) return spanCount
                return userLookup.getSpanSize(localPos)
            }
        }
    }

    private fun configStaggered(rv: RecyclerView, concat: ConcatAdapter) {
        val fullSpanAdapters = sectionDefs.filter { it.spanFull }.map { it.adapter }.toSet()
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

    /**
     * `sections { ... }` block 的接收者；提供六种 section 工厂方法。
     * 每个工厂创建好 Adapter 后立即把 [SectionDef] 加入 sectionDefs，[start] 时统一装配。
     */
    class SectionsScope internal constructor(private val defs: MutableList<SectionDef>) {

        private var autoTagIndex = 0
        private fun nextTag(): String = "section_${autoTagIndex++}"

        /**
         * 单格 section（[SingleItemBindingAdapter] 承载）。
         * @return 强类型句柄，直接 `.submit(data)` 喂数据，无需 tag
         */
        fun <T : Any, VB : ViewBinding> single(
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            tag: String? = null,
            block: SingleBuilder<T, VB>.() -> Unit
        ): SingleSectionHandle<T> {
            val builder = SingleBuilder<T, VB>(inflate).apply(block)
            val adapter = builder.build()
            addInternal(adapter, tag, builder.spanFull)
            return SingleSectionHandle(adapter)
        }

        /** 单布局静态列表 section（[BaseListAdapter] 承载） */
        fun <T : Any, VB : ViewBinding> list(
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            diff: DiffUtil.ItemCallback<T>,
            tag: String? = null,
            block: ListBuilder<T, VB>.() -> Unit
        ): ListSectionHandle<T> {
            val builder = ListBuilder<T, VB>(inflate, diff).apply(block)
            val adapter = builder.build()
            addInternal(adapter, tag, builder.spanFull)
            return ListSectionHandle(adapter)
        }

        /** 多布局静态列表 section（[BaseMultiListAdapter] 承载） */
        fun <T : Any> multiList(
            diff: DiffUtil.ItemCallback<T>,
            tag: String? = null,
            block: MultiListBuilder<T>.() -> Unit
        ): MultiListSectionHandle<T> {
            val builder = MultiListBuilder<T>(diff).apply(block)
            val adapter = builder.build()
            addInternal(adapter, tag, builder.spanFull)
            return MultiListSectionHandle(adapter)
        }

        /**
         * 单布局分页 section（[BasePagingAdapter] 承载，最终走 [PagingHelper]）。
         * @return 句柄；`controller` 在 [start] 完成后非空，可调 `optimisticUpdate` 等
         */
        fun <T : Any, VB : ViewBinding> pagingList(
            inflate: (LayoutInflater, ViewGroup, Boolean) -> VB,
            diff: DiffUtil.ItemCallback<T>,
            tag: String? = null,
            block: PagingListBuilder<T, VB>.() -> Unit
        ): PagingSectionHandle<T> {
            val builder = PagingListBuilder<T, VB>(inflate, diff).apply(block)
            val handle = PagingSectionHandle<T>()
            addPagingInternal(builder.buildAdapter(), builder.flow!!, tag, builder, handle)
            return handle
        }

        /** 多布局分页 section（[BaseMultiPagingAdapter] 承载，最终走 [PagingHelper]） */
        fun <T : Any> pagingMultiList(
            diff: DiffUtil.ItemCallback<T>,
            tag: String? = null,
            block: PagingMultiListBuilder<T>.() -> Unit
        ): PagingSectionHandle<T> {
            val builder = PagingMultiListBuilder<T>(diff).apply(block)
            val handle = PagingSectionHandle<T>()
            addPagingInternal(builder.buildAdapter(), builder.flow!!, tag, builder, handle)
            return handle
        }

        /** 直接挂业务自家 Adapter；返回简单 handle 暴露 adapter 引用 */
        fun custom(
            tag: String? = null,
            adapter: RecyclerView.Adapter<*>,
            spanFull: Boolean = true
        ): CustomSectionHandle {
            addInternal(adapter, tag, spanFull)
            return CustomSectionHandle(adapter)
        }

        private fun addInternal(adapter: RecyclerView.Adapter<*>, tag: String?, spanFull: Boolean) {
            defs.add(SectionDef.Static(tag ?: nextTag(), adapter, spanFull))
        }

        private fun <T : Any> addPagingInternal(
            adapter: PagingDataAdapter<T, *>,
            flow: Flow<PagingData<T>>,
            tag: String?,
            cfg: PagingCommonConfig<T>,
            handle: PagingSectionHandle<T>
        ) {
            @Suppress("UNCHECKED_CAST")
            defs.add(
                SectionDef.Paging(
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
                    handle = handle as PagingSectionHandle<Any>
                )
            )
        }
    }

    // ───── Section builders ─────

    /**
     * 单格 section builder：配置 [SingleItemBindingAdapter]。
     * @property data 要显示的数据；null 时该 section 不占格
     * @property spanFull GridLayoutManager 下是否跨整行，默认 true
     */
    class SingleBuilder<T : Any, VB : ViewBinding> internal constructor(
        private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB
    ) {
        var data: T? = null
        var spanFull: Boolean = true
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

    /**
     * 单布局静态列表 builder：配置 [BaseListAdapter]。
     */
    class ListBuilder<T : Any, VB : ViewBinding> internal constructor(
        private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB,
        private val diff: DiffUtil.ItemCallback<T>
    ) {
        var data: List<T> = emptyList()
        var spanFull: Boolean = true
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

        internal fun build(): BaseListAdapter<T, VB> {
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

    /**
     * 多布局静态列表 builder：配置 [BaseMultiListAdapter]。
     */
    class MultiListBuilder<T : Any> internal constructor(
        private val diff: DiffUtil.ItemCallback<T>
    ) {
        var data: List<T> = emptyList()
        var spanFull: Boolean = true
        private val typeRegistrations =
            mutableListOf<(BaseMultiListAdapter<T>) -> Unit>()

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

        internal fun build(): BaseMultiListAdapter<T> {
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

    /**
     * 分页 section 共有配置，被 [PagingListBuilder] / [PagingMultiListBuilder] 共享。
     */
    abstract class PagingCommonConfig<T : Any> {
        var flow: Flow<PagingData<T>>? = null
        var spanFull: Boolean = true
        var chatMode: Boolean = false
        internal var keyOf: ((T) -> Any)? = null
        internal var onLoadError: ((Throwable) -> Unit)? = null
        internal var distinctErrorToast: Boolean = true
        internal var onEmpty: ((Boolean) -> Unit)? = null
        internal var loadStateFooterFactory: ((retry: () -> Unit) -> LoadStateAdapter<*>)? = null

        fun keyOf(extractor: (T) -> Any) {
            keyOf = extractor
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
    }

    /**
     * 单布局分页 builder：内部生成 [BasePagingAdapter] 委托给 [PagingHelper]。
     */
    class PagingListBuilder<T : Any, VB : ViewBinding> internal constructor(
        private val inflater: (LayoutInflater, ViewGroup, Boolean) -> VB,
        private val diff: DiffUtil.ItemCallback<T>
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

        internal fun buildAdapter(): PagingDataAdapter<T, *> {
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

    /**
     * 多布局分页 builder：内部生成 [BaseMultiPagingAdapter] 委托给 [PagingHelper]。
     */
    class PagingMultiListBuilder<T : Any> internal constructor(
        private val diff: DiffUtil.ItemCallback<T>
    ) : PagingCommonConfig<T>() {
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

        internal fun buildAdapter(): PagingDataAdapter<T, *> {
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

    /**
     * Section 在 builder 内的中间表示（不暴露给业务）。
     */
    sealed class SectionDef(
        val tag: String,
        val adapter: RecyclerView.Adapter<*>,
        val spanFull: Boolean
    ) {
        class Static(tag: String, adapter: RecyclerView.Adapter<*>, spanFull: Boolean) :
            SectionDef(tag, adapter, spanFull)

        class Paging<T : Any>(
            tag: String,
            /** 强类型 PagingDataAdapter；父类 [SectionDef.adapter] 是同一实例的弱类型视图 */
            val pagingAdapter: PagingDataAdapter<T, *>,
            val flow: Flow<PagingData<T>>,
            spanFull: Boolean,
            val keyOf: ((T) -> Any)?,
            val onLoadError: ((Throwable) -> Unit)?,
            val distinctErrorToast: Boolean,
            val onEmpty: ((Boolean) -> Unit)?,
            val loadStateFooterFactory: ((retry: () -> Unit) -> LoadStateAdapter<*>)?,
            val chatMode: Boolean,
            /** start() 完成后会把 PagingController 绑回 handle，让业务通过 handle 拿 controller */
            val handle: PagingSectionHandle<T>
        ) : SectionDef(tag, pagingAdapter, spanFull)
    }
}

/** 顶层快捷别名，让调用更短：`RvPage.with(owner)` */
typealias RvPage = RvPageBuilder
