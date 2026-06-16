package com.chat.mylibrary.widget

import android.graphics.Rect
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class BarrageMode {
    /** 实时直播弹幕：业务持续 append，新弹幕按接收顺序滚动。 */
    LIVE,

    /** 视频时间轴弹幕：由外部按播放器进度投递对应时间弹幕。 */
    VIDEO_TIMELINE
}

/**
 * 从右往左移动的弹幕 LayoutManager，支持实时追加单条弹幕。
 *
 * @param baseSpeedPxPerFrame 默认滚动速度，单位 px/frame；数值越大移动越快。
 * @param horizontalGapDp 同轨道前后两条弹幕之间的最小横向间距，单位 dp。
 * @param verticalGapDp 相邻轨道/弹幕之间的最小纵向间距，单位 dp。
 * @param maxActiveItemCount 最大同时显示的弹幕数量，超过后会等待已有弹幕移出屏幕再继续布局。
 * @param maxTrackCount 最大轨道数，用于限制屏幕内最多显示多少行弹幕。
 * @param areaTopDp 弹幕显示区域顶部额外留白，单位 dp；会叠加 RecyclerView 的 paddingTop。
 * @param areaBottomDp 弹幕显示区域底部额外留白，单位 dp；会叠加 RecyclerView 的 paddingBottom。
 * @param initialVisible 首次布局时第一条弹幕是否直接显示在屏幕内；false 表示也从右侧屏幕外进入。
 * @param mode 弹幕使用模式：LIVE 用于实时直播追加；VIDEO_TIMELINE 用于视频时间轴调度。
 * @param priorityProvider 按 adapter position 提供弹幕优先级，当前用于记录和业务扩展；返回值越大优先级越高。
 * @param speedProvider 按 adapter position 提供单条弹幕速度；返回 null 时使用 baseSpeedPxPerFrame。
 */
