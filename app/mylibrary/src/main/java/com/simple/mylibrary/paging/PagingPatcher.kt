package com.simple.mylibrary.paging
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.paging.insertFooterItem
import androidx.paging.insertHeaderItem
import androidx.paging.insertSeparators
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update

/**
 * Paging 局部更新补丁器。
 *
 * 通过维护以下本地状态并 combine 到原始 PagingData 流,让 PagingHelper / PagingController
 * 能在不重新拉接口的前提下做局部更新:
 *
 * - **patches**:字段级补丁(key → 变换函数)
 * - **removed**:删除集合(被 key 命中的 item 不再可见)
 * - **head inserts**:头部插入(用于 `[最新, ..., 较早]` 顺序,新消息从头部冒出)
 * - **tail inserts**:尾部插入(用于 `[较早, ..., 最新]` 顺序,新消息追加到末尾)
 * - **tagged inserts**:相对位置插入(在某个 anchorKey 的 BEFORE / AFTER 位置插入)
 *
 * @param K item 唯一键类型(如 userId: String)
 * @param T item 类型
 */
class PagingPatcher<K : Any, T : Any>(val keyOf: (T) -> K) {

    /** 相对位置插入的方向 */
    enum class InsertPosition { BEFORE, AFTER }

    /**
     * 相对位置插入的描述。
     *
     * @property anchorKey 锚点 item 的 key,插入位置以它为参照
     * @property position 在 anchor 之前还是之后插入
     * @property item 要插入的新 item
     */
    data class TaggedInsert<K : Any, T : Any>(
        val anchorKey: K,
        val position: InsertPosition,
        val item: T
    )

    /**
     * 内部用:把三种 insert 列表聚合在一个 state 里,
     * 避免 combine 超过 5 个 flow 的上限。
     */
    private data class InsertState<K : Any, T : Any>(
        val heads: List<T> = emptyList(),
        val tails: List<T> = emptyList(),
        val tagged: List<TaggedInsert<K, T>> = emptyList()
    )

    private val _patches = MutableStateFlow<Map<K, (T) -> T>>(emptyMap())
    private val _removed = MutableStateFlow<Set<K>>(emptySet())
    private val _inserts = MutableStateFlow(InsertState<K, T>())

    fun wrap(source: Flow<PagingData<T>>): Flow<PagingData<T>> =
        combine(source, _patches, _removed, _inserts) { data, patches, removed, inserts ->
            var out: PagingData<T> = data
                .filter { keyOf(it) !in removed }
                .map { item -> patches[keyOf(item)]?.invoke(item) ?: item }

            inserts.heads.reversed()
                .asSequence()
                .filter { keyOf(it) !in removed }
                .map { item -> patches[keyOf(item)]?.invoke(item) ?: item }
                .forEach { out = out.insertHeaderItem(item = it) }

            inserts.tails
                .asSequence()
                .filter { keyOf(it) !in removed }
                .map { item -> patches[keyOf(item)]?.invoke(item) ?: item }
                .forEach { out = out.insertFooterItem(item = it) }

            inserts.tagged.forEach { ti ->
                if (ti.anchorKey in removed) return@forEach
                if (keyOf(ti.item) in removed) return@forEach
                val item = patches[keyOf(ti.item)]?.invoke(ti.item) ?: ti.item
                out = out.insertSeparators { before, after ->
                    when (ti.position) {
                        InsertPosition.BEFORE ->
                            if (after != null && keyOf(after) == ti.anchorKey) item else null
                        InsertPosition.AFTER ->
                            if (before != null && keyOf(before) == ti.anchorKey) item else null
                    }
                }
            }

            out
        }

    /** 给某个 key 打字段级补丁;多次 patch 同一个 key 后写覆盖前写 */
    fun patch(key: K, transform: (T) -> T) {
        _patches.update { it + (key to transform) }
    }

    /** 撤销某 key 的补丁,让该 item 回到服务端原始值 */
    fun unpatch(key: K) {
        _patches.update { it - key }
    }

    fun delete(key: K) {
        _removed.update { it + key }
    }

    fun undelete(key: K) {
        _removed.update { it - key }
    }

    // ───── 头部插入 ─────

    /** 头部插入;多次调用按"最新在最上"排列 */
    fun insertHead(item: T) {
        _inserts.update { it.copy(heads = listOf(item) + it.heads) }
    }

    /** 按谓词移除已插入的头部临时条目(乐观插入失败回退用) */
    fun removeInsert(predicate: (T) -> Boolean) {
        _inserts.update { it.copy(heads = it.heads.filterNot(predicate)) }
    }

    // ───── 尾部插入 ─────

    /**
     * 尾部插入;多次调用按"最新在最下"排列。
     *
     * 适用 `[较早, ..., 最新]` 自然时间序的聊天 / 时间轴,新消息追加到列表末尾。
     */
    fun insertTail(item: T) {
        _inserts.update { it.copy(tails = it.tails + item) }
    }

    /** 按谓词移除已插入的尾部临时条目(乐观尾部插入失败回退用) */
    fun removeTailInsert(predicate: (T) -> Boolean) {
        _inserts.update { it.copy(tails = it.tails.filterNot(predicate)) }
    }

    // ───── 相对位置插入 ─────

    /**
     * 在 [anchorKey] 锚点**之后**插入 [item]。
     *
     * 适用:在已存在的某条消息后插入系统消息("xx 撤回了一条消息" / 时间分隔符 /
     * 在某条评论下插入子评论等)。
     *
     * 注意:
     * - anchorKey 必须当前可见(没被 [delete]);若 anchor 已被删除本次插入被跳过
     * - 同一 anchorKey 多次 [insertAfter],后调用的更靠近 anchor(LIFO 视觉顺序)
     */
    fun insertAfter(anchorKey: K, item: T) {
        _inserts.update {
            it.copy(tagged = it.tagged + TaggedInsert(anchorKey, InsertPosition.AFTER, item))
        }
    }

    /**
     * 在 [anchorKey] 锚点**之前**插入 [item]。
     *
     * 适用:在某条消息上方插入分组标题 / "未读消息分隔线" / 群公告刷出等。
     * anchor 约束同 [insertAfter]。
     */
    fun insertBefore(anchorKey: K, item: T) {
        _inserts.update {
            it.copy(tagged = it.tagged + TaggedInsert(anchorKey, InsertPosition.BEFORE, item))
        }
    }

    /** 按谓词移除相对位置插入(乐观插入失败回退用) */
    fun removeTaggedInsert(predicate: (TaggedInsert<K, T>) -> Boolean) {
        _inserts.update { it.copy(tagged = it.tagged.filterNot(predicate)) }
    }

    /** 全部清空,下拉刷新时调用,让本地补丁让位给服务端最新数据 */
    fun clearAll() {
        _patches.value = emptyMap()
        _removed.value = emptySet()
        _inserts.value = InsertState()
    }
}
