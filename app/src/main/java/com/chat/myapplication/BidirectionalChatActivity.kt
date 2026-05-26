package com.chat.myapplication

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.chat.myapplication.databinding.ActivityChatBinding
import com.chat.myapplication.sample.BidirectionalChatViewModel
import com.chat.myapplication.sample.ChatAdapter
import com.chat.myapplication.sample.ChatMsg
import com.chat.pagingutil.PagingController
import com.chat.pagingutil.PagingHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 双向 paging 演示 —— 跟 [ChatActivity] 形成对照。
 *
 * 这条接入路径关键差异:
 * | 维度 | [ChatActivity] (chatMode) | [BidirectionalChatActivity] (本类) |
 * |---|---|---|
 * | LayoutManager | reverseLayout=true,stackFromEnd=true | 普通 LinearLayoutManager |
 * | 数据顺序 | `[最新, ..., 较早]` (index 0 = 最新) | `[较早, ..., 最新]` (index 0 = 最早) |
 * | 加载历史 | 用户上滑 → APPEND (向后翻页) | 用户上滑 → PREPEND (向前翻页) |
 * | 新消息接入 | `insertHead` | `insertTail` |
 * | PagingSource | 单向 fetch 即可 | 双向 fetchBidirectional / 三参 lambda |
 * | 初始位置 | stackFromEnd 自动贴底 | 需手动 scrollToPosition(itemCount - 1) |
 *
 * 用哪一种主要看团队 / 设计风格,功能上完全等价。
 * reverseLayout 派更省心(初始位置 / 新消息冒出动画都是免费的),
 * 自然顺序派 (本类) 跟 PagingSource 的常规接口形态更对得上,适合接已有"前向 + 后向"双接口的服务端。
 */
class BidirectionalChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val vm: BidirectionalChatViewModel by viewModels()
    private lateinit var controller: PagingController<ChatMsg>

    /** 跟踪用户是否当前停在列表底部,只在停在底部时新消息到达自动滚底,否则不打扰阅读历史 */
    private var stickToBottom = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val adapter = ChatAdapter(onRetryClick = { failed ->
            vm.retrySend(failed.id, failed.text)
        })

        // 双向方案: 不开 chatMode, 用普通 LinearLayoutManager
        controller = PagingHelper.with<ChatMsg>(this)
            .recyclerView(binding.rv)
            .layoutManager(LinearLayoutManager(this))
            .pagingAdapter(adapter)
            .pagingFlow(vm.pagingFlow)
            .patcher(vm.patcher)
            .start()

        // 初始进入定位到底部(自然顺序下不会自动贴底,要手动)。
        // 用 loadStateFlow 收到第一次非 Loading 状态时滚一次就够,后续保持不动。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var initialScrolled = false
                adapter.loadStateFlow.collectLatest { state ->
                    val refreshNotLoading =
                        state.refresh !is androidx.paging.LoadState.Loading
                    if (!initialScrolled && refreshNotLoading && adapter.itemCount > 0) {
                        binding.rv.scrollToPosition(adapter.itemCount - 1)
                        initialScrolled = true
                    }
                }
            }
        }

        // 监听用户滚动行为, 判断是否停在底部
        binding.rv.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                rv: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                stickToBottom = !rv.canScrollVertically(1)   // 1 = 向下,false 即已到底
            }
        })

        binding.btnIncoming.setOnClickListener {
            vm.simulateIncoming()
            // 新消息插到尾部之后,若用户当前已在底部就自动滚底,否则保持阅读位置不打扰
            if (stickToBottom) binding.rv.post {
                binding.rv.smoothScrollToPosition(controller.itemCount() - 1)
            }
        }

        binding.btnSend.setOnClickListener { trySend() }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                trySend(); true
            } else false
        }
    }

    private fun trySend() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        vm.fakeSend(text)
        binding.etInput.text.clear()
        // 自己发的消息一定要滚到底
        stickToBottom = true
        binding.rv.post {
            binding.rv.smoothScrollToPosition(controller.itemCount() - 1)
        }
    }
}
