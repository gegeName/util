package com.hifylive.myapplication.sample

import com.chat.pagingutil.BasePagingSource
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * 假数据分页源：
 * - 总共 5 页
 * - 每页延迟 600ms 模拟网络请求
 * - 每次刷新都会让 id 递增，方便观察刷新后的数据变化
 */
class FakePagingSource : BasePagingSource<FakeItem>() {

    companion object {
        private const val TOTAL_PAGE = 5
        private const val FAIL_PAGE = 3   // 第 3 页首次加载必失败，点击 footer 重试会成功
        private val seed = AtomicLong(0L)
    }

    private val refreshSeed = seed.incrementAndGet()

    // 记录已尝试过的页码：第一次抛错制造失败态，之后再加载（也就是重试）就走正常流程
    private val attemptedPages = mutableSetOf<Int>()

    override suspend fun fetch(page: Int, pageSize: Int): Pair<List<FakeItem>, Boolean> {
        delay(600L)
        if (page == FAIL_PAGE && attemptedPages.add(page)) {
            error("第 $FAIL_PAGE 页加载失败，点击重试")
        }
        val list = (0 until pageSize).map { i ->
            val index = (page - 1) * pageSize + i
            val id = refreshSeed * 100_000 + index
            val span = when {
                id % 7L == 0L -> 4
                id % 3L == 0L -> 2
                else -> 1
            }
            FakeItem(
                id = id,
                title = "第 $page 页 · 第 ${i + 1} 条",
                subtitle = "id=$id  · span=$span  · refresh#$refreshSeed"
            )
        }
        val hasMore = page < TOTAL_PAGE
        return list to hasMore
    }
}
