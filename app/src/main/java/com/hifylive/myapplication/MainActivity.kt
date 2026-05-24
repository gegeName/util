package com.hifylive.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import com.hifylive.myapplication.databinding.ActivityMainBinding
import com.hifylive.myapplication.sample.BannerData
import com.hifylive.myapplication.sample.BannerHeaderAdapter
import com.hifylive.myapplication.sample.FakeAdapter
import com.hifylive.myapplication.sample.FakeItem
import com.hifylive.myapplication.sample.FakeListViewModel
import com.hifylive.myapplication.sample.FooterData
import com.hifylive.myapplication.sample.RecommendFooterAdapter
import com.chat.pagingutil.CommonLoadStateAdapter
import com.chat.pagingutil.PagingController
import com.chat.pagingutil.PagingHelper
import com.chat.pagingutil.RequestPolicy
import com.chat.pagingutil.SpacingItemDecoration
import com.chat.pagingutil.SwipeRefreshAdapter
import kotlinx.coroutines.delay

/**
 * 同时演示 PagingHelper 全部 16 个方法 + PagingController 全部 22 个方法。
 * 顶部按钮区按 控制 / 直接更新 / 乐观更新 三类，逐个点击即可对照效果。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: FakeListViewModel by viewModels()
    private lateinit var controller: PagingController<FakeItem>

    // 用于 undelete / cancelKey 演示的状态
    private var lastDeletedKey: Long? = null
    private var pendingOptKey: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding.btnJumpViewActivity.setOnClickListener {
            startActivity(Intent(this@MainActivity, ViewActivity::class.java))
        }

        binding.btnSpanActivity.setOnClickListener {
            startActivity(Intent(this@MainActivity, SpanActivity::class.java))
        }
        binding.btnSpanRecyclerActivity.setOnClickListener {
            startActivity(Intent(this@MainActivity, SpanRecyclerActivity::class.java))
        }
        binding.btnZoom.setOnClickListener {
            startActivity(Intent(this@MainActivity, ZoomGestureActivity::class.java))
        }
        binding.btnChatActivity.setOnClickListener {
            startActivity(Intent(this@MainActivity, ChatActivity::class.java))
        }
        binding.btnEffectActivity.setOnClickListener {
            startActivity(Intent(this@MainActivity, EffectActivity::class.java))
        }
        binding.btnChat2Activity.setOnClickListener {
            startActivity(Intent(this@MainActivity, BidirectionalChatActivity::class.java))
        }
        val bannerAdapter = BannerHeaderAdapter().apply {
            submit(BannerData("头部 Banner（addHeader）", "下拉刷新会触发 onHeaderRefresh 更新"))
            setOnItemClickListener(throttleMs = 600) { _, data ->
                toast("点击 Banner：${data.title}")
            }
            setOnItemLongClickListener { _, data ->
                toast("长按 Banner：${data.desc}"); true
            }

            setOnItemChildClickListener(throttleMs = 600) { v, _ ->
                toast("点击 Banner 子 View id=${v.id}")
            }
        }
        val bannerAdapter1 = BannerHeaderAdapter().apply {
            submit(BannerData("头部 Banner（addHeader）", "下拉刷新会触发 onHeaderRefresh 更新"))
            setOnItemClickListener(throttleMs = 600) { _, data ->
                toast("点击 Banner：${data.title}")
            }
            setOnItemLongClickListener { _, data ->
                toast("长按 Banner：${data.desc}"); true
            }
            setOnItemChildClickListener(throttleMs = 600) { v, _ ->
                toast("点击 Banner 子 View id=${v.id}")
            }
        }
        val footerAdapter = RecommendFooterAdapter().apply {
            submit(FooterData("业务尾部（addFooter）", "GridLayoutManager 下 spanFull=true 跨整行"))
        }
        val pagingAdapter = FakeAdapter().apply {
            setOnItemClickListener(throttleMs = 600, keyOf = { it.id }) { _, item, pos ->
                toast("点击 #$pos  → ${item.title}")
            }

            setOnItemChildClickListener { view, item, position ->
                toast("点击子View #$position  → ${item.title}")
            }
        }

        SpacingItemDecoration.builder().itemSpacing(16).edge(10).attachRecyclerView(binding.rv)

        // ── 演示：外部自定义 SpanSize（PagingHelper 会保留这套规则） ──────────
        // 4 列 Grid：id % 7 == 0 跨满 4 列，id % 3 == 0 占 2 列，其它 1 列
        // header / footer / loadStateFooter 因 spanFull=true 仍由 PagingHelper 强制跨整行
        val gridLm = GridLayoutManager(this, 4)
        gridLm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                // 这里的 position 已经是 pagingAdapter 内部的本地 position（PagingHelper 帮算好了）
                val item = pagingAdapter.peek(position) ?: return 1
                return when {
                    item.id % 7L == 0L -> 4   // "精选"跨满
                    item.id % 3L == 0L -> 2   // "重点"占两格
                    else -> 1
                }
            }
        }

        controller = PagingHelper.with<FakeItem>(this)
            .recyclerView(binding.rv)
            .layoutManager(gridLm)
            .refreshAdapter(SwipeRefreshAdapter(binding.swipeRefresh))
            .pagingAdapter(pagingAdapter)
            .pagingFlow(vm.pagingFlow)
            .addHeader(bannerAdapter, spanFull = true)
            .addHeader(bannerAdapter1, spanFull = true)
            .addFooter(footerAdapter, spanFull = true)
            .loadStateFooter { retry -> CommonLoadStateAdapter(onRetry = retry) }
            .onHeaderRefresh {
                delay(400)
                bannerAdapter.submit(
                    BannerData("头部 Banner 已刷新", "时间戳：${System.currentTimeMillis()}")
                )
            }
            .onLoadError(distinct = true) { e -> toast("加载失败：${e.message}") }
            .onEmpty { isEmpty -> if (isEmpty) toast("暂无数据（onEmpty）") }
            .onLoadState { state ->
                Log.d(
                    "PagingDemo",
                    "onLoadState refresh=${tag(state.refresh)} append=${tag(state.append)}"
                )
            }
            .patcher(vm.patcher)              // 用 ViewModel 持有的 patcher，旋转/返回再进保留本地补丁
            .pageState(binding.pageState)     // 接入 PageStateHandler 实现（这里是默认的 PageStateView）
            .emptyText("还没有任何数据，下拉试试")  // 静态文案；也可传 lambda 返回动态文案
            .errorText { e ->                // 按异常类型给不同提示
                when (e) {
                    is java.net.UnknownHostException -> "网络不给力，请检查后重试"
                    else -> e.message ?: "加载失败"
                }
            }
            .clearPatchesOnRefresh(true)
            // 长按拖动 → 上传服务端
            .enableDragSort(
                longPressEnabled = true,
                vibrateOnDragStart = true,
                canDrag = { item, pos ->
                    pos != 4
                }) { fromKey, toKey, fromLocal, toLocal ->
                // 拖动后，pagingAdapter.snapshot() 是"原始顺序"，但视图层已经 notifyItemMoved 过；
                // 我们要的是拖完后的最终顺序，自己根据 fromLocal/toLocal 在快照上模拟一次 move 即可。
                val before = pagingAdapter.snapshot().items.toMutableList()
                if (fromLocal in before.indices && toLocal in before.indices) {
                    val moved = before.removeAt(fromLocal)
                    before.add(toLocal, moved)
                }
                val movedItem = before.getOrNull(toLocal) ?: return@enableDragSort
                val beforeKey = before.getOrNull(toLocal - 1)?.id
                val afterKey = before.getOrNull(toLocal + 1)?.id
                vm.onItemsReordered(
                    currentItems = before,
                    movedKey = movedItem.id,
                    beforeKey = beforeKey,
                    afterKey = afterKey
                )
            }
            .start()

        bindControlButtons()
        bindDirectButtons()
        bindOptimisticButtons()
        bindServerFirstButtons()
    }

    // ─────────────────────────────────────────────────────────────
    // 4) 先请求成功再改本地（服务端权威值）
    // ─────────────────────────────────────────────────────────────
    private fun bindServerFirstButtons() = with(binding) {
        // serverUpdate：等接口返回，用 (old, resp) -> new 把权威值落地
        btnServerUpdate.setOnClickListener {
            firstItem()?.let { item ->
                controller.serverUpdate(
                    key = item.id,
                    request = {
                        delay(800)
                        // 假设服务端返回了新的标题
                        "[服务端@${System.currentTimeMillis() % 100000}]"
                    },
                    transform = { old, resp -> old.copy(title = "$resp ${old.title}") },
                    onSuccess = { toast("服务端改 OK，响应=$it") },
                    onFailure = { toast("服务端改 失败：${it.message}") }
                )
                toast("发起服务端改请求，800ms 后落地")
            }
        }
        // serverDelete：等接口返回 OK 才本地删除
        btnServerDelete.setOnClickListener {
            firstItem()?.let { item ->
                controller.serverDelete(
                    key = item.id,
                    request = { delay(800); "deleted" },
                    onSuccess = { toast("服务端删 OK：$it") },
                    onFailure = { toast("服务端删 失败：${it.message}") }
                )
                toast("发起服务端删请求，800ms 后真正消失")
            }
        }
        // serverInsertHead：等接口返回真实 item 后再插入
        btnServerInsertHead.setOnClickListener {
            controller.serverInsertHead(
                scheduleKey = "post_fake_item",
                request = {
                    delay(800)
                    // 假装这是服务端返回的真实数据（含 id）
                    val now = System.currentTimeMillis()
                    Triple(now, "✉ 服务端插入 $now", "id 由服务端生成")
                },
                mapper = { (id, title, sub) -> FakeItem(id = id, title = title, subtitle = sub) },
                onSuccess = { toast("服务端插入 OK") },
                onFailure = { toast("服务端插入 失败：${it.message}") }
            )
            toast("发起服务端插入请求，800ms 后真插入")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 1) 控制类 8 个方法
    // ─────────────────────────────────────────────────────────────
    private fun bindControlButtons() = with(binding) {
        btnRefresh.setOnClickListener {
            controller.refresh()
            toast("controller.refresh()")
        }
        btnRetry.setOnClickListener {
            controller.retry()
            toast("controller.retry()")
        }
        btnAutoRefresh.setOnClickListener {
            controller.autoRefresh()
            toast("controller.autoRefresh()")
        }
        btnScrollTop.setOnClickListener {
            controller.scrollToTop()
            toast("controller.scrollToTop()")
        }
        btnSnapshot.setOnClickListener {
            val snap = controller.snapshot()
            toast("snapshot.size=${snap.size}, 第 0 条=${snap.items.firstOrNull()?.title}")
        }
        btnItemCount.setOnClickListener {
            toast("itemCount=${controller.itemCount()}")
        }
        btnClear.setOnClickListener {
            controller.clear()
            toast("controller.clear()  列表已清空")
        }
        btnCancelAll.setOnClickListener {
            controller.cancelAllRequests()
            toast("controller.cancelAllRequests()")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2) 直接更新类 10 个方法（不走网络，立即生效）
    // ─────────────────────────────────────────────────────────────
    private fun bindDirectButtons() = with(binding) {
        // update：改字段，必须 copy；用 updateAndGet 能同步拿到新值
        btnUpdate.setOnClickListener {
            firstItem()?.let { item ->
                val updated = controller.updateAndGet(item.id) { it.copy(title = "★ ${it.title}") }
                toast("updateAndGet → 新标题=${updated?.title}")
            }
        }
        // replace：整条替换
        btnReplace.setOnClickListener {
            firstItem()?.let { item ->
                val replaced =
                    item.copy(title = "已被 replace 整条替换", subtitle = "id=${item.id}")
                controller.replace(item.id, replaced)
                toast("replace id=${item.id}")
            }
        }
        // updateAll：批量改，满足 predicate 的 item 全部打补丁
        btnUpdateAll.setOnClickListener {
            controller.updateAll(
                predicate = { !it.title.startsWith("✓") },
                transform = { it.copy(title = "✓ ${it.title}") }
            )
            toast("updateAll  所有未带 ✓ 的标题加上 ✓")
        }
        // delete(key)：单条删除
        btnDelete.setOnClickListener {
            firstItem()?.let { item ->
                lastDeletedKey = item.id
                controller.delete(item.id)
                toast("delete id=${item.id}（点 undelete 可撤回）")
            }
        }
        // delete(keys: Collection)：批量删除
        btnDeleteMany.setOnClickListener {
            val keys = controller.snapshot().items.take(3).map { it.id }
            if (keys.isEmpty()) {
                toast("没有数据"); return@setOnClickListener
            }
            controller.delete(keys)
            toast("delete×3  keys=$keys")
        }
        // undelete：撤回单条删除
        btnUndelete.setOnClickListener {
            val key = lastDeletedKey
            if (key == null) {
                toast("先点 delete 再 undelete"); return@setOnClickListener
            }
            controller.undelete(key)
            toast("undelete id=$key")
        }
        // insertHead(item)：头部插入一条
        btnInsertHead.setOnClickListener {
            val now = System.currentTimeMillis()
            controller.insertHead(
                FakeItem(id = -now, title = "🆕 头部插入 $now", subtitle = "insertHead 单条")
            )
            toast("insertHead 单条")
        }
        // insertHead(items: List)：头部插入多条（最新在最上）
        btnInsertHeadList.setOnClickListener {
            val base = System.currentTimeMillis()
            val items = (1..3).map {
                FakeItem(id = -(base + it), title = "🆕 批量 $it", subtitle = "insertHead 批量")
            }
            controller.insertHead(items)
            toast("insertHead×3")
        }
        // unpatch：撤销某条补丁，回到服务端原值
        btnUnpatch.setOnClickListener {
            firstItem()?.let { item ->
                controller.unpatch(item.id)
                toast("unpatch id=${item.id}  恢复服务端原始值")
            }
        }
        // clearLocalChanges：清空所有本地补丁
        btnClearLocal.setOnClickListener {
            controller.clearLocalChanges()
            toast("clearLocalChanges  所有本地修改已清空")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3) 乐观更新类 4 个方法（含 cancelRequest by key）
    // ─────────────────────────────────────────────────────────────
    private fun bindOptimisticButtons() = with(binding) {
        // optimisticUpdate（成功）
        btnOptUpdateOk.setOnClickListener {
            firstItem()?.let { item ->
                controller.optimisticUpdate(
                    key = item.id,
                    transform = { it.copy(title = "✦ ${it.title}（optUpd 中…）") },
                    request = { fakeApi(success = true, delayMs = 800); "ok" },
                    onSuccess = { toast("optUpd OK：服务端返回=$it") },
                    onFailure = { toast("optUpd Fail：${it.message}") }
                )
                toast("optUpd 已乐观更新，800ms 后回调")
            }
        }
        // optimisticUpdate（失败 → 自动回退）
        btnOptUpdateFail.setOnClickListener {
            firstItem()?.let { item ->
                controller.optimisticUpdate(
                    key = item.id,
                    transform = { it.copy(title = "✦ ${it.title}（即将失败回退）") },
                    request = { fakeApi(success = false, delayMs = 800); "ok" },
                    onSuccess = { toast("不应到这") },
                    onFailure = { toast("optUpd 失败已回退：${it.message}") }
                )
                toast("optUpd 已乐观更新，预计 800ms 后回退")
            }
        }
        // optimisticUpdate + Throttle policy（连点验证 onIgnored）
        btnOptUpdateThrottle.setOnClickListener {
            firstItem()?.let { item ->
                controller.optimisticUpdate(
                    key = item.id,
                    transform = { it.copy(title = "⚡ ${it.title}（throttle 1.5s）") },
                    request = { fakeApi(success = true, delayMs = 500); "ok" },
                    policy = RequestPolicy.Throttle(1500),
                    onSuccess = { toast("optUpd-Throttle OK") },
                    onFailure = { toast("optUpd-Throttle Fail") },
                    onIgnored = { toast("optUpd-Throttle 被节流忽略 ⏭") }
                )
            }
        }
        // optimisticDelete
        btnOptDelete.setOnClickListener {
            firstItem()?.let { item ->
                controller.optimisticDelete(
                    key = item.id,
                    request = { fakeApi(success = true, delayMs = 800); "deleted" },
                    onSuccess = { toast("optDel OK：$it") },
                    onFailure = { toast("optDel 失败已 undelete：${it.message}") }
                )
                toast("optDel 已先删除，800ms 后服务端回调")
            }
        }
        // optimisticInsertHead（用 Latest 策略 + 长延迟方便 cancelKey 验证）
        btnOptInsertHead.setOnClickListener {
            val now = System.currentTimeMillis()
            val item =
                FakeItem(id = -now, title = "🆕 opt 插入（5s 内可取消）", subtitle = "optInsertHead")
            pendingOptKey = item.id
            controller.optimisticInsertHead(
                item = item,
                request = { fakeApi(success = true, delayMs = 5_000); "ok" },
                policy = RequestPolicy.Latest,
                onSuccess = { toast("optInsHead OK，key=${item.id}") },
                onFailure = { toast("optInsHead Fail：${it.message}") }
            )
            toast("optInsHead 已插入，5s 内点 cancelKey 可取消")
        }
        // cancelRequest(key)
        btnCancelKey.setOnClickListener {
            val key = pendingOptKey
            if (key == null) {
                toast("先点 optInsertHead 再来"); return@setOnClickListener
            }
            controller.cancelRequest(key)
            toast("cancelRequest(key=$key)")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────
    private fun firstItem(): FakeItem? {
        val item = controller.snapshot().items.firstOrNull()
        if (item == null) toast("当前没有数据，等加载完成或下拉刷新")
        return item
    }

    private suspend fun fakeApi(success: Boolean, delayMs: Long): Boolean {
        delay(delayMs)
        if (!success) error("mock 服务端错误")
        return true
    }

    private fun toast(msg: String) {
        Log.d("PagingDemo", msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun tag(state: LoadState): String = when (state) {
        is LoadState.Loading -> "Loading"
        is LoadState.Error -> "Error"
        is LoadState.NotLoading -> "NotLoading(end=${state.endOfPaginationReached})"
    }
}
