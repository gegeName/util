package com.chat.uifoundation.tablayout

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.SparseArray
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import androidx.core.util.size

/**
 * 角标样式。
 */
sealed class BadgeStyle {
    /** 红点（无文字） */
    object Dot : BadgeStyle()

    /** 数字角标；超过 [cap] 显示为 "cap+"，<=0 等同于隐藏 */
    data class Count(val count: Int, val cap: Int = 99) : BadgeStyle() {
        internal fun display(): String = if (count > cap) "$cap+" else count.toString()
    }

    /** 自定义文字角标（如 "NEW"、"HOT"） */
    data class Text(val text: CharSequence) : BadgeStyle()
}

/**
 * 角标绘制层。
 *
 * 它是 [MagicTabLayout] 的内部覆盖层（叠在 stage 顶层），不依赖把 tab 包一层 FrameLayout，
 * 也不依赖 Provider 类型 —— 直接按位置去 tabContainer 取 child 的 left/right/top 算坐标。
 *
 * 三个进阶特性：
 * - **入场动画**：新增/替换的角标做 OvershootInterpolator 缩放 + 透明度入场（[enterAnimDuration] = 0 关闭）
 * - **描边**：[strokeColor] + [strokeWidthPx]，常用于角标盖在白底 Tab 上的轻外圈
 * - **点击回调**：设置 [onBadgeClick]（[clickHitInsetPx] 调大可放大命中范围，使红点也好按）
 */
class BadgeOverlay internal constructor(context: Context) : View(context) {

    /* -------------------- 数据 -------------------- */

    private val badges = SparseArray<BadgeStyle>()
    private var tabContainer: LinearLayout? = null

    /** 入场动画进度：position -> 0f..1f */
    private val animProgress = SparseArray<Float>()
    private val animators = SparseArray<ValueAnimator>()

    /** 最近一次实际绘制的 bounds，供命中测试用 */
    private val lastDrawnBounds = SparseArray<RectF>()

    private var downHit: Int = -1

    /** 仅在命中测试时复用的临时矩形，避免每次 inset 都 new */
    private val tempHitRect = RectF()

    /* -------------------- 外观可调 -------------------- */

    /** 角标背景色 */
    var bgColor: Int = Color.parseColor("#FF3B30")
        set(v) { field = v; bgPaint.color = v; invalidate() }

    /** 文字色 */
    var textColor: Int = Color.WHITE
        set(v) { field = v; textPaint.color = v; invalidate() }

