package com.chat.rv_page

import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chat.pagingutil.*

/**
 * [RvPageBuilder].start() 返回的页面级控制句柄。
 *
 * - 运行时动态增删 / 显隐 section([addSection] / [removeSection] / [showSection] / [hideSection])
 * - 拿到内部 [PagingController](如有分页 section)做局部 / 乐观更新
 * - 全页面刷新 [refresh] = onHeaderRefresh + paging refresh
 *
 * tag 规则:同一页面内唯一,不传则自动生成 `section_$index`。
 */
class RvPageController internal constructor(
    private val recyclerView: RecyclerView,
    private val concatAdapter: ConcatAdapter,
    private val entries: MutableList<SectionEntry>,
    private val pagingController: PagingController<*>?,
    private val pagingTag: String?,
    private val refreshAdapter: PagingRefreshAdapter?
) {

    /**
     * Section 在 controller 内部的登记项。
     * @property tag 唯一标识
     * @property adapter 业务面对的原始 Adapter([findAdapter] 也返回这个)
     * @property spanFull GridLayoutManager 下是否跨整行
     * @property visible [hideSection] 后置 false
     * @property concatEntry 装到 ConcatAdapter 的 adapter:static 是 [GatingAdapter] 包装 [adapter],paging = adapter
     */
    class SectionEntry internal constructor(
        val tag: String,
        val adapter: RecyclerView.Adapter<*>,
        val spanFull: Boolean,
        internal var visible: Boolean = true,
        internal val concatEntry: RecyclerView.Adapter<*> = adapter
    )

    fun recyclerView(): RecyclerView = recyclerView

    fun concatAdapter(): ConcatAdapter = concatAdapter

    /** 当前所有 section 的快照(含 hidden),按声明顺序。 */
    fun sections(): List<SectionEntry> = entries.toList()

    /** 按 tag 查找业务原始 Adapter;找不到返回 null。 */
    fun findAdapter(tag: String): RecyclerView.Adapter<*>? =
        entries.firstOrNull { it.tag == tag }?.adapter

    fun findEntry(tag: String): SectionEntry? = entries.firstOrNull { it.tag == tag }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> paging(tag: String? = null): PagingController<T>? {
        if (tag != null && tag != pagingTag) return null
        return pagingController as? PagingController<T>
    }

    fun refresh() {
        if (pagingController != null && refreshAdapter != null) {
            refreshAdapter.autoRefresh()
        } else if (pagingController != null) {
            pagingController.refresh()
        } else {
            refreshAdapter?.autoRefresh()
        }
    }

    /**
     * 运行时插入新 section。adapter 会被 [GatingAdapter] 包装挂到 ConcatAdapter,
     * 后续 hideSection / showSection 走闸门切换,与 PagingHelper 的 aux 摘/插不冲突。
     */
    fun addSection(
        tag: String,
        adapter: RecyclerView.Adapter<*>,
        at: Int = -1,
        spanFull: Boolean = true
    ) {
        require(entries.none { it.tag == tag }) { "section tag 重复: $tag" }
        val entryIndex = if (at < 0 || at > entries.size) entries.size else at
        @Suppress("UNCHECKED_CAST")
        val gate = GatingAdapter(adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>)
        val entry = SectionEntry(tag, adapter, spanFull, visible = true, concatEntry = gate)
        entries.add(entryIndex, entry)
        concatAdapter.addAdapter(concatIndexFor(entry), gate)
    }

    /** 永久注销 section。 */
    fun removeSection(tag: String) {
        val entry = entries.firstOrNull { it.tag == tag } ?: return
        runCatching { concatAdapter.removeAdapter(entry.concatEntry) }
        entries.remove(entry)
    }

    /**
     * 临时隐藏:把 [GatingAdapter] 闸门关掉,wrapper 仍留在 ConcatAdapter 占位但 itemCount=0。
     * PagingHelper 的 aux 摘/插不再覆盖隐藏状态。非 gate 路径(理论上只有 paging section)走 concat 摘掉。
     */
    fun hideSection(tag: String) {
        val entry = entries.firstOrNull { it.tag == tag } ?: return
        if (!entry.visible) return
        entry.visible = false
        val gate = entry.concatEntry as? GatingAdapter
        if (gate != null) gate.setVisible(false)
        else runCatching { concatAdapter.removeAdapter(entry.concatEntry) }
    }

    fun showSection(tag: String) {
        val entry = entries.firstOrNull { it.tag == tag } ?: return
        if (entry.visible) return
        entry.visible = true
        val gate = entry.concatEntry as? GatingAdapter
        if (gate != null) gate.setVisible(true)
        else concatAdapter.addAdapter(concatIndexFor(entry), entry.concatEntry)
    }

    /**
     * 计算 entry 在 ConcatAdapter 当前序列里的插入位置。
     * gate 类 entry 永远在 concat 中,所以一律计数;非 gate 仅 visible 时计数。
     */
    private fun concatIndexFor(entry: SectionEntry): Int {
        var count = 0
        for (e in entries) {
            if (e === entry) break
            if (e.concatEntry is GatingAdapter) count++
            else if (e.visible) count++
        }
        return count
    }
}
