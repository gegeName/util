package com.chat.myapplication.sample

import kotlinx.coroutines.delay

/**
 * 假"聊天历史"接口。
 *
 * 用来给 [BidirectionalChatViewModel] 演示双向 paging:
 * - 内部存 100 条历史消息,按时间从老到新排(index 0 是最老的一条)
 * - [latest] 返回最新一批 N 条(对应 PagingSource 的 REFRESH)
 * - [olderBatch] 按"距离最新批次几批"取更老的批次,offset=1 是 latest 之前那一批
 *
 * 之所以这样设计,是因为双向 paging 里 `page` 是个抽象 key,业务负责把它翻译成
 * 实际服务端请求。我们让 `offset = -page + 1`:
 * - REFRESH 在 page=1 → 走 latest
 * - PREPEND key=0 → offset=1 → 第二新一批
 * - PREPEND key=-1 → offset=2 → 第三新一批
 * - ...
 * - offset=5 已经触底,服务端 hasMore=false
 */
class FakeChatHistoryApi {

    private val baseTime = System.currentTimeMillis() - 100 * 60_000L

    /** 全部 100 条历史,index 0 最老, 99 最新 */
    private val all: List<ChatMsg> = (0 until 100).map { i ->
        ChatMsg(
            id = "hist_${1000 + i}",
            text = "历史 #${i + 1}",
            fromMe = (i % 3 == 0),                              // 1/3 是自己发的
            createTime = baseTime + i * 60_000L
        )
    }

    /** 最新一批 N 条(自然顺序: 越靠后越新) */
    suspend fun latest(size: Int): List<ChatMsg> {
        delay(300L)
        return all.takeLast(size)
    }

    /**
     * 按相对偏移取更老的批次。
     *
     * @param offset 1 = latest 前一批, 2 = 前前一批, ...
     * @return data + hasMoreOlder("再前面"还有没有数据)
     */
    suspend fun olderBatch(offset: Int, size: Int): Pair<List<ChatMsg>, Boolean> {
        delay(300L)
        val endIdx = all.size - offset * size                   // offset=1: 80, 2: 60, ...
        if (endIdx <= 0) return emptyList<ChatMsg>() to false   // 越界了, 没历史可拉
        val startIdx = (endIdx - size).coerceAtLeast(0)
        val batch = all.subList(startIdx, endIdx)
        val hasMoreOlder = startIdx > 0
        return batch to hasMoreOlder
    }
}
