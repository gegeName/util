package com.chat.myapplication.sample

import com.chat.pagingutil.BasePagingSource
import kotlinx.coroutines.delay

/**
 * 假聊天历史源。
 *
 * 数据约定(配合 PagingHelper.chatMode() 用):
 * - 总共 5 页,每页 20 条 → 共 100 条历史消息
 * - **page 1 返回最新一批,page 5 返回最早一批**(整条流是 `[最新, ..., 较早]`)
 * - 单条消息 id 越大代表越新;偶数 id 是"对方"发的,奇数 id 是"我"发的
 * - 每页 300ms 延迟模拟网络
 *
 * 走单向 [fetch] API 就够 —— reverseLayout 下用户向上滚 = paging APPEND = 拉更老的页。
 */
class ChatPagingSource : BasePagingSource<ChatMsg>() {

    override suspend fun fetch(page: Int, pageSize: Int): Pair<List<ChatMsg>, Boolean> {
        delay(300L)
        // page 1 → idStart = 81, page 2 → 61, ... page 5 → 1
        val idStart = (TOTAL_PAGE - page) * pageSize + 1
        val list = (0 until pageSize).map { i ->
            val id = idStart + i
            ChatMsg(
                id = "srv_$id",
                text = "历史消息 #$id",
                fromMe = (id % 2 == 1),
                createTime = System.currentTimeMillis() - (100 - id) * 60_000L
            )
        }.reversed()                          // 单页内也按"新→老"排
        val hasMore = page < TOTAL_PAGE
        return list to hasMore
    }

    companion object {
        private const val TOTAL_PAGE = 5
    }
}
