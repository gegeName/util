package com.chat.uifoundation.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.core.graphics.drawable.toDrawable

object KeyboardInsetHelper {

    /** 调整方式：MARGIN 改 bottomMargin（推荐）；PADDING 改 paddingBottom。 */
    enum class Mode { MARGIN, PADDING }

    /** 手动解绑入口，通常不需要——View detach / Lifecycle DESTROY 时自动清理。 */
    interface Controller { fun detach() }

    /**
     * 绑定目标 View 到键盘高度变化，键盘弹起/收起时自动调整 View 的 margin 或 padding。
     *
     * @param view 要被键盘顶起的目标 View（一般是底部输入栏容器，如 binding.llSend）；
     *             同一 View 重复 attach 时旧绑定会自动 detach 防泄漏。
     * @param lifecycleOwner 可选；传入后在 ON_DESTROY 自动 detach。
     *                       不传则只在 View detach 时清理（一般是 Activity/Fragment）。
     * @param mode 键盘弹起时的调整方式：
     *               - [Mode.MARGIN]（默认，推荐）：改 bottomMargin，View 整体上移，**不影响 View 内部布局**。
     *               - [Mode.PADDING]：改 paddingBottom，View 整体高度增大；
     *                 用于 RecyclerView 等容器需要 clipToPadding=false 配合，详见 README。
     * @param extraOffsetPx 键盘上方额外间距（px），默认 0；
     *                      用法：想让输入栏与键盘之间留 8dp 空气 → `AutoSize.dp2px(ctx, 8)`。
     * @param forceAdjustNothing 默认 true：自动把所属 Activity 的 windowSoftInputMode 改成
     *                           adjustNothing（保留 stateXxx 标志），消除"系统也处理键盘"导致的
     *                           双倍叠加。detach 时还原成 attach 之前的原始值。
     *                           传 false 时业务自己控制 windowSoftInputMode（极少用）。
     * @param onChange 可选回调：键盘开合时触发 `(isShown, keyboardHeightPx)`；
     *                 业务可同步其他 UI（如键盘弹起时滚 RecyclerView 到底）。
     * @return [Handle] 可手动 detach；一般不用主动调（lifecycle / view detach 都会自动清理）。
     */
    @JvmStatic
    @JvmOverloads
    fun attach(
        view: View,
        lifecycleOwner: LifecycleOwner? = null,
        mode: Mode = Mode.MARGIN,
        extraOffsetPx: Int = 0,
        forceAdjustNothing: Boolean = true,
        onChange: ((isShown: Boolean, keyboardHeightPx: Int) -> Unit)? = null,
    ): Controller {
        (view.getTag(TAG_KEY) as? Impl)?.detach()

        val impl = Impl(view, mode, extraOffsetPx, forceAdjustNothing, onChange)
        view.setTag(TAG_KEY, impl)

        if (view.isAttachedToWindow) {
            impl.bind()
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    v.removeOnAttachStateChangeListener(this)
                    impl.bind()
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }

        lifecycleOwner?.lifecycle?.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_DESTROY) {
                    impl.detach()
                    source.lifecycle.removeObserver(this)
                }
            }
        })
        return impl
    }

    @JvmStatic
    fun detach(view: View) {
        (view.getTag(TAG_KEY) as? Impl)?.detach()
    }

    private val TAG_KEY = "KeyboardInsetHelper#impl".hashCode()

    private class Impl(
        private val target: View,
        private val mode: Mode,
        private val extraOffsetPx: Int,
        private val forceAdjustNothing: Boolean,
        private val onChange: ((Boolean, Int) -> Unit)?,
    ) : Controller, View.OnAttachStateChangeListener {

        private var popup: PopupWindow? = null
        private val popupContent = View(target.context)
        private val visibleRect = Rect()

        // 没有键盘时 popupContent 可见区域的底部最大值，键盘弹起后 visibleRect.bottom 会变小。
        private var maxContentBottom = 0
        // 上次 visibleRect 宽度，用于检测旋转/分屏/折叠屏导致的屏幕尺寸变化
        private var lastVisibleWidth = -1
        private var baseBottomMargin = 0
        private var baseBottomPadding = 0
        private var lastEffective = -1
        private var detached = false

        // forceAdjustNothing 时缓存原始 softInputMode，detach 还原
        private var originalSoftInputMode = -1

        private val onGlobalLayout = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            popupContent.getWindowVisibleDisplayFrame(visibleRect)
            val width = visibleRect.width()
            // 旋转 / 分屏 / 折叠屏切换时宽度会变，旧的 maxContentBottom 不再适用 → 重置基线
            if (lastVisibleWidth != -1 && width != lastVisibleWidth) {
                maxContentBottom = 0
            }
            lastVisibleWidth = width
            val bottom = visibleRect.bottom
            if (maxContentBottom < bottom) maxContentBottom = bottom
            val keyboardHeight = (maxContentBottom - bottom).coerceAtLeast(0)
            handleKeyboardHeight(keyboardHeight)
        }

        fun bind() {
            if (detached) return
            baseBottomMargin = (target.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
            baseBottomPadding = target.paddingBottom
            target.addOnAttachStateChangeListener(this)

            // 关键：强制 Activity 走 adjustNothing，让系统不参与键盘 layout 调整 → 不会和本工具叠加
            if (forceAdjustNothing) overrideActivitySoftInputMode()

            // PopupWindow 自己设 ADJUST_RESIZE + INPUT_METHOD_NEEDED 强制感知 IME，
            // STATE_ALWAYS_HIDDEN 防止 popup 自己触发键盘弹出。
            val window = PopupWindow(popupContent, 0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setBackgroundDrawable(0.toDrawable())
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            }
            popup = window
            popupContent.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayout)

            // 必须等 root 有 windowToken 才能 showAtLocation，否则 BadTokenException
            val root = target.rootView ?: return
            root.post {
                if (detached) return@post
                if (root.windowToken != null && !window.isShowing) {
                    runCatching { window.showAtLocation(root, Gravity.NO_GRAVITY, 0, 0) }
                }
            }
        }

        private fun handleKeyboardHeight(keyboardHeight: Int) {
            if (keyboardHeight == lastEffective) return
            lastEffective = keyboardHeight
            applyInset(keyboardHeight)
            onChange?.invoke(keyboardHeight > 0, keyboardHeight)
        }

        private fun applyInset(keyboardHeight: Int) {
            val total = if (keyboardHeight > 0) keyboardHeight + extraOffsetPx else 0
            when (mode) {
                Mode.MARGIN -> {
                    val lp = target.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                    lp.bottomMargin = baseBottomMargin + total
                    target.layoutParams = lp
                }
                Mode.PADDING -> target.setPadding(
                    target.paddingLeft,
                    target.paddingTop,
                    target.paddingRight,
                    baseBottomPadding + total,
                )
            }
        }

        override fun onViewAttachedToWindow(v: View) {}
        override fun onViewDetachedFromWindow(v: View) { detach() }

        override fun detach() {
            if (detached) return
            detached = true
            runCatching { popupContent.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayout) }
            runCatching { popup?.dismiss() }
            popup = null
            target.removeOnAttachStateChangeListener(this)
            applyInset(0)
            restoreActivitySoftInputMode()
            target.setTag(TAG_KEY, null)
        }

        private fun overrideActivitySoftInputMode() {
            runCatching {
                val activity = findActivity(target.context) ?: return@runCatching
                val window = activity.window ?: return@runCatching
                val current = window.attributes.softInputMode
                originalSoftInputMode = current
                // 保留 SOFT_INPUT_MASK_STATE（stateHidden/stateVisible 等），只覆盖 SOFT_INPUT_MASK_ADJUST
                val keepState = current and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
                window.setSoftInputMode(keepState or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
        }

        private fun restoreActivitySoftInputMode() {
            if (originalSoftInputMode == -1) return
            val activity = findActivity(target.context) ?: return
            runCatching { activity.window.setSoftInputMode(originalSoftInputMode) }
            originalSoftInputMode = -1
        }

        private fun findActivity(ctx: Context): Activity? {
            var c: Context? = ctx
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
