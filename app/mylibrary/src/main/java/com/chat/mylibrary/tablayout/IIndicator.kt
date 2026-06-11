package com.chat.mylibrary.tablayout

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.abs

/**
 * 指示器抽象。
 * - attach 在 MagicTabLayout 初始化时调用，把自己塞到 host 里
 * - update 由 host 在 onPageScrolled / onPageSelected 时驱动
 */
interface IIndicator {
    fun attach(host: FrameLayout, tabContainer: LinearLayout)
    fun update(position: Int, offset: Float)
}

/* ---------------- 内部工具 ---------------- */

internal fun View.titleViewOrSelf(): TextView? = when (this) {
    is TextView -> this
    is LinearLayout -> (0 until childCount).asSequence()
        .map { getChildAt(it) }
        .filterIsInstance<TextView>()
        .firstOrNull()
    else -> null
}

/**
 * 共享测量笔：UI 线程单实例。
 * 用它复制 tv.paint 的字号/字体后再 measureText，避免直接拿 tv.paint 测量 —— Animator
 * 可能在每帧切 isFakeBoldText / 改字号，把指示器宽度抖出来。这里强制 fakeBold = false，
 * 让 indicator 用稳定基线测量。
 */
private val measureScratch = android.graphics.Paint()

internal fun textBounds(tab: View): Pair<Float, Float>? {
    val tv = tab.titleViewOrSelf() ?: return null
    measureScratch.set(tv.paint)
    measureScratch.isFakeBoldText = false
    // 用未缩放的 textWidth：跟随 tv.scaleX 看起来更"贴合"文字，但 applyState 的脏检测会让
    // tv.scaleX 在某些帧被跳过更新，indicator 跟着就出现离散跳变 —— 内容长↔短切换时尤其明显。
    // 这里保持稳定基线测量，scale 带来的视觉漂移交给业务在 Animator 端调小 maxScale 处理。
    val textWidth = measureScratch.measureText(tv.text.toString())
    // 仅当 tv 是 tab 的子 view（如 IconText）时，tv.left 才表示它在 tab 内的偏移；
    // tv === tab（纯 TextTabProvider）时 tv.left 已等于 tab.left，再加会双倍偏移
    val tvOffsetWithinTab = if (tv === tab) 0 else tv.left
    val tvCenter = tab.left + tvOffsetWithinTab + tv.width / 2f
    return (tvCenter - textWidth / 2f) to (tvCenter + textWidth / 2f)
}

/* ---------------- 基类 ---------------- */

/**
 * 指示器宽度模式：
 * - [Tab]   跟随当前 tab 整体宽度（可配 horizontalPadding 内缩）
 * - [Text]  跟随当前 tab 内文字宽度
 * - [Fixed] 固定 px 宽度，居中对齐 tab 中线
 */
sealed class IndicatorWidth {
    object Tab : IndicatorWidth()
    object Text : IndicatorWidth()
    data class Fixed(val widthPx: Int) : IndicatorWidth()
}

