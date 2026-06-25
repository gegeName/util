package com.chat.uifoundation.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupWindow
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * 软键盘 ↔ 自定义面板（emoji / 表情包 / 加号扩展）切换工具。
 *
 * 切换时输入栏位置完全不变（先占位再关键盘 / 先弹键盘再隐藏面板，反闪烁）。
 * 键盘高度自动持久化到 SharedPreferences，下次面板高度沿用。
 *
 * 布局要求：
 * ```xml
 * <LinearLayout orientation="vertical">
 *     <RecyclerView layout_weight="1"/>
 *     <LinearLayout id="inputBar">
 *         <Button id="btnEmoji"/>
 *         <EditText id="etInput"/>
 *     </LinearLayout>
 *     <FrameLayout id="panelContainer"
 *         layout_height="0dp">
 *         <YourEmojiPanel id="emojiPanel"
 *             visibility="gone"/>
 *     </FrameLayout>
 * </LinearLayout>
 * ```
 *
 * 用法：
 * ```kotlin
 * KeyboardPanelHelper.bind(
 *     inputView = etInput,
 *     triggerView = btnEmoji,
 *     panelView = emojiPanel,
 *     panelContainer = panelContainer,
 *     lifecycleOwner = this,
 *     defaultPanelHeightPx = AutoSize.dp2px(this, 280),
 * ) { state -> /* 可选 */ }
 * ```
 */
object KeyboardPanelHelper {

    /** 当前键盘与面板的状态。 */
    enum class State {
        /** 键盘和面板都未显示，panelContainer.height = 0。 */
        HIDDEN,

        /** 软键盘可见，panelContainer.height = 当前键盘高度，emojiPanel 隐藏。 */
        KEYBOARD,

        /** 自定义面板可见（如 emoji），panelContainer.height = 上次保存的键盘高度，键盘已收起。 */
        PANEL,
    }

    /**
     * 业务可持有的控制器，用于主动驱动状态切换、查询当前状态或手动解绑。
     *
     * 命名说明：故意叫 Controller 而不是 Handle，避免和 [android.os.Handler] 视觉混淆。
     */
    interface Controller {
        /** 当前状态，只读；状态变化会触发 [bind] 时传入的 onStateChange 回调。 */
        val state: State

        /** 主动弹出软键盘：聚焦 inputView + showSoftInput；状态最终会变成 [State.KEYBOARD]。 */
        fun showKeyboard()

        /**
         * 主动隐藏软键盘（不影响面板可见性）。
         * - 当前 [State.KEYBOARD]：键盘收起，状态变 [State.HIDDEN]，panelContainer 高度归零
         * - 当前 [State.PANEL]：键盘本就隐藏，noop
         * - 当前 [State.HIDDEN]：noop
         */
        fun hideKeyboard()

        /**
         * 主动弹出自定义面板：先将 panelContainer 撑到键盘高度并显示 panelView，再关键盘 → 输入栏位置无闪烁。
         * 如果还没保存过键盘高度（用户没弹过键盘），使用 `defaultPanelHeightPx`；都没有就不动作。
         */
        fun showPanel()

        /** 关闭键盘和面板，状态变成 [State.HIDDEN]，panelContainer.height = 0。 */
        fun hideAll()

        /**
         * 在键盘和面板之间切换（emoji 按钮的常规绑定）：
         *   PANEL → KEYBOARD，KEYBOARD/HIDDEN → PANEL。
         */
        fun togglePanel()

        /**
         * 手动解绑：dismiss 内部 PopupWindow、移除监听、还原 Activity softInputMode、清空 panel/margin。
         * 一般不用主动调，[bind] 时传入的 lifecycleOwner 会在 ON_DESTROY 时自动调用。
         */
        fun detach()
    }

