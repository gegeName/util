package com.hifylive.myapplication.sample

import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import com.hifylive.myapplication.databinding.ItemChatMeBinding
import com.hifylive.myapplication.databinding.ItemChatOtherBinding
import com.chat.pagingutil.BaseMultiPagingAdapter

/**
 * 聊天列表 Adapter。
 *
 * @param onRetryClick 失败状态气泡点击重发回调
 */
class ChatAdapter(
    private val onRetryClick: (ChatMsg) -> Unit
) : BaseMultiPagingAdapter<ChatMsg>(DIFF) {

    init {
        addType(typeValue = ChatMsg.TYPE_OTHER, inflate = ItemChatOtherBinding::inflate) { b, msg, _ ->
            b.tvBubble.text = msg.text
        }

        addType(typeValue = ChatMsg.TYPE_ME, inflate = ItemChatMeBinding::inflate) { b, msg, _ ->
            b.tvBubble.text = msg.text
            b.pbSending.isVisible = msg.status == ChatMsg.Status.SENDING
            b.ivFailed.isVisible = msg.status == ChatMsg.Status.FAILED
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