abstract class BaseIndicator(
    context: Context,
    protected val color: Int,
    protected val heightPx: Int,
    protected val widthMode: IndicatorWidth = IndicatorWidth.Tab,
    protected val horizontalPaddingPx: Int = 0,
    protected val gravityFlag: Int = Gravity.BOTTOM,
) : View(context), IIndicator {

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BaseIndicator.color }
    protected lateinit var tabContainer: LinearLayout

    protected var indicatorLeft = 0f
    protected var indicatorRight = 0f

    /** 复用的 RectF，避免每帧 onDraw 都 new 触发 GC 卡顿 */
    protected val rect: RectF = RectF()

    // 减振平滑：VP2 在 page 边界发送 (N, 0.99) → (N+1, 0.0) 时跳过等价帧 (N, 1.0)，
    // 直接用 VP2 输入算 indicator 会有几像素跳变。这里用指数 lerp 把 indicator 向 VP2 目标平滑收敛，
    // 同时自驱动 invalidate 让 VP2 停发后 indicator 仍能继续到位。
    private var targetLeft = 0f
    private var targetRight = 0f
    private var indicatorInitialized = false

    private val smoothFactor = 0.5f
    private val convergeEps = 0.5f

    private val convergeRunnable = object : Runnable {
        override fun run() {
            val dL = targetLeft - indicatorLeft
            val dR = targetRight - indicatorRight
            if (abs(dL) < convergeEps && abs(dR) < convergeEps) {
                indicatorLeft = targetLeft
                indicatorRight = targetRight
                invalidate()
                return
            }
            indicatorLeft += dL * smoothFactor
            indicatorRight += dR * smoothFactor
            invalidate()
            postOnAnimation(this)
        }
    }

    override fun attach(host: FrameLayout, tabContainer: LinearLayout) {
        this.tabContainer = tabContainer
        host.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, heightPx, gravityFlag))
    }

    override fun update(position: Int, offset: Float) {
        val cur = tabContainer.getChildAt(position) ?: return
        val next = tabContainer.getChildAt(position + 1)
        val (cL, cR) = leftRightOf(cur)
        if (next == null) {
            targetLeft = cL; targetRight = cR
        } else {
            val (nL, nR) = leftRightOf(next)
            targetLeft = cL + (nL - cL) * offset
            targetRight = cR + (nR - cR) * offset
        }

        // 首次：直接到位，不做平滑
        if (!indicatorInitialized) {
            indicatorInitialized = true
            indicatorLeft = targetLeft
            indicatorRight = targetRight
            invalidate()
            return
        }
        // 跨多 tab 的大幅跳变（如点击远端）：也直接到位，避免长时间平滑
        if (abs(targetLeft - indicatorLeft) > width * 0.5f || abs(targetRight - indicatorRight) > width * 0.5f) {
            indicatorLeft = targetLeft
            indicatorRight = targetRight
            invalidate()
            return
        }
        // 启动减振收敛：每帧向 target lerp，直到误差 < convergeEps
        removeCallbacks(convergeRunnable)
        postOnAnimation(convergeRunnable)
    }

    protected open fun leftRightOf(tab: View): Pair<Float, Float> = when (val m = widthMode) {
        is IndicatorWidth.Fixed -> {
            val center = tab.left + tab.width / 2f
            (center - m.widthPx / 2f) to (center + m.widthPx / 2f)
        }
        IndicatorWidth.Text -> textBounds(tab) ?: tabBounds(tab)
        IndicatorWidth.Tab -> tabBounds(tab)
    }

    private fun tabBounds(tab: View): Pair<Float, Float> =
        (tab.left + horizontalPaddingPx).toFloat() to (tab.right - horizontalPaddingPx).toFloat()
}

/* ---------------- 1. 普通线条 ---------------- */

class LineIndicator(
    context: Context,
    color: Int = Color.parseColor("#F44DEF"),
    heightPx: Int = 6,
    widthMode: IndicatorWidth = IndicatorWidth.Tab,
    private val cornerRadiusPx: Float = 0f,
) : BaseIndicator(context, color, heightPx, widthMode) {
    override fun onDraw(canvas: Canvas) {
        rect.set(indicatorLeft, 0f, indicatorRight, heightPx.toFloat())
        if (cornerRadiusPx > 0f) canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
        else canvas.drawRect(rect, paint)
    }
}

/* ---------------- 2. 圆角胶囊（背景式） ---------------- */

class RoundCapsuleIndicator(
    context: Context,
    color: Int = Color.parseColor("#1AF44DEF"),
    heightPx: Int = 80,
    horizontalPaddingPx: Int = 16,
    widthMode: IndicatorWidth = IndicatorWidth.Tab,
) : BaseIndicator(
    context, color, heightPx,
    widthMode = widthMode,
    horizontalPaddingPx = horizontalPaddingPx,
    gravityFlag = Gravity.CENTER_VERTICAL,
) {
    override fun onDraw(canvas: Canvas) {
        rect.set(indicatorLeft, 0f, indicatorRight, heightPx.toFloat())
        val r = heightPx / 2f
        canvas.drawRoundRect(rect, r, r, paint)
    }
}

/* ---------------- 3. 三角形 ---------------- */

