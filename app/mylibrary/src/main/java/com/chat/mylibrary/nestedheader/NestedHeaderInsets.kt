package com.chat.mylibrary.nestedheader

import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * NestedHeaderLayout 配合 edge-to-edge / 全面屏的辅助工具。
 *
 * 设计前提：[NestedHeaderLayout] 本身保持 inset-agnostic，不会主动监听 WindowInsets。
 *   - 在传统模式（非 edge-to-edge）下，Activity 内容天然在 system bar 之间，啥都不用动。
 *   - 在 edge-to-edge 模式下，由本工具显式声明"哪些角色要让 / 哪些角色不让 system bar"。
 *
 * 工具决定不内置进 NHL 的原因：
 *   1. 避免和业务在父容器自定义的 OnApplyWindowInsetsListener 冲突，造成"双重 padding"
 *   2. 不夺取 inset 派发，sibling View 仍能正常收
 *   3. 沉浸 vs 下沉策略千变万化，硬编码满足不了所有人
 *
 * --- 推荐用法（"头图沉浸到状态栏后 + Toolbar 内容下沉"） ---
 *
 * ```kotlin
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     enableEdgeToEdge()
 *     binding = ActivityXxxBinding.inflate(layoutInflater)
 *     setContentView(binding.root)
 *
 *     NestedHeaderInsets.applyToCollapsingHeader(
 *         nhl       = binding.nestedHeader,
 *         toolbarPin = binding.toolbarFrame,
 *         scrim     = binding.scrimView,  // 可空
 *     )
 * }
 *
 * // 在每个 Fragment 的 onCreateView 里给自己的列表加底部 nav bar inset
 * override fun onCreateView(...): View {
 *     val rv = RecyclerView(ctx).apply { ... }
 *     NestedHeaderInsets.applyNavBarInsetToList(rv)
 *     return SwipeRefreshLayout(ctx).apply { addView(rv) }
 * }
 * ```
 *
 * 效果：
 *   - 头图 parallax：不动，自然延伸到状态栏后面（沉浸）
 *   - Toolbar pin：高度自动 += statusBarTop，paddingTop 推下 statusBarTop —— 内容下沉但背景到顶
 *   - scrim：高度同步撑高，保证渐变背景一直盖到状态栏底
 *   - sticky/body：自动跟随 pinReservedHeight 变化（NHL 在 onMeasure 里读 pin.measuredHeight，
 *                  pin 长高了 maxOffset 就重算 → 一切级联）
 *   - 每个 Fragment 的列表：底部留 nav bar 空间但内容能继续滚到 nav bar 之下
 */
object NestedHeaderInsets {

    /**
     * 一站式安装："沉浸式头图 + 状态栏下沉 Toolbar"。
     *
     * 在 [nhl] 上注册 [ViewCompat.setOnApplyWindowInsetsListener]，每次 insets 派发：
     *   - 把 [toolbarPin] 高度撑成 `原高度 + statusBarTop`，paddingTop 推 `原paddingTop + statusBarTop`
     *   - 把 [scrim] 高度撑成 `原高度 + statusBarTop`（若提供）
     *
     * "原高度 / 原 paddingTop" 在**调用本方法时**抓快照；之后业务再改 layoutParams.height
     * 会被下一次 insets 派发覆盖。如果有动态改 Toolbar 高度的需求，自己用低层 primitive
     * [applyStatusBarInsetToToolbar] / [applyStatusBarInsetToScrim] 手动 wire。
     *
     * 调用本方法后会立刻 [ViewCompat.requestApplyInsets]，触发一次派发，无需手工再调。
     * 重复调用会覆盖之前的 listener。
     *
     * 注意事项：
     *   - [toolbarPin] / [scrim] 必须是 NHL 的子 view（不强校验，但传错了 NHL 不会跟着 remeasure）
     *   - [toolbarPin] 的 layoutParams.height 必须是正整数像素值（WRAP_CONTENT/MATCH_PARENT 会跳过高度调整，只动 padding）
     */
    @JvmStatic
    @JvmOverloads
    fun applyToCollapsingHeader(
        nhl: NestedHeaderLayout,
        toolbarPin: View,
        scrim: View? = null,
    ) {
        val originalToolbarH = toolbarPin.layoutParams?.height ?: 0
        val originalToolbarPaddingTop = toolbarPin.paddingTop
        val originalScrimH = scrim?.layoutParams?.height ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(nhl) { _, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            applyStatusBarInsetToToolbar(
                toolbar = toolbarPin,
                statusBarTop = sb.top,
                originalHeight = originalToolbarH,
                originalPaddingTop = originalToolbarPaddingTop,
            )
            if (scrim != null) {
                applyStatusBarInsetToScrim(
                    scrim = scrim,
                    statusBarTop = sb.top,
                    originalHeight = originalScrimH,
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(nhl)
    }

    /**
     * 给一个 BEHAVIOR_PIN 的 Toolbar 应用 status bar inset。低层 primitive，
     * 适合业务自己在 Activity 已有的 OnApplyWindowInsetsListener 里调用。
     *
     * - 若 [originalHeight] 是正整数像素：layoutParams.height = originalHeight + statusBarTop
     *   （WRAP_CONTENT / MATCH_PARENT 跳过，因为加法对它们没意义）
     * - paddingTop = originalPaddingTop + statusBarTop（任何高度模式都生效）
     */
    @JvmStatic
    fun applyStatusBarInsetToToolbar(
        toolbar: View,
        statusBarTop: Int,
        originalHeight: Int,
        originalPaddingTop: Int,
    ) {
        if (originalHeight > 0) {
            toolbar.updateLayoutParams { height = originalHeight + statusBarTop }
        }
        toolbar.updatePadding(top = originalPaddingTop + statusBarTop)
    }

    /**
     * 给一个 BEHAVIOR_SCRIM 的渐变背景应用 status bar inset。只动高度，不动 padding。
     */
    @JvmStatic
    fun applyStatusBarInsetToScrim(
        scrim: View,
        statusBarTop: Int,
        originalHeight: Int,
    ) {
        if (originalHeight > 0) {
            scrim.updateLayoutParams { height = originalHeight + statusBarTop }
        }
    }

    /**
     * 给一个可滚动列表（RecyclerView、NestedScrollView、ScrollView 等）应用 nav bar 底部 inset。
     *
     * 做两件事：
     *   1. clipToPadding = false  —— 让内容可以继续滚动到 nav bar 之下，视觉上"沉浸"
     *   2. paddingBottom = 原 paddingBottom + navBarBottom —— 占位，保证最后几项滚到 nav bar 之上时仍能完整看到
     *
     * 典型用法：每个 Fragment 在 onCreateView 创建 RecyclerView 后调一次。
     * 监听器装在 [list] 上，attach 到 window 时系统自动派发；后续配置变化无需再调。
     *
     * 注意：
     *   - 在 ViewPager2 + Fragment 架构里，每个 Fragment 自己调一次自己的 RV，不要让外层 NHL 帮忙找 ——
     *     原因是 Fragment 是懒加载的，外层装监听时内层 RV 还没存在
     *   - [list] 必须是真正的滚动容器（自己消化 dy 的那个 View）。
     *     如果你包了一层 SwipeRefreshLayout 在外面，监听仍然装在 RV 上，**不要**装在 SwipeRefresh 上
     */
    @JvmStatic
    fun applyNavBarInsetToList(list: View) {
        val originalPaddingBottom = list.paddingBottom
        if (list is ViewGroup) {
            list.clipToPadding = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = originalPaddingBottom + sb.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(list)
    }
}
