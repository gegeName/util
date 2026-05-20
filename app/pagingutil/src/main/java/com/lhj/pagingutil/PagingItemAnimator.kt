package com.lhj.pagingutil

import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Paging 列表推荐默认 ItemAnimator。
 *
 * 与 [DefaultItemAnimator] 的差异：
 * - 关闭"内容变化"动画（局部 update 不再闪白）：[animateChange] 直接 dispatchChangeFinished 并返回 false
 * - 保留 add / remove / move 动画（下拉刷新、lrucache 淘汰、拖动排序等场景视觉仍然顺滑）
 * - supportsChangeAnimations = false，让 RecyclerView 对同一 viewHolder 的内容变化直接复用，不 fade
 *
 * 业务想要完全自定义，实现自己的 [RecyclerView.ItemAnimator] 并通过
 * [PagingHelper.itemAnimator] 注入。想关掉所有动画（比如首屏骨架屏闪烁）就传 null。
 */
class PagingItemAnimator : DefaultItemAnimator() {
    init {
        supportsChangeAnimations = false
    }

    override fun animateChange(
        oldHolder: RecyclerView.ViewHolder,
        newHolder: RecyclerView.ViewHolder,
        preLayoutInfo: ItemHolderInfo,
        postLayoutInfo: ItemHolderInfo
    ): Boolean {
        if (oldHolder === newHolder) {
            dispatchChangeFinished(oldHolder, /*oldItem=*/ true)
        } else {
            dispatchChangeFinished(oldHolder, /*oldItem=*/ true)
            dispatchChangeFinished(newHolder, /*oldItem=*/ false)
        }
        return false
    }
}