    /**
     * 绑定一组 view 实现"软键盘 ↔ 自定义面板"切换。
     *
     * 状态机：
     *   HIDDEN ─点 inputView─> KEYBOARD ─点 triggerView─> PANEL
     *     ↑                                                  │
     *     └──hideAll / 返回键──────────────────点 inputView──┘
     *
     * 反闪烁原理：切换时**先把替代物准备好再撤掉原来的**——
     *   - 键盘 → 面板：先 panelContainer.height = 保存的键盘高度 + 显示 emojiPanel，再隐藏键盘
     *   - 面板 → 键盘：先弹键盘（PopupWindow 监听到键盘高度后自动同步 panelContainer.height），再隐藏 emojiPanel
     *
     * @param inputView 主输入框（EditText）；点击或获得焦点时切回 [State.KEYBOARD]。
     * @param triggerView 切换触发按钮（emoji / 加号等）；点击执行 [Controller.togglePanel]。
     * @param panelView 自定义面板内容（emoji 网格 / 表情包 / 扩展菜单）；
     *                  visibility 由本类控制（PANEL 显示，其余隐藏），高度填满 panelContainer。
     * @param panelContainer 面板占位容器；高度由本类控制，**初始 layout_height 必须为 0**；
     *                       业务侧不要再手动改它的 height。
     * @param lifecycleOwner 可选；传入后在 ON_DESTROY 自动 [Controller.detach]，避免内存泄漏。
     * @param defaultPanelHeightPx 用户没弹过键盘（SharedPreferences 里没有缓存高度）时的兜底面板高度，
     *                             推荐 `AutoSize.dp2px(ctx, 280)` 左右；传 0 表示"没有缓存就不弹面板"。
     * @param onStateChange 状态变化回调，可同步 UI（如换 emoji 按钮图标、滚 RecyclerView 到底）。
     * @return [Controller] 用于主动驱动状态或手动 detach。
     */
    @JvmStatic
    @JvmOverloads
    fun bind(
        inputView: EditText,
        triggerView: View,
        panelView: View,
        panelContainer: View,
        lifecycleOwner: LifecycleOwner? = null,
        defaultPanelHeightPx: Int = 0,
        onStateChange: ((State) -> Unit)? = null,
    ): Controller {
        (panelContainer.getTag(TAG_KEY) as? Impl)?.detach()
        val impl = Impl(inputView, triggerView, panelView, panelContainer,
            defaultPanelHeightPx, onStateChange)
        panelContainer.setTag(TAG_KEY, impl)
        impl.bind()

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

    private val TAG_KEY = "KeyboardPanelHelper#impl".hashCode()
    private const val SP_NAME = "KeyboardPanelHelper"
    private const val SP_KEY_HEIGHT = "keyboardHeightPx"

    private class Impl(
        private val inputView: EditText,
        private val triggerView: View,
        private val panelView: View,
        private val panelContainer: View,
        private val defaultPanelHeightPx: Int,
        private val onStateChange: ((State) -> Unit)?,
    ) : Controller {

        override var state: State = State.HIDDEN
            private set

        private var savedKeyboardHeight = 0
        private var detached = false

        private var popup: PopupWindow? = null
        private val popupContent = View(inputView.context)
        private val visibleRect = Rect()
        private var maxContentBottom = 0
        private var lastVisibleWidth = -1
        private var lastKeyboardShown = false
        private var originalSoftInputMode = -1

        private val onGlobalLayout = ViewTreeObserver.OnGlobalLayoutListener {
            popupContent.getWindowVisibleDisplayFrame(visibleRect)
            val width = visibleRect.width()
            if (lastVisibleWidth != -1 && width != lastVisibleWidth) maxContentBottom = 0
            lastVisibleWidth = width
            val bottom = visibleRect.bottom
            if (maxContentBottom < bottom) maxContentBottom = bottom
            val keyboardHeight = (maxContentBottom - bottom).coerceAtLeast(0)
            handleKeyboardHeight(keyboardHeight)
        }

        fun bind() {
            savedKeyboardHeight = loadSavedHeight(inputView.context).takeIf { it > 0 }
                ?: defaultPanelHeightPx
            setContainerHeight(0)
            panelView.visibility = View.GONE

            overrideActivitySoftInputMode()
            setupPopupWindow()

            triggerView.setOnClickListener { togglePanel() }
            inputView.setOnClickListener {
                if (state != State.KEYBOARD) showKeyboard()
            }
            inputView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus && state == State.PANEL) showKeyboard()
            }
        }

        private fun setupPopupWindow() {
            val window = PopupWindow(popupContent, 0, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                setBackgroundDrawable(0.toDrawable())
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                inputMethodMode = PopupWindow.INPUT_METHOD_NEEDED
            }
            popup = window
            popupContent.viewTreeObserver.addOnGlobalLayoutListener(onGlobalLayout)

            val root = inputView.rootView ?: return
            root.post {
                if (detached) return@post
                if (root.windowToken != null && !window.isShowing) {
                    runCatching { window.showAtLocation(root, Gravity.NO_GRAVITY, 0, 0) }
                }
            }
        }

