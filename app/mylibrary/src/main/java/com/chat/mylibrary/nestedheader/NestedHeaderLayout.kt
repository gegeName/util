package com.chat.mylibrary.nestedheader

import android.content.Context
import android.graphics.Rect
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.OverScroller
import androidx.core.view.NestedScrollingChild3
import androidx.core.view.NestedScrollingChildHelper
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.chat.mylibrary.R
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.content.withStyledAttributes
import androidx.core.view.isGone

/**
 * A 阶段实现：自定义 CoordinatorLayout + AppBarLayout 的最小替代。
 *
 * 仅处理"手指拖动"产生的嵌套滚动；fling 留给 B 阶段统一接管。
 *
 * 子视图通过 layout_nh_behavior 指定行为：
 *   - scroll  : 折叠时整体向上消失，累计高度 = maxOffset
 *   - sticky  : 折叠到极限时固定在顶部（位于所有 scroll 之下、body 之上）
 *   - body    : 主体内容（一般是 RecyclerView / ViewPager2），高度被钳制为 (parent - sticky)
 *   - parallax: 视差头图，按 multiplier 慢速折叠
 *   - pin     : 固定顶部（Toolbar），Z 序最上
 *   - scrim   : 顶部渐变蒙层，alpha 随折叠变化
 *   - squeeze : 顶固定、底被 body 顶起压缩（内容原地压扁）
 *   - collapse: 类 exitUntilCollapsed —— 顶锚固定、内容上滚、底被 body 顶起、收缩到 0
 *   - curtain : 闭幕/开幕 —— 上下边缘同时向中间收（折叠）/ 向两边开（展开）
 *
 * 子 View 支持标准 android:layout_margin*（LayoutParams 继承 MarginLayoutParams）：
 *   scroll/parallax/sticky 的上下 margin 计入垂直堆叠间距与折叠范围；body 的 margin 缩小其可用区。
 */
class NestedHeaderLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr), NestedScrollingParent3, NestedScrollingChild3 {

    private val parentHelper = NestedScrollingParentHelper(this)
    private val childHelper = NestedScrollingChildHelper(this).apply {
        // 必须开启，否则 dispatchNestedScroll 这条转发链不会触发，
        // 外层 SwipeRefreshLayout 永远收不到下拉信号。
        isNestedScrollingEnabled = true
    }

    /** 当前已折叠像素，范围 [0, maxOffset]。0 = 完全展开，maxOffset = 完全折叠。 */
    var headerOffset: Int = 0
        private set

    /** 所有 scroll 子的高度和，即折叠上限。 */
    var maxOffset: Int = 0
        private set

    private var stickyHeight: Int = 0

    /** 所有 pin/scrim 子高度的最大值；这块顶部空间永远被预留给 pin 不会被折叠走。 */
    private var pinReservedHeight: Int = 0

    /**
     * 第一个 scroll/parallax 子的 layout 底 Y（NHL 本地坐标）；用作两件事：
     *   1) pin/scrim 子的 clipBounds 边界（防止 Toolbar 包围盒的下半部空白溢入下面子的 Y 段）
     *   2) CollapsingTitleView 自动 cap 自己的 translationY，避免**文字本体**飞到 innerRv 等下面子的 Y 段
     * -1 表示没有 scroll/parallax 子。
     */
    var firstScrollChildBottom: Int = -1
        private set

    /**
     * 是否因为存在 CollapsingTitleView 而关掉了 clipChildren。
     * 只有为 true 时，applyPinScrimClipBounds 才会给非 pin/scrim 子补 clipBounds 把它们裁回自身 box ——
     * 否则原生 clipChildren=true 已经在裁，乱补 clipBounds 会破坏业务的故意溢出（阴影/角标等）。
     */
    private var clipChildrenManagedForTitle = false

    private var pendingOffsetRatio: Float = -1f

    private val listeners = mutableListOf<OnHeaderOffsetChangedListener>()

    // ---- 手指拖动 header 区域的状态 ----------------------------------------
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop

    /** DOWN 时记录第一根手指 id；多指切换/POINTER_UP 时维护。 */
    private var activePointerId: Int = INVALID_POINTER_ID

    /** DOWN 时的 x/y；用于判定 slop 和方向。 */
    private var initialDownX: Float = 0f
    private var initialDownY: Float = 0f

    /** 当前活跃 pointer 上一次的 Y，用于增量计算（含亚像素累计）。 */
    private var lastTouchY: Float = 0f

    /** 是否已进入"拖动 header"状态。仅 true 时 onTouchEvent 才操作 offset。 */
    private var isHeaderDragging: Boolean = false

    /** DOWN 落点是否在 header 子（scroll/sticky）上。决定是否要参与拖动。 */
    private var initialTouchOnHeader: Boolean = false

    private var velocityTracker: VelocityTracker? = null

    private val minFlingVelocity: Int = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity: Int = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private val scroller: OverScroller = OverScroller(context)

    /** scroller 当前在做什么；用于结束回调里区分要不要 snap。 */
    private var scrollerAction: ScrollerAction = ScrollerAction.NONE

    /** 是否启用自动吸附。默认 false：滚到哪里就停在哪里。 */
    var snapEnabled: Boolean = false

    /** 启用吸附时的阈值：折叠比例 >= 此值 → snap 到 maxOffset，否则 snap 到 0。 */
    var snapThresholdRatio: Float = DEFAULT_SNAP_THRESHOLD_RATIO
        set(value) { field = value.coerceIn(0f, 1f) }

    init {
        context.withStyledAttributes(attrs, R.styleable.NestedHeaderLayout) {
            snapEnabled = getBoolean(R.styleable.NestedHeaderLayout_nh_snapEnabled, false)
            snapThresholdRatio = getFloat(
                R.styleable.NestedHeaderLayout_nh_snapThresholdRatio,
                DEFAULT_SNAP_THRESHOLD_RATIO
            )
        }
    }