class BarrageLayoutManager(
    private val baseSpeedPxPerFrame: Float = DEFAULT_BASE_SPEED,
    private val horizontalGapDp: Float = DEFAULT_HORIZONTAL_GAP_DP,
    private val verticalGapDp: Float = DEFAULT_VERTICAL_GAP_DP,
    private val maxActiveItemCount: Int = DEFAULT_MAX_ACTIVE_ITEM_COUNT,
    private val maxTrackCount: Int = Int.MAX_VALUE,
    private val areaTopDp: Float = 0f,
    private val areaBottomDp: Float = 0f,
    private val initialVisible: Boolean = true,
    val mode: BarrageMode = BarrageMode.LIVE,
    private val priorityProvider: ((position: Int) -> Int)? = null,
    private val speedProvider: ((position: Int) -> Float?)? = null
) : RecyclerView.LayoutManager() {

    private val activeItems = mutableListOf<BarrageItemState>()
    private val pausedPositions = mutableSetOf<Int>()
    private var recyclerView: RecyclerView? = null
    private var nextLayoutPosition = 0
    private var isRunning = false
    private var hasInitialLayout = false

    // ── VIDEO_TIMELINE 模式状态 ──
    private val timelineEntries = mutableListOf<TimelineEntry>()
    private var nextTimelineIndex = 0
    private var currentVideoTimeMs = 0L
    private var pendingSeekRestore = false

    /**
     * 时间轴条目：时间规则由业务决定，LayoutManager 内部只用 timeMs 定位。
     *
     * @param adapterPosition 对应 adapter 中该弹幕的 position。
     * @param timeMs 该弹幕应出现的视频时间，由业务计算。
     * @param speed 可选单条速度（px/frame），为 null 时使用默认或 speedProvider。
     */
    data class TimelineEntry(
        val adapterPosition: Int,
        val timeMs: Long,
        val speed: Float? = null
    )
    private val frameAction = object : Runnable {
        override fun run() {
            if (!isRunning) return
            moveItems()
            recyclerView?.let { view ->
                if (activeItems.isNotEmpty() || hasPendingItems()) {
                    requestLayout()
                    view.postOnAnimation(this)
                } else {
                    isRunning = false
                }
            }
        }
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        recyclerView = view
        if (isRunning) {
            scheduleNextFrame()
        }
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        release()
        super.onDetachedFromWindow(view, recycler)
    }

    override fun canScrollHorizontally(): Boolean = false

    override fun canScrollVertically(): Boolean = false

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.isPreLayout) return
        if (itemCount == 0 || width <= 0 || height <= 0) {
            activeItems.clear()
            nextLayoutPosition = 0
            detachAndScrapAttachedViews(recycler)
            return
        }
        if (nextLayoutPosition > itemCount) {
            activeItems.clear()
            nextLayoutPosition = 0
        }

        detachAndScrapAttachedViews(recycler)
        recycleOutOfBoundsItems()
        layoutActiveItems(recycler)
        if (mode == BarrageMode.LIVE) {
            layoutNewItems(recycler)
        } else {
            layoutTimelineItems(recycler)
        }
        hasInitialLayout = true
    }

    /** 开始弹幕动画，收到新数据后也可以再次调用。 */
    fun start() {
        isRunning = true
        requestLayout()
        scheduleNextFrame()
    }

    /** 暂停弹幕动画，保留当前弹幕位置。 */
    fun stop() {
        isRunning = false
        recyclerView?.removeCallbacks(frameAction)
    }

    private fun scheduleNextFrame() {
        recyclerView?.let { view ->
            view.removeCallbacks(frameAction)
            view.postOnAnimation(frameAction)
        }
    }

    private fun hasPendingItems(): Boolean {
        return if (mode == BarrageMode.LIVE) {
            nextLayoutPosition < itemCount
        } else {
            nextTimelineIndex < timelineEntries.size
        }
    }

    /** 暂停指定 adapter position 对应的弹幕，其它弹幕继续滚动。 */
    fun pauseItem(position: Int) {
        pausedPositions.add(position)
    }

    /** 恢复指定 adapter position 对应的弹幕滚动。 */
    fun resumeItem(position: Int) {
        pausedPositions.remove(position)
    }

    /**
     * VIDEO_TIMELINE：提交完整时间轴弹幕；内部按 timeMs 排序。
     * 业务需先把全部视频弹幕放入 adapter，再用此方法提供每条的时间规则。
     */
    fun submitTimeline(entries: List<TimelineEntry>) {
        if (mode != BarrageMode.VIDEO_TIMELINE) return
        timelineEntries.clear()
        timelineEntries.addAll(entries.sortedBy { it.timeMs })
        nextTimelineIndex = 0
        currentVideoTimeMs = 0L
        activeItems.clear()
        requestLayout()
    }

    /** VIDEO_TIMELINE：视频进度更新；内部据此显示到达时间的弹幕。 */
    fun updateVideoTime(currentTimeMs: Long) {
        if (mode != BarrageMode.VIDEO_TIMELINE) return
        if (currentTimeMs < currentVideoTimeMs) {
            seekVideoTime(currentTimeMs)
            return
        }
        currentVideoTimeMs = currentTimeMs
        start()
    }

    /** VIDEO_TIMELINE：视频 seek；清屏并还原目标时间窗口内本应在飞的弹幕位置。 */
    fun seekVideoTime(currentTimeMs: Long) {
        if (mode != BarrageMode.VIDEO_TIMELINE) return
        currentVideoTimeMs = currentTimeMs
        activeItems.clear()
        pausedPositions.clear()
        pendingSeekRestore = true
        nextTimelineIndex = lowerBoundByTime(currentTimeMs)
        requestLayout()
        start()
    }

    /** 清空当前屏幕弹幕，已有 adapter 数据不再从头播放。 */
    fun clear() {
        activeItems.clear()
        pausedPositions.clear()
        nextLayoutPosition = itemCount
        nextTimelineIndex = timelineEntries.size
        requestLayout()
    }

    /** 清空当前屏幕弹幕，并从 adapter 第一条重新开始播放。 */
    fun reset() {
        activeItems.clear()
        pausedPositions.clear()
        nextLayoutPosition = 0
        nextTimelineIndex = 0
        currentVideoTimeMs = 0L
        requestLayout()
    }

    /** 释放状态，页面销毁时调用。 */
    fun release() {
        stop()
        activeItems.clear()
        pausedPositions.clear()
        timelineEntries.clear()
        nextLayoutPosition = 0
        nextTimelineIndex = 0
        currentVideoTimeMs = 0L
        recyclerView = null
    }

    private fun moveItems() {
        activeItems.forEach { item ->
            if (item.position !in pausedPositions) {
                item.left -= item.speed
            }
        }
    }

    private fun recycleOutOfBoundsItems() {
        val iterator = activeItems.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.left + item.itemWidth < 0f || item.position >= itemCount) {
                iterator.remove()
            }
        }
    }

    private fun layoutActiveItems(recycler: RecyclerView.Recycler) {
        activeItems.forEach { item ->
            val child = recycler.getViewForPosition(item.position)
            addView(child)
            measureChildWithMargins(child, 0, 0)
            val left = item.left.toInt()
            layoutDecoratedWithMargins(
                child,
                left,
                item.top,
                left + item.itemWidth,
                item.top + item.itemHeight
            )
        }
    }

    private fun layoutNewItems(recycler: RecyclerView.Recycler) {
        while (nextLayoutPosition < itemCount && activeItems.size < maxActiveItemCount) {
            val child = recycler.getViewForPosition(nextLayoutPosition)
            addView(child)
            measureChildWithMargins(child, 0, 0)

            val itemWidth = getDecoratedMeasuredWidth(child)
            val itemHeight = getDecoratedMeasuredHeight(child)
            val speed = speedForPosition(nextLayoutPosition)
            val entryLeft = initialLeft(itemWidth)
            val top = findAvailableTrackTop(itemWidth, itemHeight, speed, entryLeft)
            if (top == null) {
                removeAndRecycleView(child, recycler)
                break
            }
            val item = BarrageItemState(
                position = nextLayoutPosition,
                left = entryLeft,
                top = top,
                speed = speed,
                priority = priorityProvider?.invoke(nextLayoutPosition) ?: 0,
                itemWidth = itemWidth,
                itemHeight = itemHeight
            )
            activeItems.add(item)

            val left = item.left.toInt()
            layoutDecoratedWithMargins(
                child,
                left,
                item.top,
                left + itemWidth,
                item.top + itemHeight
            )
            nextLayoutPosition++
        }
    }

    private fun layoutTimelineItems(recycler: RecyclerView.Recycler) {
        // seek 还原：把 timeMs < 当前时间、但理论上还没飞出屏幕的弹幕按已飞位置补回
        if (pendingSeekRestore) {
            pendingSeekRestore = false
            var index = nextTimelineIndex - 1
            while (index >= 0) {
                val entry = timelineEntries[index]
                val speed = timelineSpeed(entry)
                val traveledPx = travelPx(entry.timeMs, currentVideoTimeMs, speed)
                // 已飞出最大可能宽度则更早的弹幕也都飞出了，停止回溯
                if (traveledPx > width + SEEK_RESTORE_MAX_ITEM_WIDTH_PX) break
                addTimelineItem(recycler, entry, speed, traveledPx)
                index--
            }
        }

        // 到达当前时间、且尚未显示的弹幕从右侧进入
        while (nextTimelineIndex < timelineEntries.size &&
            timelineEntries[nextTimelineIndex].timeMs <= currentVideoTimeMs
        ) {
            val entry = timelineEntries[nextTimelineIndex]
            val speed = timelineSpeed(entry)
            val traveledPx = travelPx(entry.timeMs, currentVideoTimeMs, speed)
            addTimelineItem(recycler, entry, speed, traveledPx)
            nextTimelineIndex++
        }
    }

    /**
     * 把一条时间轴弹幕加入屏幕。
     * traveledPx 为它从“出现时间”到“当前时间”应已飞过的像素，用于 seek 还原位置。
     */
    private fun addTimelineItem(
        recycler: RecyclerView.Recycler,
        entry: TimelineEntry,
        speed: Float,
        traveledPx: Float
    ) {
        if (activeItems.size >= maxActiveItemCount) return
        if (entry.adapterPosition < 0 || entry.adapterPosition >= itemCount) return
        if (activeItems.any { it.position == entry.adapterPosition }) return

        val child = recycler.getViewForPosition(entry.adapterPosition)
        addView(child)
        measureChildWithMargins(child, 0, 0)
        val itemWidth = getDecoratedMeasuredWidth(child)
        val itemHeight = getDecoratedMeasuredHeight(child)

        // 已经完全飞出屏幕的弹幕不再显示
        val startLeft = width.toFloat() - traveledPx
        if (startLeft + itemWidth < 0f) {
            removeAndRecycleView(child, recycler)
            return
        }

        val top = findAvailableTrackTop(itemWidth, itemHeight, speed, startLeft)
        if (top == null) {
            removeAndRecycleView(child, recycler)
            return
        }
        val item = BarrageItemState(
            position = entry.adapterPosition,
            left = startLeft,
            top = top,
            speed = speed,
            priority = priorityProvider?.invoke(entry.adapterPosition) ?: 0,
            itemWidth = itemWidth,
            itemHeight = itemHeight
        )
        activeItems.add(item)
        val left = item.left.toInt()
        layoutDecoratedWithMargins(child, left, item.top, left + itemWidth, item.top + itemHeight)
    }

    private fun timelineSpeed(entry: TimelineEntry): Float {
        val speed = entry.speed ?: speedProvider?.invoke(entry.adapterPosition) ?: baseSpeedPxPerFrame
        return speed.coerceAtLeast(MIN_SPEED_PX_PER_FRAME)
    }

    /** 计算从 fromMs 到 toMs 期间，以 speed(px/frame) 应飞过的像素，按 60fps 估算。 */
    private fun travelPx(fromMs: Long, toMs: Long, speed: Float): Float {
        if (toMs <= fromMs) return 0f
        val frames = (toMs - fromMs) / 1000f * ESTIMATED_FPS
        return frames * speed
    }

    private fun lowerBoundByTime(timeMs: Long): Int {
        var left = 0
        var right = timelineEntries.size
        while (left < right) {
            val mid = (left + right) / 2
            if (timelineEntries[mid].timeMs < timeMs) {
                left = mid + 1
            } else {
                right = mid
            }
        }
        return left
    }

    private fun findAvailableTrackTop(
        itemWidth: Int,
        itemHeight: Int,
        speed: Float,
        entryLeft: Float
    ): Int? {
        return buildTrackTops(itemHeight).firstOrNull { top ->
            canPlaceAt(top, itemWidth, itemHeight, speed, entryLeft)
        }
    }

    private fun buildTrackTops(itemHeight: Int): List<Int> {
        val verticalGap = verticalGapPx()
        val minTop = paddingTop + areaTopPx()
        val maxBottom = height - paddingBottom - areaBottomPx()
        if (itemHeight <= 0 || maxBottom - minTop < itemHeight) return emptyList()

        val trackHeight = itemHeight + verticalGap
        val availableHeight = maxBottom - minTop
        val countByHeight = max(1, (availableHeight + verticalGap) / trackHeight)
        val trackCount = min(countByHeight, max(1, maxTrackCount))
        return List(trackCount) { index -> minTop + index * trackHeight }
    }

    private fun canPlaceAt(
        top: Int,
        itemWidth: Int,
        itemHeight: Int,
        speed: Float,
        entryLeft: Float
    ): Boolean {
        val newLeft = entryLeft
        val horizontalGap = horizontalGapPx()
        val verticalGap = verticalGapPx()
        val newRect = Rect(
            newLeft.toInt(),
            top,
            (newLeft + itemWidth).toInt(),
            top + itemHeight
        )
        return activeItems.none { item ->
            val verticalOverlap = top < item.top + item.itemHeight + verticalGap &&
                    top + itemHeight + verticalGap > item.top
            if (!verticalOverlap) return@none false

            val activeRect = Rect(
                item.left.toInt(),
                item.top - verticalGap,
                (item.left + item.itemWidth + horizontalGap).toInt(),
                item.top + item.itemHeight + verticalGap
            )
            Rect.intersects(activeRect, newRect) || willCatchBeforeExit(
                newLeft = newLeft,
                newSpeed = speed,
                activeItem = item,
                horizontalGap = horizontalGap
            )
        }
    }

    private fun willCatchBeforeExit(
        newLeft: Float,
        newSpeed: Float,
        activeItem: BarrageItemState,
        horizontalGap: Int
    ): Boolean {
        if (newSpeed <= activeItem.speed) return false
        val activeRight = activeItem.left + activeItem.itemWidth + horizontalGap
        val distance = newLeft - activeRight
        if (distance <= 0f) return true

        val framesToCatch = distance / (newSpeed - activeItem.speed)
        val framesToExit = (activeItem.left + activeItem.itemWidth) / activeItem.speed
        return framesToCatch <= framesToExit
    }

    private fun speedForPosition(position: Int): Float {
        val speed = speedProvider?.invoke(position) ?: baseSpeedPxPerFrame
        return speed.coerceAtLeast(MIN_SPEED_PX_PER_FRAME)
    }

    private fun initialLeft(itemWidth: Int): Float {
        if (!initialVisible || hasInitialLayout || activeItems.isNotEmpty()) {
            return width.toFloat() + paddingEnd
        }
        return width - itemWidth.toFloat()
    }

    private fun horizontalGapPx(): Int = dpToPx(horizontalGapDp)

    private fun verticalGapPx(): Int = dpToPx(verticalGapDp)

    private fun areaTopPx(): Int = dpToPx(areaTopDp)

    private fun areaBottomPx(): Int = dpToPx(areaBottomDp)

    private fun dpToPx(value: Float): Int {
        val density = recyclerView?.resources?.displayMetrics?.density ?: 1f
        return ceil(value * density).toInt()
    }

    private data class BarrageItemState(
        val position: Int,
        var left: Float,
        val top: Int,
        val speed: Float,
        val priority: Int,
        val itemWidth: Int,
        val itemHeight: Int
    )

    companion object {
        private const val DEFAULT_BASE_SPEED = 4f
        private const val DEFAULT_HORIZONTAL_GAP_DP = 80f
        private const val DEFAULT_VERTICAL_GAP_DP = 8f
        private const val DEFAULT_MAX_ACTIVE_ITEM_COUNT = 30
        private const val MIN_SPEED_PX_PER_FRAME = 0.5f
        private const val ESTIMATED_FPS = 60f
        private const val SEEK_RESTORE_MAX_ITEM_WIDTH_PX = 2000
    }
}