    /** 文字字号（sp） */
    var textSizeSp: Float = 10f
        set(v) {
            field = v
            textPaint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics
            )
            invalidate()
        }

    /** 红点直径（px） */
    var dotSizePx: Float = dp(8f)
        set(v) { field = v; invalidate() }

    /** 文字/数字角标的最小高度（px），同时作为圆角半径基准 */
    var minHeightPx: Float = dp(16f)
        set(v) { field = v; invalidate() }

    /** 文字/数字角标的水平内边距（px） */
    var paddingHPx: Float = dp(5f)
        set(v) { field = v; invalidate() }

    /** 角标相对 tab 右上角的 X 偏移（px），负值向内推 */
    var offsetXPx: Float = dp(-4f)
        set(v) { field = v; invalidate() }

    /** 角标相对 tab 右上角的 Y 偏移（px），正值向下推 */
    var offsetYPx: Float = dp(6f)
        set(v) { field = v; invalidate() }

    /** 描边颜色（默认透明 = 不描边） */
    var strokeColor: Int = Color.TRANSPARENT
        set(v) { field = v; strokePaint.color = v; invalidate() }

    /** 描边宽度（px），<=0 不描边 */
    var strokeWidthPx: Float = 0f
        set(v) { field = v; strokePaint.strokeWidth = v; invalidate() }

    /** 入场动画时长（毫秒），0 关闭动画 */
    var enterAnimDuration: Long = 200L

    /** 入场动画起始缩放（0f..1f） */
    var enterAnimMinScale: Float = 0.5f

    /** 点击命中区外扩（px），正值放大命中范围，使红点也好点 */
    var clickHitInsetPx: Float = dp(6f)

    /** 角标点击回调；不设则不消费触摸事件、不影响 Tab 点击 */
    var onBadgeClick: ((position: Int, style: BadgeStyle) -> Unit)? = null

    /* -------------------- Paint -------------------- */

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = bgColor
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics
        )
        textAlign = Paint.Align.CENTER
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = strokeColor
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
    }

    init {
        setWillNotDraw(false)
    }

    /* -------------------- 对 MagicTabLayout 暴露 -------------------- */

    internal fun attachContainer(container: LinearLayout) {
        tabContainer = container
        invalidate()
    }

    internal fun set(position: Int, style: BadgeStyle?) {
        if (style == null) {
            cancelAnim(position)
            badges.remove(position)
            animProgress.remove(position)
            lastDrawnBounds.remove(position)
        } else {
            val old = badges.get(position)
            // 仅当 0 → 有 / 不同类型切换 时入场动画，
            // 数字递增 (Count.5 → Count.6) 等纯数据变化不抖
            val shouldAnim = old == null || old::class != style::class
            badges.put(position, style)
            if (shouldAnim && enterAnimDuration > 0L) {
                startEnterAnim(position)
            } else {
                // 关键：若上一段动画仍在跑，先取消，避免它继续把 progress 拉回中间值
                cancelAnim(position)
                animProgress.put(position, 1f)
            }
        }
        invalidate()
    }

    internal fun clearAll() {
        for (i in 0 until animators.size) animators.valueAt(i).cancel()
        animators.clear()
        animProgress.clear()
        lastDrawnBounds.clear()
        badges.clear()
        invalidate()
    }

    internal val hasAny: Boolean get() = badges.size > 0

    /* -------------------- 动画 -------------------- */

    private fun startEnterAnim(position: Int) {
        cancelAnim(position)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = enterAnimDuration
            interpolator = OvershootInterpolator(2f)
            addUpdateListener {
                animProgress.put(position, it.animatedValue as Float)
                invalidate()
            }
        }
        animators.put(position, anim)
        anim.start()
    }

    private fun cancelAnim(position: Int) {
        animators.get(position)?.cancel()
        animators.remove(position)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (i in 0 until animators.size) animators.valueAt(i).cancel()
        animators.clear()
    }

    /* -------------------- 绘制 -------------------- */

    override fun onDraw(canvas: Canvas) {
        val container = tabContainer ?: return
        if (badges.size == 0) return

        for (i in 0 until badges.size) {
            val pos = badges.keyAt(i)
            val tab = container.getChildAt(pos) ?: continue
            val style = badges.valueAt(i)
            val progress = animProgress.get(pos) ?: 1f

            when (style) {
                BadgeStyle.Dot -> drawDot(canvas, tab, pos, progress)
                is BadgeStyle.Count -> {
                    if (style.count <= 0) { lastDrawnBounds.remove(pos); continue }
                    drawTextBadge(canvas, tab, pos, style.display(), progress)
                }
                is BadgeStyle.Text -> {
                    val s = style.text.toString()
                    if (s.isEmpty()) { lastDrawnBounds.remove(pos); continue }
                    drawTextBadge(canvas, tab, pos, s, progress)
                }
            }
        }
    }

    private fun drawDot(canvas: Canvas, tab: View, position: Int, progress: Float) {
        val (anchorRight, anchorTop) = tabAnchor(tab)
        val r = dotSizePx / 2f
        val cx = anchorRight + offsetXPx - r
        val cy = anchorTop + offsetYPx + r
        // 复用 lastDrawnBounds 里已有的 RectF，避免每帧 new
        boundsOf(position).set(cx - r, cy - r, cx + r, cy + r)

        val scale = enterAnimMinScale + (1f - enterAnimMinScale) * progress
        val alpha = progress.coerceIn(0f, 1f)

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        withAlpha(alpha) {
            canvas.drawCircle(cx, cy, r, bgPaint)
            if (strokeWidthPx > 0f) canvas.drawCircle(cx, cy, r, strokePaint)
        }
        canvas.restore()
    }

    private fun drawTextBadge(
        canvas: Canvas,
        tab: View,
        position: Int,
        text: String,
        progress: Float,
    ) {
        val textW = textPaint.measureText(text)
        val w = (textW + paddingHPx * 2).coerceAtLeast(minHeightPx)
        val h = minHeightPx
        val (anchorRight, anchorTop) = tabAnchor(tab)
        val right = anchorRight + offsetXPx
        val top = anchorTop + offsetYPx
        val bounds = boundsOf(position).apply { set(right - w, top, right, top + h) }

        val scale = enterAnimMinScale + (1f - enterAnimMinScale) * progress
        val alpha = progress.coerceIn(0f, 1f)
        val cx = bounds.centerX()
        val cy = bounds.centerY()

        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        withAlpha(alpha) {
            canvas.drawRoundRect(bounds, h / 2f, h / 2f, bgPaint)
            if (strokeWidthPx > 0f) canvas.drawRoundRect(bounds, h / 2f, h / 2f, strokePaint)
            val baseY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(text, cx, baseY, textPaint)
        }
        canvas.restore()
    }

    /** 取或创建该 position 的可复用 bounds RectF */
    private fun boundsOf(position: Int): RectF =
        lastDrawnBounds.get(position) ?: RectF().also { lastDrawnBounds.put(position, it) }

    /** 临时把三支 Paint 的 alpha 乘上一个系数再绘制，结束后恢复 */
    private inline fun withAlpha(alpha: Float, block: () -> Unit) {
        if (alpha >= 1f) { block(); return }
        val oa = bgPaint.alpha; val ob = textPaint.alpha; val oc = strokePaint.alpha
        bgPaint.alpha = (oa * alpha).toInt()
        textPaint.alpha = (ob * alpha).toInt()
        strokePaint.alpha = (oc * alpha).toInt()
        block()
        bgPaint.alpha = oa; textPaint.alpha = ob; strokePaint.alpha = oc
    }

    /* -------------------- 点击 -------------------- */

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (onBadgeClick == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downHit = hitTest(event.x, event.y)
                return downHit >= 0
            }
            MotionEvent.ACTION_UP -> {
                val up = hitTest(event.x, event.y)
                val hit = downHit
                downHit = -1
                if (up >= 0 && up == hit) {
                    val style = badges.get(up) ?: return false
                    onBadgeClick?.invoke(up, style)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> downHit = -1
        }
        return false
    }

    private fun hitTest(x: Float, y: Float): Int {
        val inset = -clickHitInsetPx
        for (i in 0 until lastDrawnBounds.size) {
            val pos = lastDrawnBounds.keyAt(i)
            val rect = lastDrawnBounds.valueAt(i)
            // 复用 tempHitRect，避免每次 DOWN/UP 都 new RectF
            tempHitRect.set(rect)
            tempHitRect.inset(inset, inset)   // inset 是负数 → 反向扩张
            if (tempHitRect.contains(x, y)) return pos
        }
        return -1
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /**
     * 角标锚点 = tab 视觉上的右上角。考虑 tab.scaleX/Y（[ZoomAnimator] 会改 tab 整体 scale），
     * 让角标跟随缩放对齐到 tab 视觉右上，而不是脱离 Tab 文字游离在外。
     * 不考虑 [ScaleColorAnimator]——那个只缩放内部 TextView，tab.scaleX 始终为 1。
     */
    private fun tabAnchor(tab: View): Pair<Float, Float> {
        val sx = tab.scaleX
        val sy = tab.scaleY
        if (sx == 1f && sy == 1f) {
            return tab.right.toFloat() to tab.top.toFloat()
        }
        val cx = (tab.left + tab.right) / 2f
        val cy = (tab.top + tab.bottom) / 2f
        val visualRight = cx + (tab.right - cx) * sx
        val visualTop = cy + (tab.top - cy) * sy
        return visualRight to visualTop
    }
}
