package com.hifylive.myapplication

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import com.hifylive.myapplication.databinding.ActivityChatBinding
import com.hifylive.myapplication.sample.ChatAdapter
import com.hifylive.myapplication.sample.ChatMsg
import com.hifylive.myapplication.sample.ChatViewModel
import com.simple.mylibrary.paging.CommonLoadStateAdapter
import com.simple.mylibrary.paging.PagingController
import com.simple.mylibrary.paging.PagingHelper

/**
 * 聊天演示 Activity:
 *
 * 接入 PagingHelper 的标准聊天流:
 * - `.chatMode()` 一行开 reverseLayout + stackFromEnd
 * - 数据顺序 `[最新, ..., 较早]`,page 1 = 最新 20 条,APPEND 拉更老
 * - 新消息(对方推送 / 自己发送) → `controller.insertHead(...)`
 *   reverseLayout 下 index 0 = 视觉底部,新消息从底部冒出
 * - 自己发送走"乐观插入 + 网络延迟 + 概率失败"流程,失败时给气泡加红色感叹号供重发
 *
 * 自动滚到最新消息:reverseLayout 下 LayoutManager 默认会保留 anchor 不动 ——
 * 即使 insertHead 把新消息放到 index 0(视觉底部),也不会自动滚到那一条。
 * 这里用 [RecyclerView.AdapterDataObserver] 监听插入 + [stickToBottom] 跟踪滚动状态:
 * - 用户停在底部时,新消息到达自动滚过去
 * - 用户上滑看历史时,新消息**不打扰**,只静静追加到底部下方
 * - 自己发送时强制滚底,因为用户操作意图明确
 *
 * 没有用 `.refreshAdapter(...)`,聊天通常不要下拉刷新(下拉等于"刷新到最新",
 * 但消息列表本来就是最新的,容易误操作)。
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val vm: ChatViewModel by viewModels()
    private lateinit var controller: PagingController<ChatMsg>

    /**
     * 用户是否停在底部。新消息到达时只在 true 才自动滚底,否则保持当前阅读位置。
     *
     * reverseLayout 下,"底部"在 layout 坐标里仍是 canScrollVertically(1)=false 的边界,
     * 这点跟自然顺序的 [BidirectionalChatActivity] 实现一致,语义不需要因 reverseLayout 而反转。
     */
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

        // Adapter 收到"重发"点击后调 VM 的 retrySend, 把临时 id 跟当前 text 关联
        val adapter = ChatAdapter(onRetryClick = { failed ->
            vm.retrySend(failed.id, failed.text)
        })

        // 走标准聊天接入:chatMode + patcher + loadStateFooter(在顶部表示加载历史)
        controller = PagingHelper.with<ChatMsg>(this)
            .recyclerView(binding.rv)
            .chatMode()
            .pagingAdapter(adapter)
            .pagingFlow(vm.pagingFlow)
            .patcher(vm.patcher)
            .loadStateFooter { CommonLoadStateAdapter(onRetry = it) }
            .start()

        // 跟踪滚动状态:用户每次滚动后更新 stickToBottom
        binding.rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                stickToBottom = !rv.canScrollVertically(1)
            }
        })

        // 关键:监听 paging adapter 的插入事件 → 在 index 0 插入新条目时自动滚到底
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                /**
                 * reverseLayout 下 index 0 = 视觉底部 = "最新消息"位置.
                 * 只有在用户当前已经停在底部时才自动滚, 否则不打扰正在看历史的用户.
                 *
                 * binding.rv.post 是必要的:onItemRangeInserted 触发时 RecyclerView 还在
                 * 同一帧的 layout 过程中,直接 scrollToPosition 有概率不生效;post 到下一帧
                 * 就能保证 layout 完成后再滚动.
                 */
                if (positionStart == 0 && stickToBottom) {
                    binding.rv.post { binding.rv.scrollToPosition(0) }
                }
            }
        })

        binding.btnIncoming.setOnClickListener {
            vm.simulateIncoming()
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
        // 自己发送一定要看到自己的消息,强制 stick=true 让 observer 帮我们滚底
        stickToBottom = true
    }
}