class TriangleIndicator(
    context: Context,
    color: Int = Color.parseColor("#F44DEF"),
    heightPx: Int = 18,
    private val widthPx: Int = 24,
    private val pointUp: Boolean = true,
    widthMode: IndicatorWidth = IndicatorWidth.Tab,
) : BaseIndicator(context, color, heightPx, widthMode) {
    private val path = Path()
    override fun onDraw(canvas: Canvas) {
        val center = (indicatorLeft + indicatorRight) / 2f
        val half = widthPx / 2f
        path.reset()
        if (pointUp) {
            path.moveTo(center, 0f)
            path.lineTo(center - half, heightPx.toFloat())
            path.lineTo(center + half, heightPx.toFloat())
        } else {
            path.moveTo(center - half, 0f)
            path.lineTo(center + half, 0f)
            path.lineTo(center, heightPx.toFloat())
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}

/* ---------------- 4. 弹球（贝塞尔拉伸） ---------------- */

/**
 * 滑动时左右端走不同插值曲线 → 视觉上「先拉长再回弹」。
 * 左端用减速、右端用加速；反向滑动时互换。
 */
class ElasticIndicator(
    context: Context,
    color: Int = Color.parseColor("#F44DEF"),
    heightPx: Int = 8,
    widthMode: IndicatorWidth = IndicatorWidth.Text,
    private val cornerRadiusPx: Float = 8f,
) : BaseIndicator(context, color, heightPx, widthMode) {

    override fun update(position: Int, offset: Float) {
        val cur = tabContainer.getChildAt(position) ?: return
        val next = tabContainer.getChildAt(position + 1)
        val (curL, curR) = leftRightOf(cur)
        if (next == null) {
            indicatorLeft = curL; indicatorRight = curR
        } else {
            val (nextL, nextR) = leftRightOf(next)
            // 左端：加速曲线（t^2）；右端：减速曲线（1-(1-t)^2）—— Elastic 的特征插值，不走基类减振
            indicatorLeft = curL + (nextL - curL) * (offset * offset)
            indicatorRight = curR + (nextR - curR) * (1f - (1f - offset) * (1f - offset))
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(indicatorLeft, 0f, indicatorRight, heightPx.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
    }
}

/* ---------------- 5. 渐变色线条 ---------------- */

class GradientLineIndicator(
    context: Context,
    private val startColor: Int = Color.parseColor("#8B5FF1"),
    private val endColor: Int = Color.parseColor("#F44DEF"),
    heightPx: Int = 8,
    widthMode: IndicatorWidth = IndicatorWidth.Text,
    private val cornerRadiusPx: Float = 4f,
) : BaseIndicator(context, startColor, heightPx, widthMode) {

    private var lastShaderLeft = Float.NaN
    private var lastShaderRight = Float.NaN

    override fun onDraw(canvas: Canvas) {
        if (indicatorRight <= indicatorLeft) return
        // 只在坐标变化时重建 LinearGradient（shader 持有 native 句柄，重建有开销）
        if (indicatorLeft != lastShaderLeft || indicatorRight != lastShaderRight) {
            paint.shader = LinearGradient(
                indicatorLeft, 0f, indicatorRight, 0f,
                startColor, endColor, Shader.TileMode.CLAMP
            )
            lastShaderLeft = indicatorLeft
            lastShaderRight = indicatorRight
        }
        rect.set(indicatorLeft, 0f, indicatorRight, heightPx.toFloat())
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
    }
}

/* ---------------- 6. 资源图片指示器 ---------------- */

/**
 * 用 [Drawable] 作为指示器（PNG / VectorDrawable / 9-patch 均可）。
 *
 * 默认 [widthMode] = `Fixed(drawable.intrinsicWidth)`：指示器尺寸 = 图片本身大小，居中跟随 tab；
 * 想让图片拉伸到 tab / 文字宽度，传 `IndicatorWidth.Tab` / `IndicatorWidth.Text`。
 *
 * @param tintColor 不为 [Color.TRANSPARENT] 时给图片着色（mutate 后修改，不影响其它使用同一资源的地方）
 */
class DrawableIndicator(
    context: Context,
    private val drawable: Drawable,
    heightPx: Int,
    widthMode: IndicatorWidth? = null,
    @ColorInt tintColor: Int = Color.TRANSPARENT,
    gravityFlag: Int = Gravity.BOTTOM,
) : BaseIndicator(
    context,
    color = Color.TRANSPARENT,
    heightPx = heightPx,
    widthMode = widthMode
        ?: IndicatorWidth.Fixed(drawable.intrinsicWidth.takeIf { it > 0 } ?: heightPx),
    gravityFlag = gravityFlag,
) {

    /** 从资源 id 创建：内部加载并自动 mutate */
    constructor(
        context: Context,
        @DrawableRes drawableRes: Int,
        heightPx: Int,
        widthMode: IndicatorWidth? = null,
        @ColorInt tintColor: Int = Color.TRANSPARENT,
        gravityFlag: Int = Gravity.BOTTOM,
    ) : this(
        context,
        (AppCompatResources.getDrawable(context, drawableRes)
            ?: error("DrawableIndicator: 资源未找到 $drawableRes")).mutate(),
        heightPx,
        widthMode,
        tintColor,
        gravityFlag,
    )

    init {
        if (tintColor != Color.TRANSPARENT) {
            drawable.mutate().setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (indicatorRight <= indicatorLeft) return
        drawable.setBounds(
            indicatorLeft.toInt(),
            0,
            indicatorRight.toInt(),
            heightPx,
        )
        drawable.draw(canvas)
    }
}

/* ---------------- 7. 无指示器（纯靠 Animator 体现选中） ---------------- */

class NoneIndicator(context: Context) : BaseIndicator(context, Color.TRANSPARENT, 0) {
    override fun attach(host: FrameLayout, tabContainer: LinearLayout) {}
    override fun update(position: Int, offset: Float) {}
}
