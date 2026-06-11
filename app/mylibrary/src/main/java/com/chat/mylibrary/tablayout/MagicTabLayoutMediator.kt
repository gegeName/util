package com.chat.mylibrary.tablayout

import androidx.viewpager2.widget.ViewPager2

/**
 * 把 [MagicTabLayout] 和 [ViewPager2] 双向绑定。
 *
 * 用法：
 * ```
 * MagicTabLayoutMediator(tabLayout, viewPager) { position ->
 *     titles[position]
 * }.attach()
 * ```
 *
 * 调用方需保证调用 [attach] 时 ViewPager2 已有 Adapter 且 itemCount 与 titles 一致。
 */
class MagicTabLayoutMediator(
    private val tabLayout: MagicTabLayout,
    private val viewPager: ViewPager2,
    private val titleProvider: (Int) -> CharSequence,
) {

    private var pageCallback: ViewPager2.OnPageChangeCallback? = null
    private var tabListener: ((Int) -> Unit)? = null
    private var attached = false

    fun attach() {
        check(!attached) { "MagicTabLayoutMediator already attached." }
        val adapter = viewPager.adapter
            ?: error("ViewPager2 has no adapter — attach an adapter first.")
        val count = adapter.itemCount
        val titles = (0 until count).map(titleProvider)
        tabLayout.setTitles(titles)

        // 点击 Tab 触发切换动画时，让 VP2 立即瞬切到目标页（不滚过中间页），避免
        // "跨多 item 点击时 VP2 一页一页滑过来" 的差体验。
        // MagicTabLayout 自己的 ValueAnimator 仍然平滑滑 indicator，VP2 瞬切引发的
        // onPageSelected 会被 isRunning 保护挡住，不会打断 indicator 动画。
        tabLayout.onJumpUnderlyingPager = { position ->
            if (viewPager.currentItem != position) {
                viewPager.setCurrentItem(position, false)
            }
        }

        pageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                tabLayout.onPageScrolled(position, positionOffset)
            }
            override fun onPageSelected(position: Int) {
                tabLayout.onPageSelected(position)
            }
        }.also { viewPager.registerOnPageChangeCallback(it) }

        // 兜底：用户调 selectTab(pos, smooth=false) 走 jumpTo 时 onJumpUnderlyingPager 不会触发；
        // 此处在选中事件 dispatch 时再同步一下 VP2，同样用瞬切而非平滑滚动。
        val listener: (Int) -> Unit = { position ->
            if (viewPager.currentItem != position) {
                viewPager.setCurrentItem(position, false)
            }
        }
        tabLayout.addOnTabSelectedListener(listener)
        tabListener = listener

        // 同步初始位置
        tabLayout.selectTab(viewPager.currentItem, smooth = false)
        attached = true
    }

    fun detach() {
        if (!attached) return
        pageCallback?.let { viewPager.unregisterOnPageChangeCallback(it) }
        tabListener?.let { tabLayout.removeOnTabSelectedListener(it) }
        tabLayout.onJumpUnderlyingPager = null
        pageCallback = null
        tabListener = null
        attached = false
    }
}
