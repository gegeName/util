package com.hifylive.myapplication.sample

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chat.pagingutil.PagingPatcher
import com.chat.pagingutil.pagingFlowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 聊天 VM。
 *
 * 持有:
 * - [patcher] 跨 View 重建保留本地补丁(发送中/失败/收到的消息都靠它注入到 paging 流)
 * - [pagingFlow] 历史消息分页流(走 cachedIn(viewModelScope))
 *
 * 暴露三个能力给 Activity:
 * - [simulateIncoming] 模拟对方发来一条消息(往 patcher 头部插)
 * - [fakeSend] 模拟我方发送,带"乐观插入 + 网络延迟 + 概率失败"
 * - [retrySend] 失败后重发(把状态改回 SENDING 再走一遍 fakeSend)
 */
class ChatViewModel : ViewModel() {

    val patcher = PagingPatcher<Any, ChatMsg> { it.id }

    val pagingFlow = pagingFlowOf(pageSize = 20) { ChatPagingSource() }

    private val incomingCounter = AtomicLong(0)

    /** 模拟对方推送一条新消息(WebSocket / IM SDK 收到时调) */
    fun simulateIncoming() {
        val n = incomingCounter.incrementAndGet()
        patcher.insertHead(
            ChatMsg(
                id = "in_${UUID.randomUUID()}",
                text = "对方说: hi #$n",
                fromMe = false,
                createTime = System.currentTimeMillis()
            )
        )
    }

    /**
     * 模拟"我"发一条消息:乐观插入 → 1s 网络 → 80% 成功 / 20% 失败。
     *
     * 成功:用服务端 id 替换临时 id;失败:把临时 item 状态置 FAILED 留在界面等用户重发。
     *
     * @return 临时 id,Activity 把这个 id 跟输入框关联起来,失败时可以点击"重发"
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
        patcher.insertHead(tmp)

        viewModelScope.launch {
            delay(1000L)
            val ok = (0..9).random() < 8         // 80% 成功
            if (ok) {
                // 成功:把临时条目换成"服务端正式条目"
                patcher.removeInsert { it.id == tmpId }
                patcher.insertHead(
                    tmp.copy(
                        id = "srv_${System.currentTimeMillis()}",
                        status = ChatMsg.Status.SENT
                    )
                )
            } else {
                // 失败:在原位置改状态为 FAILED,让 UI 显示重发按钮
                patcher.patch(tmpId) { it.copy(status = ChatMsg.Status.FAILED) }
            }
        }
        return tmpId
    }

    /** 失败重发:把状态改回 SENDING,然后再起一次 fakeSend 的协程逻辑 */
    fun retrySend(failedId: String, text: String) {
        // 撤掉失败的本地补丁,让条目回到 SENDING(或者直接 patch 也行)
        patcher.patch(failedId) { it.copy(status = ChatMsg.Status.SENDING) }
        viewModelScope.launch {
            delay(1000L)
            val ok = (0..9).random() < 8
            if (ok) {
                patcher.delete(failedId)
                patcher.insertHead(
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
