package com.simple.mylibrary.paging
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.insertHeaderItem
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * Paging 局部更新补丁器。
 *
 * 通过维护三种本地状态（字段补丁 / 删除集合 / 头部插入）并 combine 到原始 PagingData 流，
 * 让 PagingHelper / PagingController 能在不重新拉接口的前提下做局部更新。
 *
 * @param K item 唯一键类型（如 userId: String）
 * @param T item 类型
 */
class PagingPatcher<K : Any, T : Any>(val keyOf: (T) -> K) {

    private val _patches = MutableStateFlow<Map<K, (T) -> T>>(emptyMap())
    private val _removed = MutableStateFlow<Set<K>>(emptySet())
    private val _inserts = MutableStateFlow<List<T>>(emptyList())

    /** 把 patcher 套到原始 paging flow 上 */
    fun wrap(source: Flow<PagingData<T>>): Flow<PagingData<T>> =
        combine(source, _patches, _removed, _inserts) { data, patches, removed, inserts ->
            var out: PagingData<T> = data
                .filter { keyOf(it) !in removed }
                .map { item -> patches[keyOf(item)]?.invoke(item) ?: item }
            inserts.reversed()
                .asSequence()
                .filter { keyOf(it) !in removed }
                .map { item -> patches[keyOf(item)]?.invoke(item) ?: item }
                .forEach { out = out.insertHeaderItem(item = it) }
            out
        }

    /** 给某个 key 打字段级补丁；多次 patch 同一个 key 后写覆盖前写 */
    fun patch(key: K, transform: (T) -> T) {
        _patches.update { it + (key to transform) }
    }

    /** 撤销某 key 的补丁，让该 item 回到服务端原始值 */
    fun unpatch(key: K) {
        _patches.update { it - key }
    }

    fun delete(key: K) {
        _removed.update { it + key }
    }

    fun undelete(key: K) {
        _removed.update { it - key }
    }

    /** 头部插入；多次调用按"最新在最上"排列 */
    fun insertHead(item: T) {
        _inserts.update { listOf(item) + it }
    }

    /** 按谓词移除已插入的临时条目（乐观插入失败回退用） */
    fun removeInsert(predicate: (T) -> Boolean) {
        _inserts.update { it.filterNot(predicate) }
    }

    /** 全部清空，下拉刷新时调用，让本地补丁让位给服务端最新数据 */
    fun clearAll() {
        _patches.value = emptyMap()
        _removed.value = emptySet()
        _inserts.value = emptyList()
    }
}