        private fun handleKeyboardHeight(keyboardHeight: Int) {
            val shown = keyboardHeight > 0
            if (shown == lastKeyboardShown && keyboardHeight == savedKeyboardHeight) return
            lastKeyboardShown = shown

            if (shown) {
                savedKeyboardHeight = keyboardHeight
                saveHeight(inputView.context, keyboardHeight)
                setContainerHeight(keyboardHeight)
                if (state != State.KEYBOARD) {
                    state = State.KEYBOARD
                    panelView.visibility = View.GONE
                    onStateChange?.invoke(state)
                }
            } else if (state == State.KEYBOARD) {
                setContainerHeight(0)
                state = State.HIDDEN
                onStateChange?.invoke(state)
            }
            // 注意：state == PANEL 时键盘已主动隐藏，此处不响应
        }

        override fun showKeyboard() {
            if (detached) return
            inputView.requestFocus()
            imm()?.showSoftInput(inputView, 0)
            // state 由 popup 回调更新
        }

        override fun hideKeyboard() {
            if (detached) return
            if (state != State.KEYBOARD) return   // PANEL/HIDDEN 时键盘本就不可见
            imm()?.hideSoftInputFromWindow(inputView.windowToken, 0)
            inputView.clearFocus()
            // state 变 HIDDEN 由 popup 监听到键盘高度=0 后自动同步
        }

        override fun showPanel() {
            if (detached) return
            val h = savedKeyboardHeight.takeIf { it > 0 } ?: defaultPanelHeightPx
            if (h <= 0) return

            // 先把 panel 占位拉满，再隐藏键盘 → 输入栏位置无闪烁
            setContainerHeight(h)
            panelView.visibility = View.VISIBLE
            imm()?.hideSoftInputFromWindow(inputView.windowToken, 0)
            inputView.clearFocus()

            state = State.PANEL
            onStateChange?.invoke(state)
        }

        override fun hideAll() {
            if (detached) return
            setContainerHeight(0)
            panelView.visibility = View.GONE
            imm()?.hideSoftInputFromWindow(inputView.windowToken, 0)
            inputView.clearFocus()
            state = State.HIDDEN
            onStateChange?.invoke(state)
        }

        override fun togglePanel() {
            when (state) {
                State.PANEL -> showKeyboard()
                State.KEYBOARD, State.HIDDEN -> showPanel()
            }
        }

        override fun detach() {
            if (detached) return
            detached = true
            runCatching { popupContent.viewTreeObserver.removeOnGlobalLayoutListener(onGlobalLayout) }
            runCatching { popup?.dismiss() }
            popup = null
            triggerView.setOnClickListener(null)
            inputView.setOnClickListener(null)
            inputView.onFocusChangeListener = null
            setContainerHeight(0)
            panelView.visibility = View.GONE
            restoreActivitySoftInputMode()
            panelContainer.setTag(TAG_KEY, null)
        }

        private fun setContainerHeight(h: Int) {
            val lp = panelContainer.layoutParams ?: return
            if (lp.height == h) return
            lp.height = h
            panelContainer.layoutParams = lp
        }

        private fun imm(): InputMethodManager? =
            inputView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

        private fun overrideActivitySoftInputMode() {
            runCatching {
                val activity = findActivity(inputView.context) ?: return@runCatching
                val window = activity.window ?: return@runCatching
                val current = window.attributes.softInputMode
                originalSoftInputMode = current
                val keepState = current and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
                window.setSoftInputMode(keepState or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            }
        }

        private fun restoreActivitySoftInputMode() {
            if (originalSoftInputMode == -1) return
            runCatching {
                findActivity(inputView.context)?.window?.setSoftInputMode(originalSoftInputMode)
            }
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

    private fun loadSavedHeight(ctx: Context): Int =
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).getInt(SP_KEY_HEIGHT, 0)

    private fun saveHeight(ctx: Context, height: Int) {
        ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE).edit()
            .putInt(SP_KEY_HEIGHT, height).apply()
    }
}
