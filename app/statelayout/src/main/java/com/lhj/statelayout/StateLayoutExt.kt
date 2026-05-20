package com.lhj.statelayout

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.view.doOnAttach
import androidx.fragment.app.Fragment

/**
 * 把任意已存在的 View 用 StateLayout 包裹起来，原地替换，保留原 LayoutParams。
 *
 * **要求**：调用时该 View 已 attach（parent != null）。包 binding 的具体子 View 永远满足。
 * 想直接包整个 Fragment 根 view，请用 [Fragment.installStateLayout]，
 * 它能自动处理"还未 attach"的情况（ViewPager2 / 嵌套 Fragment 等场景）。
 *
 * @param stateBackgroundRes loading/empty/error 视图的统一背景（color 或 drawable）。
 *   典型用途：topbar 在 content 里，状态视图用 [topMarginPx] 让出 topbar 区域，
 *   背景色覆盖剩余区域以遮住 content。0 表示透明（content 透出，适合保留旧列表的刷新体验）。
 */
fun View.wrapWithStateLayout(
    topMarginPx: Int = 0,
    bottomMarginPx: Int = 0,
    startMarginPx: Int = 0,
    endMarginPx: Int = 0,
    @DrawableRes stateBackgroundRes: Int,
    onRetry: (() -> Unit)? = null,
): StateLayout {
    val parent = parent as? ViewGroup
        ?: error("View 还未 attach 到任何 ViewGroup，无法包裹（请改包子节点或用 Fragment.installStateLayout）")
    val state = StateLayout(context).apply {
        id = View.generateViewId()
        setStateMargin(startMarginPx, topMarginPx, endMarginPx, bottomMarginPx)
        if (stateBackgroundRes != 0) setStateBackgroundResource(stateBackgroundRes)
        onRetry?.let { setOnRetryClickListener(it) }
    }
    swapInStateLayout(this, state, parent)
    return state
}

fun Activity.installStateLayout(
    topMarginPx: Int = 0,
    bottomMarginPx: Int = 0,
    startMarginPx: Int = 0,
    endMarginPx: Int = 0,
    @DrawableRes stateBackgroundRes: Int = R.color.state_background_default,
    onRetry: (() -> Unit)? = null,
): StateLayout {
    val root = findViewById<ViewGroup>(android.R.id.content)
    val content = root.getChildAt(0) ?: error("Activity 还未 setContentView")
    return content.wrapWithStateLayout(
        topMarginPx, bottomMarginPx, startMarginPx, endMarginPx, stateBackgroundRes, onRetry
    )
}

/**
 * 包裹 Fragment 根 view。自动处理"还未 attach"的情况（ViewPager2 + FragmentStateAdapter 等）。
 * 任何场景下都立即返回 StateLayout 实例，可马上调 bindPageState / showLoading 等。
 *
 * @param stateBackgroundRes loading/empty/error 视图统一背景，遮挡 topMargin 之下的 content
 */
fun Fragment.installStateLayout(
    topMarginPx: Int = 0,
    bottomMarginPx: Int = 0,
    startMarginPx: Int = 0,
    endMarginPx: Int = 0,
    @DrawableRes stateBackgroundRes: Int = R.color.state_background_default,
    onRetry: (() -> Unit)? = null,
): StateLayout {
    val view = requireView()
    val state = StateLayout(view.context).apply {
        id = View.generateViewId()
        setStateMargin(startMarginPx, topMarginPx, endMarginPx, bottomMarginPx)
        if (stateBackgroundRes != 0) setStateBackgroundResource(stateBackgroundRes)
        onRetry?.let { setOnRetryClickListener(it) }
    }
    val parent = view.parent as? ViewGroup
    if (parent != null) {
        swapInStateLayout(view, state, parent)
    } else {
        view.doOnAttach {
            val p = it.parent as? ViewGroup ?: return@doOnAttach
            if (it === state || it.parent === state) return@doOnAttach
            swapInStateLayout(it, state, p)
        }
    }
    return state
}

private fun swapInStateLayout(target: View, state: StateLayout, parent: ViewGroup) {
    val index = parent.indexOfChild(target)
    val originalLp = target.layoutParams
    parent.removeView(target)
    state.addView(
        target,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )
    parent.addView(state, index, originalLp)
}
