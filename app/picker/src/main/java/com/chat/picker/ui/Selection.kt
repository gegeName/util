package com.chat.picker.ui

import com.chat.picker.model.MediaEntity

/** 进程内共享的选中集合，避免 Intent 反复传大列表 */
internal object Selection {
    val all = mutableListOf<MediaEntity>()
    val selected = linkedSetOf<MediaEntity>()
    var max: Int = 9

    /** 操作结果：受角标变化影响的 entity 集合（用于精确刷新） */
    data class ToggleResult(
        val accepted: Boolean,
        val affected: List<MediaEntity>,
    )

    private fun key(e: MediaEntity): Long =
        (e.id shl 4) or (e.mediaType.ordinal.toLong() and 0xF)

    fun clear() {
        all.clear()
        selected.clear()
    }

    /**
     * 切换选中态，并返回所有"角标会改变"的 entity：
     * - 新增选中：仅该 item（角标从空变为 N）
     * - 取消选中：该 item + 其后的所有已选项（后续项角标 -1）
     * - 超出上限：accepted=false，affected 空
     *
     * 判定用 id+mediaType key（非 equals），让外部传入的 preSelected entity
     * （字段可能与查询返回的不全等）也能正确识别为已选。
     */
    fun toggle(item: MediaEntity): ToggleResult {
        val k = key(item)
        val ordered = selected.toList()
        val idx = ordered.indexOfFirst { key(it) == k }
        return if (idx >= 0) {
            selected.remove(ordered[idx])
            ToggleResult(true, ordered.subList(idx, ordered.size).toList())
        } else {
            if (selected.size >= max) ToggleResult(false, emptyList())
            else {
                selected.add(item)
                ToggleResult(true, listOf(item))
            }
        }
    }

    fun indexOf(item: MediaEntity): Int {
        val k = key(item)
        var i = 0
        for (e in selected) {
            i++
            if (key(e) == k) return i
        }
        return -1
    }

    /**
     * 预填充已选项（来自外部 preSelected 配置）。按 key 去重，不触发上限。
     * 注意：caller 应保证 items.size <= max，否则后续 toggle 时会拒绝新增。
     */
    fun preSelect(items: Collection<MediaEntity>) {
        if (items.isEmpty()) return
        val existed = HashSet<Long>(selected.size * 2 + items.size).apply {
            selected.forEach { add(key(it)) }
        }
        items.forEach { e ->
            if (existed.add(key(e))) selected.add(e)
        }
    }
}
