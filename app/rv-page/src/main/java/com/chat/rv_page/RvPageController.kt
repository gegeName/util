package com.chat.rv_page

import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chat.pagingutil.PagingController
import com.chat.pagingutil.PagingRefreshAdapter

/**
 * [RvPageBuilder].start() 返回的页面级控制句柄。
 *
 * 职责：
 * - 运行时动态增删 / 显隐 section（[addSection] / [removeSection] / [showSection] / [hideSection]）
 * - 拿到内部 [PagingController]（如有分页 section）做局部更新 / 乐观更新
 * - 全页面刷新 [refresh]：等价于触发 onHeaderRefresh + 分页 refresh
 * - 暴露 [recyclerView] / [concatAdapter] 给业务做细粒度操作
 *
 * **section tag 规则**：每个 section 在 builder.sections {} 里声明时可显式传 tag，没传则用
 * `section_$index` 自动生成。运行时增加的 section 必须传 tag，不能重复。
 *
 * **顺序保持**：[hideSection] 不会真删 section，只是从 ConcatAdapter 摘掉；[showSection] 时
 * 按原索引位置回插。[removeSection] 则彻底注销，[showSection] 后该 tag 不再存在。
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
     * @property adapter 实际承载的 RecyclerView.Adapter
     * @property spanFull GridLayoutManager 下是否跨整行（构造时确定，运行时改 LayoutManager 不会重算）
     * @property visible 当前是否挂在 ConcatAdapter 上；[hideSection] 后置 false
     */
    class SectionEntry internal constructor(
        val tag: String,
        val adapter: RecyclerView.Adapter<*>,
        val spanFull: Boolean,
        internal var visible: Boolean = true
    )

    /** RecyclerView 实例；业务可直接 scrollToPosition / 拿 LayoutManager 等 */
    fun recyclerView(): RecyclerView = recyclerView

    /** 拼好的 ConcatAdapter；业务需要嵌套挂在其它容器上时可拿走（一般不用） */
    fun concatAdapter(): ConcatAdapter = concatAdapter

    /** 当前所有 section 的快照（含 hidden），按声明顺序 */
    fun sections(): List<SectionEntry> = entries.toList()

    /** 按 tag 查找原始 Adapter；找不到返回 null */
    fun findAdapter(tag: String): RecyclerView.Adapter<*>? =
        entries.firstOrNull { it.tag == tag }?.adapter

    /** 按 tag 查找 entry，便于读 visible / spanFull */
    fun findEntry(tag: String): SectionEntry? = entries.firstOrNull { it.tag == tag }

    /**
     * 拿到分页 section 的控制句柄（局部更新 / 乐观更新 / 拖动等都在这里）。
     *
     * @param tag 不传时返回唯一那个 paging section 的 controller；多个 paging section 在 builder 阶段
     *            已被拒绝，理论上不会发生
     * @return 没有分页 section 或 tag 不匹配时返回 null
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> paging(tag: String? = null): PagingController<T>? {
        if (tag != null && tag != pagingTag) return null
        return pagingController as? PagingController<T>
    }

    /**
     * 全页面刷新：
     * - 有分页 + refreshAdapter → 触发下拉刷新动画 + autoRefresh
     * - 有分页 无 refreshAdapter → [PagingController.refresh]
     * - 没分页 + refreshAdapter → 走 refreshAdapter.autoRefresh，业务可在 [RvPageBuilder.onHeaderRefresh] 拉数据
     * - 都没 → no-op
     */
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
     * 运行时插入一个新 section。
     *
     * @param tag 唯一标识，重复会抛错
     * @param adapter 实际 Adapter，业务自己 new 好（[SingleItemBindingAdapter] / [BaseListAdapter] / 自定义都行）
     * @param at 插入位置（按 entries 的相对位置算）；-1 = 追加到末尾
     * @param spanFull GridLayoutManager 下是否跨整行；默认 true
     */
    fun addSection(
        tag: String,
        adapter: RecyclerView.Adapter<*>,
        at: Int = -1,
        spanFull: Boolean = true
    ) {
        require(entries.none { it.tag == tag }) { "section tag 重复: $tag" }
        val entryIndex = if (at < 0 || at > entries.size) entries.size else at
        val entry = SectionEntry(tag, adapter, spanFull, visible = true)
        entries.add(entryIndex, entry)
        val concatIndex = concatIndexFor(entry)
        concatAdapter.addAdapter(concatIndex, adapter)
    }

    /**
     * 移除一个 section（彻底注销，不可再 [showSection]）。
     * @param tag 找不到时静默忽略
     */
    fun removeSection(tag: String) {
        val entry = entries.firstOrNull { it.tag == tag } ?: return
        if (entry.visible) concatAdapter.removeAdapter(entry.adapter)
        entries.remove(entry)
    }

    /**
     * 临时隐藏 section（保留登记，可 [showSection] 复显）。
     * @param tag 找不到 / 本已隐藏时静默忽略
     */
    fun hideSection(tag: String) {
        val entry = entries.firstOrNull { it.tag == tag } ?: return
        if (!entry.visible) return
        entry.visible = false
        concatAdapter.removeAdapter(entry.adapter)
    }

    /**
     * 复显之前 [hideSection] 的 section；按 entries 中的原索引位置回插。
     * @param tag 找不到 / 本已可见时静默忽略
     */
    fun showSection(tag: String) {
        val entry = entries.firstOrNull { it.tag == tag } ?: return
        if (entry.visible) return
        entry.visible = true
        concatAdapter.addAdapter(concatIndexFor(entry), entry.adapter)
    }

    /**
     * 计算 entry 在 ConcatAdapter 当前可见序列里的正确插入位置。
     * 即统计 entries 中排在它之前且 visible 的数量。
     */
    private fun concatIndexFor(entry: SectionEntry): Int {
        var count = 0
        for (e in entries) {
            if (e === entry) break
            if (e.visible) count++
        }
        return count
    }
}
