package com.chat.rv_page

import android.view.View

/**
 * 描述某个 adapter position 是否应作为 sticky header 渲染。
 * RvPage 内部由 [RvPageBuilder] 自动构造一个匿名实例,业务无需直接实现 —— 在 section 上声明
 * `stickyHeader = true` 即可。
 */
interface StickyHeaderCallbacks {
    /** 该位置是否是一个 sticky header。 */
    fun isStickyHeader(position: Int): Boolean

    /** view 被提升为 sticky header 时回调,可加阴影 / 背景等。 */
    fun setupStickyHeaderView(stickyHeader: View) {}

    /** view 退出 sticky 状态时回调,撤销 [setupStickyHeaderView] 的副作用。 */
    fun teardownStickyHeaderView(stickyHeader: View) {}
}
