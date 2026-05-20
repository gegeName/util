package com.hifylive.myapplication.sample

import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import com.hifylive.myapplication.databinding.ItemChatMeBinding
import com.hifylive.myapplication.databinding.ItemChatOtherBinding
import com.lhj.pagingutil.BaseMultiPagingAdapter

/**
 * 聊天列表 Adapter。
 *
 * 两种 itemType 通过 [ChatMsg.itemType] 派发:
 * - [ChatMsg.TYPE_OTHER] → 左侧灰色气泡
 * - [ChatMsg.TYPE_ME] → 右侧蓝色气泡, 同时根据 [ChatMsg.status] 显示发送中转圈 / 失败感叹号
 *
 * 失败状态的点击回调通过构造参数 [onRetryClick] 传出去, 让 Activity / VM 决定怎么重发,
 * 保持 Adapter 跟业务解耦。
 */
class ChatAdapter(
    private val onRetryClick: (ChatMsg) -> Unit
) : BaseMultiPagingAdapter<ChatMsg>(DIFF) {

    init {
        addType<ItemChatOtherBinding>(typeValue = ChatMsg.TYPE_OTHER) { b, msg, _ ->
            b.tvBubble.text = msg.text
        }

        addType<ItemChatMeBinding>(typeValue = ChatMsg.TYPE_ME) { b, msg, _ ->
            b.tvBubble.text = msg.text
            // 根据 status 切换右侧状态指示
            b.pbSending.isVisible = msg.status == ChatMsg.Status.SENDING
            b.ivFailed.isVisible = msg.status == ChatMsg.Status.FAILED
            // 失败感叹号点击触发重发(View 复用要每次 bind 都重设监听)
            b.ivFailed.setOnClickListener {
                if (msg.status == ChatMsg.Status.FAILED) onRetryClick(msg)
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ChatMsg>() {
            override fun areItemsTheSame(old: ChatMsg, new: ChatMsg) = old.id == new.id
            override fun areContentsTheSame(old: ChatMsg, new: ChatMsg) = old == new
        }
    }
}
