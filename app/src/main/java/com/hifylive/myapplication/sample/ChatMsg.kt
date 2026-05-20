package com.hifylive.myapplication.sample

import com.simple.mylibrary.paging.MultiTypeItem

/**
 * 聊天消息。
 *
 * @property id 消息唯一 id;本地乐观插入用 `local_xxx` 临时 id,服务端返回后替换
 * @property text 文本内容
 * @property fromMe true=自己发的(渲染右侧),false=对方发的(渲染左侧)
 * @property createTime 时间戳(ms),仅供展示与排序
 * @property status 发送状态,只对 [fromMe]=true 的消息有意义
 */
data class ChatMsg(
    val id: String,
    val text: String,
    val fromMe: Boolean,
    val createTime: Long,
    val status: Status = Status.SENT
) : MultiTypeItem {

    enum class Status { SENDING, SENT, FAILED }

    /**
     * BaseMultiPagingAdapter 用这个字段派发布局。返回值跟下面 companion 的常量一致,
     * 同时也是 RecyclerView.viewType。
     */
    override val itemType: Int get() = if (fromMe) TYPE_ME else TYPE_OTHER

    companion object {
        const val TYPE_ME = 1
        const val TYPE_OTHER = 2
    }
}
