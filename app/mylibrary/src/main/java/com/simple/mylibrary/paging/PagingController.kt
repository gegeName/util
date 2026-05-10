package com.simple.mylibrary.paging

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 由 [PagingHelper.start] 创建并返回的对外操作句柄。
 *
 * 控制类：refresh / retry / autoRefresh / scrollToTop / cancelRequest 等
 * 直接更新：update / replace / updateAll / delete / insertHead 等
 * 乐观更新（带 [RequestPolicy] 限频/串行）：optimisticUpdate / optimisticDelete / optimisticInsertHead
 *
 * @param T item 类型
 * @property adapter 业务的 PagingDataAdapter，所有控制 / 数据查询都委托给它
 * @property recyclerView 列表 RecyclerView，目前用于 [scrollToTop]
 * @property refreshAdapter 刷新框架抽象层，可空；为空时 [autoRefresh] 退化为 [refresh]
 * @property onHeaderRefresh 下拉刷新时附带要做的头部接口（PagingHelper 配置），refresh() 时也会触发
 * @property owner 生命周期宿主，用于内部 lifecycleScope.launch
 * @property patcher 局部更新补丁器；只有调用过 PagingHelper.keyOf 时才存在，所有 update / delete / insertHead 都依赖它
 * @property clearPatchesOnRefresh 下拉刷新时是否自动清空本地补丁，默认 true
 * @property runner 乐观更新的请求调度器，按 key + RequestPolicy 节流 / 串行 / 取消
 */
