package com.chat.myapplication.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chat.pagingutil.BasePagingSource
import com.chat.pagingutil.PagingPatcher
import com.chat.pagingutil.pagingFlowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 双向 paging 演示 VM —— 跟 [ChatViewModel] 形成对比。
 *
 * 关键差异(数据/视觉都跟 [ChatViewModel] 反过来):
 * - 数据顺序: **[较早, ..., 最新]** (自然时间序, index 0 最老, index N 最新)
 * - PagingHelper 不开 chatMode, RecyclerView 用普通 LinearLayoutManager
 * - 加载更早历史靠 **PREPEND** (用户向上滚到顶部触发), 不是 APPEND
 * - 新消息进 patcher 走 [PagingPatcher.insertTail] (尾部 = 视觉底部 = 最新)
 *
 * 关键 API: 双向 `pagingFlowOf` lambda 拿三个参数 `(page, size, direction)` 返回 [PageResult],
 * `hasPrev=true` 是开启 PREPEND 触发的关键开关。
 */
class BidirectionalChatViewModel : ViewModel() {

    val patcher = PagingPatcher<Any, ChatMsg> { it.id }

    private val api = FakeChatHistoryApi()

    /**
     * 双向 paging flow.
     *
     * lambda 形参个数 = 3 → Kotlin 自动选中 [pagingFlowOf] 的双向重载,内部用
     * `BidirectionalLambdaPagingSource` 把它包成 [BasePagingSource]。
     *
     * @param page Paging 库传过来的抽象 key。REFRESH 时 = `startPage = 1`,
     *             后续 PREPEND/APPEND 自动 ±1。
     * @param size 期望条数 (= PagingConfig.pageSize)
     * @param direction REFRESH / PREPEND / APPEND
     * @return [PageResult] 三件套: data, hasMore, hasPrev
     */
    val pagingFlow = pagingFlowOf(pageSize = 20) { page, size, direction, prependIndex ->
        Log.e("BidirectionalChatViewModel", ":page=${page} prependIndex=$prependIndex")
        when (direction) {
            BasePagingSource.LoadDirection.REFRESH -> {
                val data = api.latest(size)
                BasePagingSource.PageResult(
                    data = data,
                    hasMore = false,
                    hasPrev = data.size == size
                )
            }

            BasePagingSource.LoadDirection.PREPEND -> {
                val (data, hasMoreOlder) = api.olderBatch(prependIndex, size)
                BasePagingSource.PageResult(data = data, hasMore = hasMoreOlder)
            }

            BasePagingSource.LoadDirection.APPEND -> {
                BasePagingSource.PageResult(emptyList(), hasMore = false)
            }
        }
    }

    private val incomingCounter = AtomicLong(0)

    /** 模拟收到新消息: 走 [PagingPatcher.insertTail] 追加到尾部(视觉底部) */
    fun simulateIncoming() {
        val n = incomingCounter.incrementAndGet()
        patcher.insertTail(
            ChatMsg(
                id = "in_${UUID.randomUUID()}",
                text = "对方说: hello #$n",
                fromMe = false,
                createTime = System.currentTimeMillis()
            )
        )
    }

    /**
     * 我方发送: 同样走 insertTail, 因为自然顺序里新消息也在底部。
     * 这次直接用框架的 [optimisticInsertTail] 等价模板(写在 VM 而非 controller),
     * 失败回退由 patcher 的 removeTailInsert 处理。
     */
    fun fakeSend(text: String): String {
        val tmpId = "local_${UUID.randomUUID()}"
        val tmp = ChatMsg(
            id = tmpId,
            text = text,
            fromMe = true,
            createTime = System.currentTimeMillis(),
            status = ChatMsg.Status.SENDING
        )
        patcher.insertTail(tmp)

        viewModelScope.launch {
            delay(1000L)
            val ok = (0..9).random() < 8
            if (ok) {
                patcher.removeTailInsert { it.id == tmpId }
                patcher.insertTail(
                    tmp.copy(
                        id = "srv_${System.currentTimeMillis()}",
                        status = ChatMsg.Status.SENT
                    )
                )
            } else {
                patcher.patch(tmpId) { it.copy(status = ChatMsg.Status.FAILED) }
            }
        }
        return tmpId
    }

    fun retrySend(failedId: String, text: String) {
        patcher.patch(failedId) { it.copy(status = ChatMsg.Status.SENDING) }
        viewModelScope.launch {
            delay(1000L)
            val ok = (0..9).random() < 8
            if (ok) {
                patcher.delete(failedId)
                patcher.insertTail(
                    ChatMsg(
                        id = "srv_${System.currentTimeMillis()}",
                        text = text,
                        fromMe = true,
                        createTime = System.currentTimeMillis(),
                        status = ChatMsg.Status.SENT
                    )
                )
            } else {
                patcher.patch(failedId) { it.copy(status = ChatMsg.Status.FAILED) }
            }
        }
    }
}
