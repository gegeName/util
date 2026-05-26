package com.chat.myapplication.sample

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chat.pagingutil.PagingPatcher
import com.chat.pagingutil.pagingFlowOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FakeListViewModel : ViewModel() {

    val patcher = PagingPatcher<Any, FakeItem> { it.id }
    val pagingFlow = pagingFlowOf { FakePagingSource() }

    /**
     * 拖动排序的"上传逻辑"通常要节流：连续拖很多步只发一次接口，避免风暴。
     * 这里用 debounce job 实现 200ms 的合并窗口。
     */
    private var pendingReorderJob: Job? = null

    /**
     * 拖动 callback：顺序数据从 PagingHelper 给的 currentItems 拿（已经是拖动后的最新顺序）。
     *
     * 业务侧两种常见协议都给出示例：
     * 1) [uploadFullOrder] —— 全量上传 [id1, id2, id3, ...]，服务端按数组顺序保存
     * 2) [uploadRelative]  —— 只上传 (movedKey, beforeKey, afterKey)，服务端用相对位置插入
     */
    fun onItemsReordered(currentItems: List<FakeItem>, movedKey: Any, beforeKey: Any?, afterKey: Any?) {
        Log.d("PagingDemo", "onItemsReordered moved=$movedKey before=$beforeKey after=$afterKey  size=${currentItems.size}")
        pendingReorderJob?.cancel()
        pendingReorderJob = viewModelScope.launch {
            delay(200)            // 200ms 防抖：连续小步拖动只发最后一次
            // 二选一调用：
            uploadFullOrder(currentItems.map { it.id })
            // uploadRelative(movedKey as Long, beforeKey as Long?, afterKey as Long?)
        }
    }

    /** 协议 1：全量序号上传 */
    private suspend fun uploadFullOrder(ids: List<Long>) = withContext(Dispatchers.IO) {
        Log.d("PagingDemo", "POST /reorder/full  ids=$ids")
        delay(500)
        Log.d("PagingDemo", "全量上传 OK")
    }

    /** 协议 2：相对位置上传 */
    private suspend fun uploadRelative(movedId: Long, beforeId: Long?, afterId: Long?) = withContext(Dispatchers.IO) {
        Log.d("PagingDemo", "POST /reorder/relative  moved=$movedId before=$beforeId after=$afterId")
        delay(500)
        Log.d("PagingDemo", "相对位置上传 OK")
    }
}