class PagingController<T : Any> internal constructor(
    private val adapter: PagingDataAdapter<T, *>,
    private val recyclerView: RecyclerView,
    private val refreshAdapter: PagingRefreshAdapter?,
    private val onHeaderRefresh: (suspend () -> Unit)?,
    private val owner: LifecycleOwner,
    private val patcher: PagingPatcher<Any, T>?,
    private val clearPatchesOnRefresh: Boolean,
    private val runner: KeyedRequestRunner,
    private val dragSortHelper: DragSortHelper<T>? = null
) {

    // ───── 控制类 ─────

    /**
     * 重新走一遍 PagingSource。
     *
     * 行为：
     * 1. 如果 [clearPatchesOnRefresh]=true，先清空 patcher 的所有本地补丁（删除 / 插入 / 字段改）
     * 2. 触发 [onHeaderRefresh]（业务头部接口，例如轮播 / 公告）
     * 3. 调 adapter.refresh() 让 PagingSource 失效，upstream 重新发首屏
     */
    fun refresh() {
        if (clearPatchesOnRefresh) patcher?.clearAll()
        owner.lifecycleScope.launch { onHeaderRefresh?.invoke() }
        adapter.refresh()
    }

    /** 重试上一次失败的 load（refresh 失败 / append 失败都可重试），等价于 adapter.retry() */
    fun retry() = adapter.retry()

    /**
     * 触发"下拉刷新动画 + refresh"。
     *
     * 有 [refreshAdapter]：调框架的 autoRefresh，由框架触发自带的 OnRefreshListener
     * 没有 [refreshAdapter]：直接调 [refresh]，没有动画
     */
    fun autoRefresh() {
        if (refreshAdapter != null) refreshAdapter.autoRefresh() else refresh()
    }

    /** 列表瞬间回顶，等价于 RecyclerView.scrollToPosition(0) */
    fun scrollToTop() = recyclerView.scrollToPosition(0)

    /** 取当前 adapter 的快照（包含 patcher 应用后的可见 item），用于读取当前列表状态 */
    fun snapshot() = adapter.snapshot()

    /** 当前 adapter 的 itemCount（不含 ConcatAdapter 的 header / footer / loadState 尾部） */
    fun itemCount() = adapter.itemCount

    /**
     * 切账号 / 退出页面常用：清空整个列表。
     *
     * 实现细节：当存在 patcher（即 PagingHelper.keyOf 已配置）时，通过 patcher 的 delete + removeInsert
     * 把当前可见数据全部"软清空"，adapter 仍然挂在活的 source 上，下次 refresh() 能正常触发
     * loadState 切换、刷新动画能正常关闭。没有 patcher 时退化为 submitData(PagingData.empty())，
     * 但要注意此时再 refresh() 不会真正重新拉数据（adapter 已和原 source 解绑），刷新动画可能不消失。
     */
    fun clear() {
        val p = patcher
        if (p != null) {
            // 1) 先清掉 patcher 自己插入的临时条目
            p.removeInsert { true }
            // 2) 把当前 adapter 可见的所有 key 加入 _removed，combine 重新发 → adapter 立即变空
            adapter.snapshot().items.forEach { p.delete(p.keyOf(it)) }
        } else {
            owner.lifecycleScope.launch { adapter.submitData(PagingData.empty()) }
        }
    }

    /**
     * 取消某 key 当前正在进行的乐观更新请求。
     *
     * 配合 [optimisticUpdate] / [optimisticDelete] / [optimisticInsertHead] 使用。
     * 取消后会抛出 CancellationException，不会触发 onFailure 回调（被乐观方法内部 rethrow 跳过）。
     *
     * @param key 要取消的请求 key（与 optimistic* 调用时使用的 key 相同）
     */
    fun cancelRequest(key: Any) = runner.cancel(key)

    /** 取消所有正在进行的乐观更新请求；切页面 / 退出登录时清场用 */
    fun cancelAllRequests() = runner.cancelAll()

    /**
     * 业务自定义手柄场景：在拖动手柄按下时调用，让 ItemTouchHelper 接管该 holder 的拖动。
     *
     * 仅在 [PagingHelper.enableDragSort] 已启用时有效；未启用时返回。
     *
     * 典型用法（手柄拖动而不是长按）：
     * ```
     * holder.binding.dragHandle.setOnTouchListener { _, e ->
     *     if (e.action == MotionEvent.ACTION_DOWN) controller.startDrag(holder)
     *     false
     * }
     * ```
     */
    fun startDrag(holder: RecyclerView.ViewHolder) {
        dragSortHelper?.startDrag(holder)
    }

    // ───── 直接更新（不走网络） ─────

    /**
     * 改字段：对某条 item 打字段级补丁。
     *
     * 多次对同一 key 调用，后写覆盖前写。底层走 patcher 的 _patches StateFlow，
     * combine 触发后 collectLatest 会重新 submitData，列表自动 diff 出变更。
     *
     * 注意：本方法 fire-and-forget，调完立刻 [snapshot] 拿到的可能仍是旧值（patch 走 flow 异步生效）。
     * 需要拿到"更新后的值"用 [updateAndGet]，或在 [optimisticUpdate] 的 onSuccess 里取服务端权威值。
     *
     * @param key 主键，由 PagingHelper.keyOf 提取
     * @param transform 接收旧 item，必须返回一个 copy 出来的新对象（data class 用 .copy）
     */
    fun update(key: Any, transform: (T) -> T) = requirePatcher().patch(key, transform)

    /**
     * 改字段并返回更新后的值（同步拿到新值）。
     *
     * 实现：先在当前 snapshot 里按 key 查到旧 item，本地算出 new = transform(old)，
     * 然后等价于调 [update] 把补丁排入 patcher。所以返回的 [T] 就是这次变更落到 UI 后的新值。
     *
     * 如果 [key] 在当前 snapshot 中找不到（item 还没加载到 / 已被 _removed 过滤），返回 null 且不 patch。
     *
     * @param key 主键
     * @param transform 字段变换
     * @return 应用 transform 后的新 item，未找到时 null
     */
    fun updateAndGet(key: Any, transform: (T) -> T): T? {
        val p = requirePatcher()
        val old = adapter.snapshot().items.firstOrNull { p.keyOf(it) == key } ?: return null
        val new = transform(old)
        p.patch(key) { transform(it) }
        return new
    }

    /** 在当前 snapshot 中按 key 查 item；找不到返回 null。便捷封装 */
    fun findByKey(key: Any): T? {
        val p = requirePatcher()
        return adapter.snapshot().items.firstOrNull { p.keyOf(it) == key }
    }

    /**
     * 整条替换：用 [newItem] 完全替换某条 item，等价于 update(key) { newItem }。
     *
     * @param key 主键
     * @param newItem 替换后的新整条数据
     */
    fun replace(key: Any, newItem: T) = requirePatcher().patch(key) { newItem }

    /**
     * 批量改：满足 [predicate] 的 item 都打 [transform] 补丁，适合"全部已读"、"批量收藏"等场景。
     *
     * 注意只作用于当前 snapshot 中已加载的 item；后续翻页加载到的同条件 item 不会自动 patch。
     *
     * @param predicate 过滤条件，true 表示该 item 要被 patch
     * @param transform 字段变换，必须 copy 出新对象
     */
    fun updateAll(predicate: (T) -> Boolean, transform: (T) -> T) {
        val p = requirePatcher()
        adapter.snapshot().items
            .filter(predicate)
            .forEach { p.patch(p.keyOf(it), transform) }
    }

    /**
     * 删除单条：把 [key] 加入 _removed 集合，combine 过滤后该条不再可见。
     *
     * 不真正发起接口请求；如需服务端同步，用 [optimisticDelete]。
     */
    fun delete(key: Any) = requirePatcher().delete(key)

    /**
     * 批量删除：见 [delete]。
     *
     * @param keys 要删除的主键集合
     */
    fun delete(keys: Collection<Any>) = keys.forEach { requirePatcher().delete(it) }

    /**
     * 撤回删除：把 [key] 从 _removed 集合中移除，该 item 重新可见。
     *
     * 配合 [delete] 实现"撤销删除" UI；[optimisticDelete] 失败时也会自动调它回退。
     */
    fun undelete(key: Any) = requirePatcher().undelete(key)

    /**
     * 头部插入一条：在列表最顶端插入 [item]。
     *
     * 多次调用按"最新在最上"排列；patcher 内部维护 _inserts 列表，最近一次 insert 在最前。
     */
    fun insertHead(item: T) = requirePatcher().insertHead(item)

    /**
     * 头部批量插入：按 List 顺序插入到顶端，[items] 第 0 条最终展示在最上。
     *
     * 内部对 list 做了 reverse 后逐条 insertHead，保证视觉顺序与传入顺序一致。
     */
    fun insertHead(items: List<T>) =
        items.asReversed().forEach { requirePatcher().insertHead(it) }

    /**
     * 撤销某条补丁：让该 item 回到 PagingSource 给的原始值。
     *
     * 只撤销字段级补丁（_patches），不影响 _removed / _inserts 状态。
     *
     * @param key 主键
     */
    fun unpatch(key: Any) = requirePatcher().unpatch(key)

    /** 清空所有本地修改：补丁 / 删除 / 插入全部清空，等价于 patcher.clearAll() */
    fun clearLocalChanges() = patcher?.clearAll()

    // ───── 乐观更新（自动回退 + 策略调度） ─────

    /**
     * 乐观字段更新：先打补丁让 UI 立即生效，再发请求；失败自动 unpatch 回退。
     *
     * @param R 服务端响应类型
     * @param key 主键，同时也是请求调度的 key（[policy] 按这个 key 节流 / 串行）
     * @param transform 立即生效的字段变换，必须 copy 出新对象
     * @param request 真正的服务端请求；正常返回视为成功，抛异常视为失败
     * @param policy 请求调度策略：[RequestPolicy.None] 默认每次都跑；[RequestPolicy.Throttle] 节流；
     *               [RequestPolicy.Latest] 取消旧的；[RequestPolicy.Serial] 串行
     * @param onSuccess 请求成功回调，参数是 [request] 的返回值
     * @param onFailure 请求失败回调（CancellationException 会被吞掉，不算失败）
     * @param onIgnored 被 [policy] 调度规则丢弃时回调（例如节流窗口内的连点）
     * @return 启动后的 Job；被 policy 丢弃时返回 null
     */
    fun <R> optimisticUpdate(
        key: Any,
        transform: (T) -> T,
        request: suspend () -> R,
        policy: RequestPolicy = RequestPolicy.None,
        onSuccess: (suspend (R) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        onIgnored: (() -> Unit)? = null
    ): Job? {
        val p = requirePatcher()
        val job = runner.run(key, policy) {
            p.patch(key, transform)
            runCatching { request() }
                .onSuccess { resp -> onSuccess?.invoke(resp) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    p.unpatch(key)
                    onFailure?.invoke(e)
                }
        }
        if (job == null) onIgnored?.invoke()
        return job
    }

    /**
     * 乐观删除：先把 item 从列表移除让 UI 立即生效，再发请求；失败自动 undelete 回退。
     *
     * @param R 服务端响应类型
     * @param key 主键
     * @param request 真正的删除请求
     * @param policy 请求调度策略，同 [optimisticUpdate]
     * @param onSuccess 请求成功回调
     * @param onFailure 请求失败回调（CancellationException 会被吞掉）
     * @param onIgnored 被 policy 丢弃时回调
     * @return 启动后的 Job；被 policy 丢弃时返回 null
     */
    fun <R> optimisticDelete(
        key: Any,
        request: suspend () -> R,
        policy: RequestPolicy = RequestPolicy.None,
        onSuccess: (suspend (R) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        onIgnored: (() -> Unit)? = null
    ): Job? {
        val p = requirePatcher()
        val job = runner.run(key, policy) {
            p.delete(key)
            runCatching { request() }
                .onSuccess { resp -> onSuccess?.invoke(resp) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    p.undelete(key)
                    onFailure?.invoke(e)
                }
        }
        if (job == null) onIgnored?.invoke()
        return job
    }

    /**
     * 乐观头部插入：先把 item 插到顶端让 UI 立即生效，再发请求；失败自动 removeInsert 回退。
     *
     * 调度 key 自动取自 patcher.keyOf(item)，所以 policy 是按 item 主键节流 / 串行的。
     *
     * @param R 服务端响应类型
     * @param item 要插入的新条目（如发布的新评论 / 新动态）
     * @param request 真正的发布 / 新增请求
     * @param policy 请求调度策略，同 [optimisticUpdate]
     * @param onSuccess 请求成功回调，可在这里把临时 item 替换为服务端返回的正式 item
     * @param onFailure 请求失败回调（CancellationException 会被吞掉）
     * @param onIgnored 被 policy 丢弃时回调
     * @return 启动后的 Job；被 policy 丢弃时返回 null
     */
    fun <R> optimisticInsertHead(
        item: T,
        request: suspend () -> R,
        policy: RequestPolicy = RequestPolicy.None,
        onSuccess: (suspend (R) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        onIgnored: (() -> Unit)? = null
    ): Job? {
        val p = requirePatcher()
        val key = p.keyOf(item)
        val job = runner.run(key, policy) {
            p.insertHead(item)
            runCatching { request() }
                .onSuccess { resp -> onSuccess?.invoke(resp) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    p.removeInsert { p.keyOf(it) == key }
                    onFailure?.invoke(e)
                }
        }
        if (job == null) onIgnored?.invoke()
        return job
    }

    // ───── 先请求成功再改本地（服务端权威值落地） ─────

    /**
     * 先发请求拿到服务端返回，再用 [transform] 把响应映射到 item 上 patch。
     *
     * 与 [optimisticUpdate] 对偶：那个是先改 UI 再请求，失败回退；本方法是请求成功才改 UI，
     * 失败时本地完全不动。适合"必须以服务端值为准"的场景，比如：充值后拿到的真实余额、
     * 投票后拿到的真实票数、改名后拿到的服务端规整化的昵称等。
     *
     * @param R 服务端响应类型
     * @param key 主键，同时是请求调度 key（[policy] 按它节流 / 串行）
     * @param request 真正的请求；正常返回视为成功，抛异常视为失败
     * @param transform (旧 item, 响应) → 新 item，必须 copy 出新对象
     * @param policy 请求调度策略，同 [optimisticUpdate]
     * @param onSuccess 请求成功 + patch 完成后回调
     * @param onFailure 请求失败回调（CancellationException 会被吞掉）
     * @param onIgnored 被 [policy] 调度规则丢弃时回调
     * @return 启动后的 Job；被 policy 丢弃时返回 null
     */
    fun <R> serverUpdate(
        key: Any,
        request: suspend () -> R,
        transform: (T, R) -> T,
        policy: RequestPolicy = RequestPolicy.None,
        onSuccess: (suspend (R) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        onIgnored: (() -> Unit)? = null
    ): Job? {
        val p = requirePatcher()
        val job = runner.run(key, policy) {
            runCatching { request() }
                .onSuccess { resp ->
                    p.patch(key) { old -> transform(old, resp) }
                    onSuccess?.invoke(resp)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    onFailure?.invoke(e)
                }
        }
        if (job == null) onIgnored?.invoke()
        return job
    }

    /**
     * 先请求成功再删除：等接口返回 OK 后，把 [key] 加入 _removed。
     *
     * 失败时本地不变，不会有"先消失后又冒出来"的闪烁，但用户感觉慢一拍。
     * 适合误删代价高的业务，比如付费内容、消息撤回、发票作废等。
     *
     * @param R 服务端响应类型
     * @param key 主键
     * @param request 真正的删除请求
     * @param policy 请求调度策略
     * @param onSuccess 请求成功 + 本地删除完成后回调
     * @param onFailure 请求失败回调（CancellationException 会被吞掉）
     * @param onIgnored 被 policy 丢弃时回调
     * @return 启动后的 Job；被 policy 丢弃时返回 null
     */
    fun <R> serverDelete(
        key: Any,
        request: suspend () -> R,
        policy: RequestPolicy = RequestPolicy.None,
        onSuccess: (suspend (R) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        onIgnored: (() -> Unit)? = null
    ): Job? {
        val p = requirePatcher()
        val job = runner.run(key, policy) {
            runCatching { request() }
                .onSuccess { resp ->
                    p.delete(key)
                    onSuccess?.invoke(resp)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    onFailure?.invoke(e)
                }
        }
        if (job == null) onIgnored?.invoke()
        return job
    }

    /**
     * 先请求成功再头部插入：等接口返回后，用 [mapper] 把响应转成 item 再 insertHead。
     *
     * 与 [optimisticInsertHead] 对偶：那个先插临时 item 再请求；本方法等服务端返回真实 item
     * （含真实 id / createTime / 头像等）再插。适合发评论、发动态等需要服务端 id 才能定位的场景。
     *
     * 注意：调度 key 取自 [scheduleKey]（不是 item 主键，因为发请求时还没有 item）；建议用业务上稳定的
     * 串行 key，比如 "post_comment_${threadId}" 用于"同一帖子下连发评论排队"。
     *
     * @param R 服务端响应类型
     * @param scheduleKey 请求调度 key，决定 [policy] 的节流 / 串行 / 取消范围
     * @param request 真正的请求；返回结果交给 [mapper] 转换
     * @param mapper 把服务端响应映射为要插入到列表顶部的 item
     * @param policy 请求调度策略
     * @param onSuccess 请求成功 + insertHead 完成后回调
     * @param onFailure 请求失败回调（CancellationException 会被吞掉）
     * @param onIgnored 被 policy 丢弃时回调
     * @return 启动后的 Job；被 policy 丢弃时返回 null
     */
    fun <R> serverInsertHead(
        scheduleKey: Any,
        request: suspend () -> R,
        mapper: (R) -> T,
        policy: RequestPolicy = RequestPolicy.None,
        onSuccess: (suspend (R) -> Unit)? = null,
        onFailure: ((Throwable) -> Unit)? = null,
        onIgnored: (() -> Unit)? = null
    ): Job? {
        val p = requirePatcher()
        val job = runner.run(scheduleKey, policy) {
            runCatching { request() }
                .onSuccess { resp ->
                    p.insertHead(mapper(resp))
                    onSuccess?.invoke(resp)
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    onFailure?.invoke(e)
                }
        }
        if (job == null) onIgnored?.invoke()
        return job
    }

    /** 取 patcher，没设 keyOf 直接抛异常并提示 */
    private fun requirePatcher(): PagingPatcher<Any, T> = patcher
        ?: error("使用 update/delete/insertHead/optimistic* 前必须先 .keyOf { ... } 配置主键提取器")
}