    fun addOnOffsetChangedListener(l: OnHeaderOffsetChangedListener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeOnOffsetChangedListener(l: OnHeaderOffsetChangedListener) {
        listeners.remove(l)
    }

    private fun dispatchOffsetChanged() {
        if (listeners.isEmpty()) return
        listeners.toList().forEach { it.onOffsetChanged(this, headerOffset, maxOffset) }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        // 把 pin/scrim 提到 child 列表最后 → 绘制时最后绘制 → 视觉上盖在 parallax/scroll 之上。
        // scrim 在前提，pin 在最后提，保证 pin（Toolbar）显示在 scrim（背景）之上。
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if ((c.layoutParams as? LayoutParams)?.behavior == BEHAVIOR_SCRIM) {
                bringChildToFront(c)
            }
        }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if ((c.layoutParams as? LayoutParams)?.behavior == BEHAVIOR_PIN) {
                bringChildToFront(c)
            }
        }
        // 业务忘了写 clipChildren=false 时，CollapsingTitleView 的 expanded 状态会被父容器裁掉。
        // 这里递归找一遍子树，给所有包含 CollapsingTitleView 的祖先链都关掉裁剪。
        disableClipChildrenForCollapsingTitles(this)
    }

    /**
     * 递归查找子树里所有 CollapsingTitleView，把它们的祖先链 clipChildren 全关掉。
     * 仅在 [onFinishInflate] 时跑一次；运行时新增的 CollapsingTitleView 自己
     * 会在 onAttachedToWindow 时调 [disableClipChildrenAlongAncestorsOf]。
     */
    private fun disableClipChildrenForCollapsingTitles(root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            when (c) {
                is CollapsingTitleView -> disableClipChildrenAlongAncestorsOf(c)
                is ViewGroup -> disableClipChildrenForCollapsingTitles(c)
            }
        }
    }

    /**
     * 把 [view] 的 parent 一路向上到 this（含）逐级 clipChildren = false。
     * 公开给 CollapsingTitleView 在运行时附着时调用，保证 XML 之外的添加场景也能生效。
     * 如果 [view] 不在 this 的子树里（parent 链找不到 this），整个调用不会触动任何 view ——
     * 防止误调误关外层（DecorView 等）的裁剪。
     */
    fun disableClipChildrenAlongAncestorsOf(view: View) {
        // 第一遍 dry-run：确认 view 是 this 的后代
        var p: ViewParent? = view.parent
        while (p is ViewGroup && p !== this) {
            p = p.parent
        }
        if (p !== this) return
        // 确认是后代了：标记我们因为 title 关过 clipChildren。
        // 这是 applyPinScrimClipBounds 决定要不要给其他子补 clipBounds 的依据 ——
        // 没 title 时 clipChildren 仍是默认 true，不应该乱裁子（破坏业务的故意溢出，如阴影/角标）。
        clipChildrenManagedForTitle = true
        // 第二遍才真正关 clip
        p = view.parent
        while (p is ViewGroup) {
            if (p.clipChildren) p.clipChildren = false
            if (p === this) break
            p = p.parent
        }
        // 关掉 clip 后子的裁剪策略变了，重新布一次让 clipBounds 生效
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val parentW = MeasureSpec.getSize(widthMeasureSpec)
        val parentH = MeasureSpec.getSize(heightMeasureSpec)

        var scrollSum = 0     // scroll + parallax 都算可折叠区域
        var stickySum = 0
        var pinMax = 0        // pin/scrim 取最大值（它们在 Y=0..H 重叠）
        var bodyChild: View? = null

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val lp = child.layoutParams as LayoutParams

            if (lp.behavior == BEHAVIOR_BODY) {
                bodyChild = child
                continue
            }

            // measureChildWithMargins 会把 padding + margin 都从可用空间里扣掉
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)

            // 垂直占位 = 自身高度 + 上下 margin
            val occupied = child.measuredHeight + lp.topMargin + lp.bottomMargin
            when (lp.behavior) {
                // squeeze/collapse/curtain 与 scroll/parallax 一样计入折叠范围：移除的空间都是 offset，只是视觉差异
                BEHAVIOR_SCROLL, BEHAVIOR_PARALLAX, BEHAVIOR_SQUEEZE,
                BEHAVIOR_COLLAPSE, BEHAVIOR_CURTAIN ->
                    scrollSum += occupied
                BEHAVIOR_STICKY -> stickySum += occupied
                BEHAVIOR_PIN, BEHAVIOR_SCRIM -> if (occupied > pinMax) pinMax = occupied
            }
        }

        stickyHeight = stickySum
        pinReservedHeight = pinMax
        // pin 永远占顶部那段，所以最多能折叠走的距离 = scroll+parallax 总高 - pin 预留
        maxOffset = (scrollSum - pinMax).coerceAtLeast(0)

        // 算"第一个 scroll/parallax 子的 layout 底 Y"，给 pin/scrim 的 clipBounds 用
        // ——title 通过 translation 可以飞下来，但飞不进 innerRv 等下面的子的 Y 段
        firstScrollChildBottom = -1
        var stackTopForClip = paddingTop
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.isGone) continue
            val lp = c.layoutParams as LayoutParams
            when (lp.behavior) {
                BEHAVIOR_SCROLL, BEHAVIOR_PARALLAX, BEHAVIOR_SQUEEZE,
                BEHAVIOR_COLLAPSE, BEHAVIOR_CURTAIN -> {
                    stackTopForClip += lp.topMargin + c.measuredHeight
                    if (firstScrollChildBottom < 0) firstScrollChildBottom = stackTopForClip
                    stackTopForClip += lp.bottomMargin
                }
                BEHAVIOR_STICKY -> stackTopForClip += lp.topMargin + c.measuredHeight + lp.bottomMargin
                else -> {}
            }
        }

        // body 高度扣除 sticky 和 pin 预留区域 + 自身 margin；折叠过程中 body 自身不会再 measure
        bodyChild?.let { body ->
            val lp = body.layoutParams as LayoutParams
            val cwSpec = getChildMeasureSpec(
                widthMeasureSpec,
                paddingLeft + paddingRight + lp.leftMargin + lp.rightMargin,
                lp.width
            )
            val bodyH = (parentH - paddingTop - paddingBottom - stickyHeight - pinReservedHeight
                    - lp.topMargin - lp.bottomMargin).coerceAtLeast(0)
            val chSpec = MeasureSpec.makeMeasureSpec(bodyH, MeasureSpec.EXACTLY)
            body.measure(cwSpec, chSpec)
        }

        setMeasuredDimension(parentW, parentH)

        if (pendingOffsetRatio in 0f..1f && maxOffset > 0) {
            headerOffset = (pendingOffsetRatio * maxOffset).toInt().coerceIn(0, maxOffset)
            pendingOffsetRatio = -1f
        } else if (headerOffset > maxOffset) {
            headerOffset = maxOffset
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // scroll/parallax/sticky/body 走线性堆叠的 stackTop
        var stackTop = paddingTop

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.isGone) continue
            val lp = child.layoutParams as LayoutParams
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val cl = paddingLeft + lp.leftMargin
            when (lp.behavior) {
                BEHAVIOR_PIN, BEHAVIOR_SCRIM -> {
                    // 独立锚点：固定在顶部 + 自身 topMargin，不参与线性堆叠
                    val ct = paddingTop + lp.topMargin
                    child.layout(cl, ct, cl + cw, ct + ch)
                }
                else -> {
                    stackTop += lp.topMargin
                    child.layout(cl, stackTop, cl + cw, stackTop + ch)
                    stackTop += ch + lp.bottomMargin
                }
            }
        }

        // 旧实现用 offsetTopAndBottom 累加 delta；新模型对每个子按 behavior 算绝对 translationY。
        applyOffsetsToAllChildren()
        // 给 pin/scrim 子设 clipBounds，把它们的绘制（包括子 View 通过 translation 的延伸，比如
        // CollapsingTitleView 的 expanded 飞出 Toolbar）限制在"第一个 scroll/parallax 子的底部"之内。
        // 这样 title 还能往下飞但飞不进 innerRv 等下面的子的 Y 段，RV 的 item 滚动不会被 title 压住。
        applyPinScrimClipBounds()
        dispatchOffsetChanged()
    }

    private fun applyPinScrimClipBounds() {
        // 没为 title 关过 clipChildren → 原生裁剪仍在工作，不要乱补 clipBounds。
        // 清掉可能残留的（理论上不会有，防御）后直接返回，避免破坏业务故意的子溢出（阴影/角标等）。
        // 但 squeeze 的 clipBounds 由 applyOffsetsToAllChildren 管理，这里始终不能碰。
        if (!clipChildrenManagedForTitle) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val b = (c.layoutParams as? LayoutParams)?.behavior
                if (b == BEHAVIOR_SQUEEZE || b == BEHAVIOR_COLLAPSE || b == BEHAVIOR_CURTAIN) continue
                c.clipBounds = null
            }
            return
        }
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.isGone) continue
            val lp = c.layoutParams as LayoutParams
            // squeeze/collapse/curtain 的 clipBounds 是动态压缩量，归 applyOffsetsToAllChildren 管，跳过
            if (lp.behavior == BEHAVIOR_SQUEEZE ||
                lp.behavior == BEHAVIOR_COLLAPSE ||
                lp.behavior == BEHAVIOR_CURTAIN
            ) continue
            when (lp.behavior) {
                BEHAVIOR_PIN, BEHAVIOR_SCRIM -> {
                    if (firstScrollChildBottom < 0) {
                        // 没有 scroll/parallax 子可参考：pin/scrim 不设上下界，让 title 自由飞出
                        c.clipBounds = null
                    } else {
                        // clipBounds 是 view 本地坐标。c.top 是 c 在 NHL 里的 Y。
                        // 至少保证 c 自身的高度不被裁掉（用户的 Toolbar 高 56dp 就别只给 56dp）。
                        val effectiveBottom = (firstScrollChildBottom - c.top).coerceAtLeast(c.height)
                        c.clipBounds = Rect(0, 0, c.width, effectiveBottom)
                    }
                }
                else -> {
                    // 非 pin/scrim 子（scroll/parallax/sticky/body）：因为我们为了 CollapsingTitleView
                    // 把 NHL.clipChildren 关了，这些子不再被 NHL 裁到自身 layout box，
                    // 会导致 RecyclerView 滚动时正在回收的 item（item.top<0）跑到上面的 head image 区域。
                    // 显式给个 clipBounds = 自身 layout box，恢复"绘制限制在自己的范围内"。
                    c.clipBounds = Rect(0, 0, c.width, c.height)
                }
            }
        }
    }

    /**
     * 把当前 headerOffset 落到每个子的 translationY / alpha 上。
     * 不要用 delta 累加（parallax 的 multiplier 不是整数，累加会丢精度）。
     */
    private fun applyOffsetsToAllChildren() {
        val offsetF = headerOffset.toFloat()
        val ratio = if (maxOffset > 0) offsetF / maxOffset else 0f
        // squeeze / collapse 自上而下手风琴式压缩，共享同一累加器：
        // 累计上方已折叠像素，让下一个整体上移这个量（贴住上一个收缩后的底边，不露白），再处理自己的份额。
        //   squeeze ：顶固定 + 裁底 + 内容不动（原地压扁）
        //   collapse：顶锚固定 + 裁顶 + 内容上滚（类 exitUntilCollapsed）
        var collapsedAbove = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.isGone) continue
            val lp = c.layoutParams as LayoutParams
            when (lp.behavior) {
                BEHAVIOR_SCROLL, BEHAVIOR_STICKY, BEHAVIOR_BODY -> {
                    c.translationY = -offsetF
                }
                BEHAVIOR_PARALLAX -> {
                    c.translationY = -offsetF * lp.parallaxMultiplier
                }
                BEHAVIOR_SQUEEZE -> {
                    // 本段份额按"占位高度"(height + 上下 margin) 分配，与 onMeasure 计入 scrollSum 的口径一致，
                    // 否则带 margin 时累加器少算 → 总折叠 < offset → 和 body(-offset) 对不上露白。
                    // clip 只能裁 view 自身高度，所以 clip 量以 height 封顶；多出的 margin 份额仅参与累加（让下方贴合）。
                    val occupied = c.height + lp.topMargin + lp.bottomMargin
                    val local = (headerOffset - collapsedAbove).coerceIn(0, occupied)
                    c.translationY = -collapsedAbove.toFloat()
                    c.clipBounds = Rect(0, 0, c.width, (c.height - local).coerceAtLeast(0))
                    collapsedAbove += local
                }
                BEHAVIOR_COLLAPSE -> {
                    // 顶锚固定在 (laidTop - collapsedAbove)：内容全速上滚 (translationY=-(above+local))，
                    // 把上滚出顶的 local 像素裁掉 → 可见区从顶锚开始、高度收缩 (height-local)，底被 body 顶起。
                    val occupied = c.height + lp.topMargin + lp.bottomMargin
                    val local = (headerOffset - collapsedAbove).coerceIn(0, occupied)
                    c.translationY = -(collapsedAbove + local).toFloat()
                    val clipTop = local.coerceIn(0, c.height)
                    c.clipBounds = Rect(0, clipTop, c.width, c.height)
                    collapsedAbove += local
                }
                BEHAVIOR_CURTAIN -> {
                    // 闭幕：上下对称裁 local，内容向中间合拢。
                    // 整体再上移半个裁量 (clipTopPx)，让可见区顶部对齐顶锚 → 与上下区块贴合不露白。
                    val occupied = c.height + lp.topMargin + lp.bottomMargin
                    val local = (headerOffset - collapsedAbove).coerceIn(0, occupied)
                    val clipTopPx = (local / 2).coerceIn(0, c.height)
                    val clipBotPx = local - local / 2
                    val bottom = (c.height - clipBotPx).coerceAtLeast(clipTopPx)
                    c.translationY = -(collapsedAbove + clipTopPx).toFloat()
                    c.clipBounds = Rect(0, clipTopPx, c.width, bottom)
                    collapsedAbove += local
                }
                BEHAVIOR_PIN -> {
                    c.translationY = 0f
                }
                BEHAVIOR_SCRIM -> {
                    c.translationY = 0f
                    // alpha 在 (triggerRatio, 1) 区间内 0→1 线性渐现。
                    // trigger=1 极端值单独处理：完全折叠时直接给 1，避免除零退化为 0。
                    val trigger = lp.scrimTriggerRatio
                    c.alpha = when {
                        trigger >= 1f -> if (ratio >= 1f) 1f else 0f
                        ratio <= trigger -> 0f
                        else -> ((ratio - trigger) / (1f - trigger)).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    /**
     * 把 dy 作用到 header offset。
     *   dy>0：希望折叠（content 向上），返回真实折叠量 in [0, dy]
     *   dy<0：希望展开（content 向下），返回真实展开量 in [dy, 0]
     */
    private fun consumeHeaderScroll(dy: Int): Int {
        if (dy == 0 || maxOffset == 0) return 0
        val target = (headerOffset + dy).coerceIn(0, maxOffset)
        val actual = target - headerOffset
        if (actual != 0) {
            headerOffset = target
            applyOffsetsToAllChildren()
            dispatchOffsetChanged()
        }
        return actual
    }

    fun setHeaderOffsetImmediately(offset: Int) {
        val target = offset.coerceIn(0, maxOffset)
        if (target != headerOffset) {
            headerOffset = target
            applyOffsetsToAllChildren()
            dispatchOffsetChanged()
        }
    }

    /**
     * 让外层 SwipeRefreshLayout 等控件正确判断"列表能否继续向上/向下滚"。
     * canChildScrollUp = true → 不触发刷新；false → 才允许出 spinner。
     *
     * direction<0 表示问"还能继续向上滚动吗"（其实是问内容还能继续显示更靠前的部分），
     * 等价于"header 还能展开"。
     */
    override fun canScrollVertically(direction: Int): Boolean = when {
        direction < 0 -> headerOffset > 0
        direction > 0 -> headerOffset < maxOffset
        else -> false
    }

    // ---- 头部区域拖动：onInterceptTouchEvent / onTouchEvent ----------------
    //
    // 设计要点：
    //   1. DOWN 永不拦截：让 Tab/banner 的点击/按下反馈正常触发；
    //   2. MOVE 超 touchSlop 且 |dy|>|dx|（避免和 ViewPager2 横向滑动打架）才拦截；
    //   3. 拦截成立的前提是 DOWN 落点在 scroll/sticky 子上 —— 在 body 上的拖动走嵌套滚动协议；
    //   4. 拖动中若 header 已到上界仍有剩余 dy，把剩余喂给 body 内第一个可竖向滚的子。

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isHeaderDragging = false
                activePointerId = ev.getPointerId(0)
                initialDownX = ev.x
                initialDownY = ev.y
                lastTouchY = ev.y
                initialTouchOnHeader = isPointOnHeader(initialDownX.toInt(), initialDownY.toInt())
                // DOWN 上一律中断我方的 fling/snap；如果落在 header 上，连带把 body 的 fling 也停掉，
                // 这样按下 → 拖动间不会出现"边按边滚"的视觉撕裂。
                abortAnyOurScroll()
                if (initialTouchOnHeader) stopBodyFling()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!initialTouchOnHeader) return false
                val pi = ev.findPointerIndex(activePointerId)
                if (pi < 0) return false
                val dy = ev.getY(pi) - initialDownY
                val dx = ev.getX(pi) - initialDownX
                if (!isHeaderDragging && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    isHeaderDragging = true
                    // 越过 slop 的部分不算入第一次位移，避免首帧跳一下
                    lastTouchY = if (dy > 0) initialDownY + touchSlop else initialDownY - touchSlop
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isHeaderDragging = false
                activePointerId = INVALID_POINTER_ID
            }
        }
        return isHeaderDragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        ensureVelocityTracker().addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // child 没消费 DOWN（如裸 ImageView）时会走到这里。重置一遍状态，
                // 保证只在 header 落点上参与拖动。
                activePointerId = ev.getPointerId(0)
                initialDownX = ev.x
                initialDownY = ev.y
                lastTouchY = ev.y
                initialTouchOnHeader = isPointOnHeader(initialDownX.toInt(), initialDownY.toInt())
                abortAnyOurScroll()
                if (!initialTouchOnHeader) {
                    // 不接管这条触摸流：系统不会再派发 MOVE/UP 给我们 ——
                    // 必须把开头分配的 VT 立刻回收，否则永久泄漏一份。
                    recycleVelocityTracker()
                    return false
                }
                stopBodyFling()
                // 即便落点在 header，DOWN 也不主动消费 —— 等 MOVE 超 slop 再说。
                // 但必须返回 true 才能继续收到 MOVE 事件。
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = ev.findPointerIndex(activePointerId)
                if (pi < 0) return false
                val y = ev.getY(pi)
                if (!isHeaderDragging) {
                    val dy = y - initialDownY
                    val dx = ev.getX(pi) - initialDownX
                    if (initialTouchOnHeader && abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                        isHeaderDragging = true
                        lastTouchY = if (dy > 0) initialDownY + touchSlop else initialDownY - touchSlop
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (isHeaderDragging) {
                    val rawDy = lastTouchY - y  // 正：手指向上滑 → 折叠
                    val dyInt = rawDy.toInt()
                    if (dyInt != 0) {
                        // 保留亚像素余量，避免连续小位移被反复截零
                        lastTouchY -= dyInt
                        val consumed = consumeHeaderScroll(dyInt)
                        val leftover = dyInt - consumed
                        if (leftover != 0) {
                            forwardScrollToBody(leftover)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 多指按下：把活跃 pointer 换成最新按下的，重新基准 lastTouchY
                val idx = ev.actionIndex
                activePointerId = ev.getPointerId(idx)
                lastTouchY = ev.getY(idx)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                if (ev.getPointerId(idx) == activePointerId) {
                    val newIdx = if (idx == 0) 1 else 0
                    activePointerId = ev.getPointerId(newIdx)
                    lastTouchY = ev.getY(newIdx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = isHeaderDragging
                isHeaderDragging = false
                if (wasDragging && ev.actionMasked == MotionEvent.ACTION_UP) {
                    // VT 的速度方向：finger 向下为正。我们的"折叠方向"是 finger 向上 → 取负。
                    val vt = velocityTracker
                    vt?.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
                    val vyTracker = vt?.getYVelocity(activePointerId) ?: 0f
                    val vy = -vyTracker
                    if (abs(vy) >= minFlingVelocity) {
                        startHeaderFling(vy)
                    } else {
                        maybeStartSnap()
                    }
                } else if (wasDragging) {
                    // CANCEL：不算速度，但若停在中段仍 snap，避免悬挂
                    maybeStartSnap()
                }
                activePointerId = INVALID_POINTER_ID
                recycleVelocityTracker()
                return true
            }
        }
        return true
    }

    private fun isPointOnHeader(x: Int, y: Int): Boolean {
        // 反序遍历：先匹配 Z 序最上层（pin/scrim 已被 bringChildToFront）
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i)
            if (c.isGone) continue
            // 用 visual Y（含 translationY），因为新模型用 translationY 而非 offsetTopAndBottom
            val visualTop = c.top + c.translationY.toInt()
            val visualBottom = c.bottom + c.translationY.toInt()
            if (x < c.left || x >= c.right || y < visualTop || y >= visualBottom) continue

            val behavior = (c.layoutParams as LayoutParams).behavior
            val isHeaderBehavior = behavior == BEHAVIOR_SCROLL ||
                    behavior == BEHAVIOR_STICKY ||
                    behavior == BEHAVIOR_PARALLAX ||
                    behavior == BEHAVIOR_PIN ||
                    behavior == BEHAVIOR_SCRIM ||
                    behavior == BEHAVIOR_SQUEEZE ||
                    behavior == BEHAVIOR_COLLAPSE ||
                    behavior == BEHAVIOR_CURTAIN
            if (!isHeaderBehavior) return false

            // 触摸点下若有"可竖向滚 + 开了 nested scroll"的 view（包括 c 自身或其后代），
            // 让它自己处理：触摸事件正常派发给它，它的滚动通过 nested 协议驱动 header 折叠。
            // 否则（裸 ImageView 等不可滚的子）我们才接管，提供"拖头图就能折叠"的体验。
            val childX = x - c.left - c.translationX.toInt()
            val childY = y - c.top - c.translationY.toInt()
            if (isOrContainsInteractiveNestedScrollingAt(c, childX, childY)) return false

            // 触摸点本身没落在 NSC 上，但 NSC sibling 离它很近（touchSlop 内），也让位 ——
            // 避免用户在 RV 顶端边缘略偏上 1-2px 落点意外触发 header drag，
            // 而事实上他们的意图是想滚 RV。
            if (hasNearbyNscSibling(x, y, c)) return false
            return true
        }
        return false
    }

    /**
     * 触摸点 (x, y) 是否落在某个 sibling 的"扩展 touchSlop"边界内、且该 sibling 是可滚 NSC。
     * 仅用于 [isPointOnHeader] 的边缘容错。
     */
    private fun hasNearbyNscSibling(x: Int, y: Int, excludeSelf: View): Boolean {
        val margin = touchSlop
        for (i in 0 until childCount) {
            val sib = getChildAt(i)
            if (sib === excludeSelf) continue
            if (sib.visibility != VISIBLE) continue
            val sTop = sib.top + sib.translationY.toInt()
            val sBottom = sib.bottom + sib.translationY.toInt()
            // X 严格在 sib 内（横向不扩，避免和左右 sibling 抢）
            if (x < sib.left || x >= sib.right) continue
            // Y 扩展 margin
            if (y < sTop - margin || y >= sBottom + margin) continue
            // 把触摸点钳到 sib 本地坐标里再做 NSC 检测，超出 sib 真实边界的就归到最近的边
            val localX = x - sib.left - sib.translationX.toInt()
            val localY = (y - sib.top - sib.translationY.toInt()).coerceIn(0, sib.height - 1)
            if (isOrContainsInteractiveNestedScrollingAt(sib, localX, localY)) return true
        }
        return false
    }

    /**
     * [view] 自己或它子树中、触摸点（[x], [y] 是 [view] 本地坐标）下方，是否有
     * "可竖向滚 + 开了 nested scroll"的目标。
     *
     * 关键是要**把 view 自己也算进去**：RV 直接作为 NHL 的 parallax/pin 等角色子时，
     * 我们传进来的 view 就是 RV 本身，不能只看它的子孙。
     */
    private fun isOrContainsInteractiveNestedScrollingAt(view: View, x: Int, y: Int): Boolean {
        if (view.visibility != VISIBLE) return false
        // 1) 先看 view 自己
        if (view.isNestedScrollingEnabled &&
            (view.canScrollVertically(-1) || view.canScrollVertically(1))
        ) {
            return true
        }
        // 2) 再向子孙递归（反序遍历贴合 Z 序）
        if (view !is ViewGroup) return false
        for (i in view.childCount - 1 downTo 0) {
            val c = view.getChildAt(i)
            // 触摸坐标换算到 c 的本地：含 view.scrollX/Y 和 c.translationX/Y
            val tx = x + view.scrollX
            val ty = y + view.scrollY
            val left = c.left + c.translationX.toInt()
            val top = c.top + c.translationY.toInt()
            if (tx < left || tx >= left + c.width) continue
            if (ty < top || ty >= top + c.height) continue
            if (isOrContainsInteractiveNestedScrollingAt(c, tx - left, ty - top)) return true
        }
        return false
    }

    private fun forwardScrollToBody(dy: Int) {
        val body = findBodyChild() ?: return
        val target = findVerticallyScrollableDescendant(body, dy) ?: return
        target.scrollBy(0, dy)
    }

    // ---- fling / snap ------------------------------------------------------

    /**
     * 启动 header 自驱 fling。vy 约定：正 = 折叠方向（向上 fling），负 = 展开方向。
     * 到达边界（0 或 maxOffset）时把剩余速度转交给 body 的 RecyclerView.fling，
     * 让"在头图上一甩"也能继续把列表带起来。
     */
    private fun startHeaderFling(vy: Float) {
        val v = vy.roundToInt()
        if (v == 0) return
        // 方向上已无空间 → 直接交给 body fling，不空跑一段 OverScroller
        if (v > 0 && headerOffset >= maxOffset) {
            forwardFlingToBody(v); return
        }
        if (v < 0 && headerOffset <= 0) {
            forwardFlingToBody(v); return
        }
        scrollerAction = ScrollerAction.FLING
        scroller.fling(
            0, headerOffset,
            0, v,
            0, 0,
            0, maxOffset
        )
        postInvalidateOnAnimation()
    }

    private fun forwardFlingToBody(signedVy: Int) {
        val body = findBodyChild() ?: return
        val target = findVerticallyScrollableDescendant(body, signedVy) ?: return
        if (target is RecyclerView) target.fling(0, signedVy)
    }

    /** 若 header 处在 [1, maxOffset-1] 区间，按阈值 snap 到 0 或 maxOffset。 */
    private fun maybeStartSnap() {
        if (!snapEnabled) return
        if (maxOffset <= 0) return
        if (headerOffset <= 0 || headerOffset >= maxOffset) return
        val target = if (headerOffset.toFloat() / maxOffset >= snapThresholdRatio) maxOffset else 0
        val delta = target - headerOffset
        if (delta == 0) return
        // 持续时间按距离线性，封顶 SNAP_MAX_DURATION_MS，最短 SNAP_MIN_DURATION_MS
        val duration = (abs(delta).toFloat() / maxOffset * SNAP_MAX_DURATION_MS)
            .toInt()
            .coerceIn(SNAP_MIN_DURATION_MS, SNAP_MAX_DURATION_MS)
        scrollerAction = ScrollerAction.SNAP
        scroller.startScroll(0, headerOffset, 0, delta, duration)
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        if (scrollerAction == ScrollerAction.NONE) return
        if (!scroller.computeScrollOffset()) {
            val wasAction = scrollerAction
            scrollerAction = ScrollerAction.NONE
            // fling 自然结束（没撞边）时若停在中段 → snap
            if (wasAction == ScrollerAction.FLING) maybeStartSnap()
            return
        }
        val targetY = scroller.currY
        val dy = targetY - headerOffset
        if (dy != 0) {
            val consumed = consumeHeaderScroll(dy)
            if (consumed != dy && scrollerAction == ScrollerAction.FLING) {
                // 撞到边界还有剩余 → 把剩余速度交给 body 的 RecyclerView 继续 fling
                val direction = if (dy > 0) 1 else -1
                val remain = scroller.currVelocity
                scroller.abortAnimation()
                scrollerAction = ScrollerAction.NONE
                if (remain.isFinite() && remain >= minFlingVelocity) {
                    forwardFlingToBody((remain * direction).roundToInt())
                }
                return
            }
        }
        postInvalidateOnAnimation()
    }

    private fun abortAnyOurScroll() {
        if (!scroller.isFinished) scroller.abortAnimation()
        scrollerAction = ScrollerAction.NONE
    }

    private fun stopBodyFling() {
        val body = findBodyChild() ?: return
        findFlingableRecyclerView(body)?.stopScroll()
    }

    private fun findFlingableRecyclerView(v: View): RecyclerView? {
        if (v.visibility != VISIBLE) return null
        if (v is RecyclerView) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child.right <= 0 || child.left >= v.width) continue
                val r = findFlingableRecyclerView(child)
                if (r != null) return r
            }
        }
        return null
    }

    private fun ensureVelocityTracker(): VelocityTracker {
        return velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private enum class ScrollerAction { NONE, FLING, SNAP }

    private fun findBodyChild(): View? {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.isGone) continue
            if ((c.layoutParams as LayoutParams).behavior == BEHAVIOR_BODY) return c
        }
        return null
    }

    /**
     * 从 [target] 一路向上走，找到 [target] 所在的 NHL 直接子的 behavior。
     * 用来在 onNestedPreScroll/onNestedScroll 里区分滚动来源 ——
     * body 来的走"header 先折再 RV 滚"，非 body 来的走"RV 自己先滚到边界再折 header"。
     */
    private fun findHostingBehavior(target: View): Int {
        var v: View? = target
        while (v != null && v.parent !== this) {
            v = v.parent as? View
        }
        return (v?.layoutParams as? LayoutParams)?.behavior ?: BEHAVIOR_BODY
    }

    /**
     * 在 body 子树中找第一个可在 dy 方向竖向滚动的 View。
     * 横向越界的 child（例如 ViewPager2 内已经被划过去的页）会被跳过，
     * 保证拿到的是当前可见页里的滚动容器。
     */
    private fun findVerticallyScrollableDescendant(v: View, dy: Int): View? {
        if (v.visibility != VISIBLE) return null
        val direction = if (dy > 0) 1 else -1
        if (v.canScrollVertically(direction)) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val child = v.getChildAt(i)
                if (child.right <= 0 || child.left >= v.width) continue
                val found = findVerticallyScrollableDescendant(child, dy)
                if (found != null) return found
            }
        }
        return null
    }

    // ---- LayoutParams ------------------------------------------------------

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): LayoutParams =
        LayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?): Boolean = p is LayoutParams

    /**
     * 继承 MarginLayoutParams，所以子 View 支持标准的 android:layout_margin*（含 RTL 的 marginStart/End）。
     * margin 的语义：
     *   - scroll/parallax/sticky：topMargin/bottomMargin 在垂直堆叠里加间距，计入 maxOffset / body 高度扣减
     *   - pin/scrim：相对各自顶部锚点 (Y=0) 偏移 topMargin/leftMargin，多个 pin 仍重叠
     *   - body：margin 直接缩小 body 的可用宽高
     */
    class LayoutParams : ViewGroup.MarginLayoutParams {
        var behavior: Int = BEHAVIOR_BODY

        /** 仅 BEHAVIOR_PARALLAX 用，[0, 1]：0 = 不动，1 = 同 scroll 同步。 */
        var parallaxMultiplier: Float = DEFAULT_PARALLAX_MULTIPLIER

        /** 仅 BEHAVIOR_SCRIM 用：折叠比例 >= 此值开始渐显。 */
        var scrimTriggerRatio: Float = DEFAULT_SCRIM_TRIGGER_RATIO

        constructor(width: Int, height: Int) : super(width, height)

        constructor(source: ViewGroup.LayoutParams?) : super(source) {
            // super(ViewGroup.LayoutParams) 不复制 margin，这里补上
            if (source is ViewGroup.MarginLayoutParams) {
                leftMargin = source.leftMargin
                topMargin = source.topMargin
                rightMargin = source.rightMargin
                bottomMargin = source.bottomMargin
                marginStart = source.marginStart
                marginEnd = source.marginEnd
            }
            if (source is LayoutParams) {
                behavior = source.behavior
                parallaxMultiplier = source.parallaxMultiplier
                scrimTriggerRatio = source.scrimTriggerRatio
            }
        }

        constructor(c: Context, attrs: AttributeSet?) : super(c, attrs) {
            // 父类 MarginLayoutParams(Context, AttributeSet) 已自动读了 android:layout_margin*
            c.withStyledAttributes(attrs, R.styleable.NestedHeaderLayout_Layout) {
                behavior = getInt(
                    R.styleable.NestedHeaderLayout_Layout_layout_nh_behavior,
                    BEHAVIOR_BODY
                )
                parallaxMultiplier = getFloat(
                    R.styleable.NestedHeaderLayout_Layout_layout_nh_parallaxMultiplier,
                    DEFAULT_PARALLAX_MULTIPLIER
                ).coerceIn(0f, 1f)
                scrimTriggerRatio = getFloat(
                    R.styleable.NestedHeaderLayout_Layout_layout_nh_scrimTriggerRatio,
                    DEFAULT_SCRIM_TRIGGER_RATIO
                ).coerceIn(0f, 1f)
            }
        }
    }

    // ---- NestedScrollingParent3 -------------------------------------------

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int): Boolean {
        // 所有子（包括非 body 的 scroll/parallax/pin/scrim/sticky）发起的 vertical nested scroll 都接管。
        // 这样放在头图、Toolbar 等位置的 RecyclerView：自己滚 = 上滑时先把 header 折叠（onNestedPreScroll 吃掉 dy>0），
        // 折完后 leftover 才给自己的内容滚；下滑时先自己滚到顶，剩余的 unconsumed 再给我们展开 header。
        // TOUCH/NON_TOUCH 都接：fling 阶段也走同一条逻辑，避免双 OverScroller 撕裂。
        return (axes and ViewCompat.SCROLL_AXIS_VERTICAL) != 0
    }

    override fun onStartNestedScroll(child: View, target: View, axes: Int): Boolean =
        onStartNestedScroll(child, target, axes, ViewCompat.TYPE_TOUCH)

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) {
        parentHelper.onNestedScrollAccepted(child, target, axes, type)
        // 我作为 nested-scrolling-child，也向外层 parent（可能是 SwipeRefreshLayout）开会话，
        // 这样后续 dispatchNestedScroll 才能被外层认领。
        startNestedScroll(axes, type)
    }

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int) {
        onNestedScrollAccepted(child, target, axes, ViewCompat.TYPE_TOUCH)
    }

    override fun onStopNestedScroll(target: View, type: Int) {
        parentHelper.onStopNestedScroll(target, type)
        stopNestedScroll(type)
        // body 的 fling 结束（NON_TOUCH 停）。此时如果 header 卡在中段，触发 snap。
        // TOUCH 停不在这里 snap：避免和"用户随后启动 fling"的事件抢同一帧。
        if (type == ViewCompat.TYPE_NON_TOUCH) {
            maybeStartSnap()
        }
    }

    override fun onStopNestedScroll(target: View) {
        onStopNestedScroll(target, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        // dy>0（内容要向上 = header 折叠方向）：
        //   - 来源 = body：父优先折 header，剩余的才给 body 自己滚（CoordinatorLayout 的经典行为）
        //   - 来源 = 非 body（如头图内嵌 RV）：父不抢，让 RV 自己先滚自己的内容；
        //     RV 到底之后通过 onNestedScroll 的 dyUnconsumed>0 才折 header
        if (dy > 0 && findHostingBehavior(target) == BEHAVIOR_BODY) {
            consumed[1] = consumeHeaderScroll(dy)
        }
        // 没消耗完的 dy 继续上抛给外层 parent。
        // 用途：外层 SwipeRefreshLayout 的 preScroll 在 dy>0 时取消 mTotalUnconsumed（spinner 收回）。
        val remainDy = dy - consumed[1]
        val remainDx = dx - consumed[0]
        if (remainDx != 0 || remainDy != 0) {
            val parentConsumed = IntArray(2)
            dispatchNestedPreScroll(remainDx, remainDy, parentConsumed, null, type)
            consumed[0] += parentConsumed[0]
            consumed[1] += parentConsumed[1]
        }
    }

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray) {
        onNestedPreScroll(target, dx, dy, consumed, ViewCompat.TYPE_TOUCH)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int,
        consumed: IntArray
    ) {
        // 两段消费（其余转发给外层 parent）：
        //   1) dyUnconsumed<0：child 到顶仍想下拉 → 父展开 header。两种来源都吃，对称的"列表到顶接力展开"。
        //   2) dyUnconsumed>0：child 到底仍想上推 → 仅非 body 来源吃；这是"内嵌 RV 滚到底之后才折 header"的关键
        //                       body 来源此处 dyUnconsumed>0 几乎不会出现（已被 preScroll 折过），即便出现 consume 也是 0
        if (dyUnconsumed < 0) {
            consumed[1] += consumeHeaderScroll(dyUnconsumed)
        } else if (dyUnconsumed > 0 && findHostingBehavior(target) != BEHAVIOR_BODY) {
            consumed[1] += consumeHeaderScroll(dyUnconsumed)
        }
        // 转发到外层 parent：header 也消化完了仍有剩余 → 让外层 SwipeRefreshLayout 等接管。
        dispatchNestedScroll(
            dxConsumed + consumed[0],
            dyConsumed + consumed[1],
            dxUnconsumed - consumed[0],
            dyUnconsumed - consumed[1],
            null,
            type,
            consumed
        )
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int,
        type: Int
    ) {
        val tmp = IntArray(2)
        onNestedScroll(target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, type, tmp)
    }

    override fun onNestedScroll(
        target: View,
        dxConsumed: Int,
        dyConsumed: Int,
        dxUnconsumed: Int,
        dyUnconsumed: Int
    ) {
        onNestedScroll(
            target, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, ViewCompat.TYPE_TOUCH
        )
    }

    override fun onNestedPreFling(target: View, velocityX: Float, velocityY: Float): Boolean = false

    override fun onNestedFling(
        target: View, velocityX: Float, velocityY: Float, consumed: Boolean
    ): Boolean = false

    override fun getNestedScrollAxes(): Int = parentHelper.nestedScrollAxes

    // ---- NestedScrollingChild3：把没消耗完的 dy 转给外层（如 SwipeRefreshLayout）-------

    override fun setNestedScrollingEnabled(enabled: Boolean) {
        childHelper.isNestedScrollingEnabled = enabled
    }

    override fun isNestedScrollingEnabled(): Boolean = childHelper.isNestedScrollingEnabled

    override fun startNestedScroll(axes: Int): Boolean = childHelper.startNestedScroll(axes)

    override fun startNestedScroll(axes: Int, type: Int): Boolean =
        childHelper.startNestedScroll(axes, type)

    override fun stopNestedScroll() {
        childHelper.stopNestedScroll()
    }

    override fun stopNestedScroll(type: Int) {
        childHelper.stopNestedScroll(type)
    }

    override fun hasNestedScrollingParent(): Boolean = childHelper.hasNestedScrollingParent()

    override fun hasNestedScrollingParent(type: Int): Boolean =
        childHelper.hasNestedScrollingParent(type)

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow
    )

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int
    ): Boolean = childHelper.dispatchNestedScroll(
        dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type
    )

    override fun dispatchNestedScroll(
        dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int,
        offsetInWindow: IntArray?, type: Int, consumed: IntArray
    ) {
        childHelper.dispatchNestedScroll(
            dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed, offsetInWindow, type, consumed
        )
    }

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow)

    override fun dispatchNestedPreScroll(
        dx: Int, dy: Int, consumed: IntArray?, offsetInWindow: IntArray?, type: Int
    ): Boolean = childHelper.dispatchNestedPreScroll(dx, dy, consumed, offsetInWindow, type)

    override fun dispatchNestedFling(
        velocityX: Float, velocityY: Float, consumed: Boolean
    ): Boolean = childHelper.dispatchNestedFling(velocityX, velocityY, consumed)

    override fun dispatchNestedPreFling(velocityX: Float, velocityY: Float): Boolean =
        childHelper.dispatchNestedPreFling(velocityX, velocityY)

    // ---- SavedState --------------------------------------------------------

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val ratio = if (maxOffset > 0) headerOffset.toFloat() / maxOffset else 0f
        return SavedState(superState, ratio)
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is SavedState) {
            super.onRestoreInstanceState(state)
            return
        }
        super.onRestoreInstanceState(state.superState)
        pendingOffsetRatio = state.offsetRatio.coerceIn(0f, 1f)
        requestLayout()
    }

    private class SavedState : BaseSavedState {
        val offsetRatio: Float

        constructor(superState: Parcelable?, ratio: Float) : super(superState) {
            this.offsetRatio = ratio
        }

        constructor(source: Parcel) : super(source) {
            offsetRatio = source.readFloat()
        }

        override fun writeToParcel(out: Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeFloat(offsetRatio)
        }

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: Parcel): SavedState = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    fun interface OnHeaderOffsetChangedListener {
        fun onOffsetChanged(layout: NestedHeaderLayout, offset: Int, maxOffset: Int)
    }

    companion object {
        const val BEHAVIOR_SCROLL = 0
        const val BEHAVIOR_STICKY = 1
        const val BEHAVIOR_BODY = 2
        const val BEHAVIOR_PARALLAX = 3
        const val BEHAVIOR_PIN = 4
        const val BEHAVIOR_SCRIM = 5
        const val BEHAVIOR_SQUEEZE = 6
        const val BEHAVIOR_COLLAPSE = 7
        const val BEHAVIOR_CURTAIN = 8

        const val DEFAULT_PARALLAX_MULTIPLIER = 0.5f
        const val DEFAULT_SCRIM_TRIGGER_RATIO = 0.7f

        private const val INVALID_POINTER_ID = -1

        private const val DEFAULT_SNAP_THRESHOLD_RATIO = 0.5f

        private const val SNAP_MIN_DURATION_MS = 120
        private const val SNAP_MAX_DURATION_MS = 240
    }
}
