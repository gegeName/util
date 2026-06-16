package com.chat.mylibrary.guide

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.core.view.doOnAttach
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * 引导图遮罩 View。全屏覆盖在 Activity decorView 上，支持：
 *  - 多步骤切换（链式 Builder）
 *  - 每步多按钮高亮挖洞（[HighlightShape]）
 *  - 内容图片 / SVGA / 自定义 View
 *  - 入场/出场动画
 *  - 相对 anchor 定位或全屏居中
 *  - oncePerKey 只显示一次 + 可外部清除缓存
 *
 * 入口：
 * ```
 * GuideView.with(activity)
 *     .step { highlight(btn1).image(R.drawable.guide_1).position(Position.Below) }
 *     .step { highlight(btn2).svga("guide2.svga") }
 *     .oncePerKey("home_first")
 *     .show()
 * ```
 */
class GuideView private constructor(context: Context) : FrameLayout(context) {

    private val steps = mutableListOf<GuideStep>()
    private var currentStepIndex = -1

    @ColorInt
    private var maskColor: Int = 0xCC000000.toInt()
    private var dismissOnTouchOutside = false
    private var onceKey: String? = null
    private var onDismissAction: (() -> Unit)? = null

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val locBuf = IntArray(2)
    private val rootLoc = IntArray(2)
    private val highlightRects = mutableListOf<HighlightRender>()

    private var currentContent: View? = null
    private var currentSvgaView: View? = null
    private var runningAnimator: Animator? = null

    private var passThroughActive = false
    private val autoNextRunnable = Runnable { advance() }
    private var dismissing = false

    init {
        setWillNotDraw(false)
    }

    private val controller = object : GuideController {
        override val currentIndex: Int get() = currentStepIndex
        override val totalCount: Int get() = steps.size
        override fun next() = advance()
        override fun prev() {
            if (currentStepIndex > 0) {
                currentStepIndex -= 2
                advance()
            }
        }
        override fun dismiss() = dismissInternal()
    }

    // -------- lifecycle / flow --------

    private fun start() {
        doOnAttach {
            findViewTreeLifecycleOwner()?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) = detach()
            })
            post { advance() }
        }
    }

    private fun advance() {
        if (dismissing) return
        removeCallbacks(autoNextRunnable)
        currentStepIndex++
        if (currentStepIndex >= steps.size) {
            dismissInternal()
            return
        }
        showStep(steps[currentStepIndex])
    }

    private fun showStep(step: GuideStep) {
        getLocationInWindow(rootLoc)
        highlightRects.clear()
        for (t in step.highlights) {
            t.view.getLocationInWindow(locBuf)
            val r = Rect(
                locBuf[0] - rootLoc[0],
                locBuf[1] - rootLoc[1],
                locBuf[0] - rootLoc[0] + t.view.width,
                locBuf[1] - rootLoc[1] + t.view.height,
            )
            highlightRects += HighlightRender(r, t.shape, t.clickThrough, t.onClick)
        }

        removeCurrentContent()
        val content = buildContentView(step) ?: run {
            invalidate()
            return
        }
        currentContent = content
        addView(content, FrameLayout.LayoutParams(step.contentWidth, step.contentHeight))
        content.setOnClickListener {
            val cb = step.contentClickListener
            if (cb != null) cb(controller) else advance()
        }

        content.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                content.viewTreeObserver.removeOnPreDrawListener(this)
                positionContent(content, step)
                runEnterAnim(content, step.anim)
                if (step.autoNextDelayMs > 0) {
                    postDelayed(autoNextRunnable, step.autoNextDelayMs)
                }
                return true
            }
        })

        invalidate()
    }

    private fun buildContentView(step: GuideStep): View? = when (val c = step.content) {
        Content.Empty -> null
        is Content.Image -> ImageView(context).apply {
            setImageResource(c.resId)
            adjustViewBounds = true
        }
        is Content.Svga -> {
            val provider = svgaProvider
                ?: error("GuideView: svga() used but no SvgaProvider set. Call GuideView.setSvgaProvider() first.")
            val v = provider.create(context, c.source, c.loops)
            currentSvgaView = v
            v
        }
        is Content.Custom -> c.view.also { (it.parent as? ViewGroup)?.removeView(it) }
    }

    private fun positionContent(content: View, step: GuideStep) {
        val w = content.measuredWidth
        val h = content.measuredHeight
        val pw = width
        val ph = height
        val lp = content.layoutParams as FrameLayout.LayoutParams

        val anchor = step.anchorView ?: step.highlights.firstOrNull()?.view
        if (anchor == null || step.position is Position.Center) {
            lp.gravity = Gravity.NO_GRAVITY
            lp.leftMargin = clampAxis((pw - w) / 2 + step.offsetX, w, pw)
            lp.topMargin = clampAxis((ph - h) / 2 + step.offsetY, h, ph)
            content.layoutParams = lp
            return
        }

        anchor.getLocationInWindow(locBuf)
        val ax = locBuf[0] - rootLoc[0]
        val ay = locBuf[1] - rootLoc[1]
        val aw = anchor.width
        val ah = anchor.height

        var x: Int
        var y: Int
        when (val p = step.position) {
            Position.Above -> {
                x = ax + (aw - w) / 2
                y = ay - h
                if (y < 0 && ay + ah + h <= ph) y = ay + ah
            }
            Position.Below -> {
                x = ax + (aw - w) / 2
                y = ay + ah
                if (y + h > ph && ay - h >= 0) y = ay - h
            }
            Position.LeftOf -> {
                x = ax - w
                y = ay + (ah - h) / 2
                if (x < 0 && ax + aw + w <= pw) x = ax + aw
            }
            Position.RightOf -> {
                x = ax + aw
                y = ay + (ah - h) / 2
                if (x + w > pw && ax - w >= 0) x = ax - w
            }
            is Position.Custom -> {
                val pt = p.resolver(Rect(ax, ay, ax + aw, ay + ah), w, h)
                x = pt.x; y = pt.y
            }
            Position.Center -> { x = 0; y = 0 }
        }
        x += step.offsetX
        y += step.offsetY
        lp.gravity = Gravity.NO_GRAVITY
        lp.leftMargin = clampAxis(x, w, pw)
        lp.topMargin = clampAxis(y, h, ph)
        content.layoutParams = lp
    }

    /** 把 value 收敛到 [0, parent - size]；当 size > parent 时返回 0（左/上对齐保证可见左上角） */
    private fun clampAxis(value: Int, size: Int, parent: Int): Int =
        if (size >= parent) 0 else value.coerceIn(0, parent - size)

    private fun runEnterAnim(content: View, anim: GuideAnim) {
        runningAnimator?.cancel()
        runningAnimator = anim.buildEnter(content)?.also { it.start() }
    }

    private fun removeCurrentContent() {
        runningAnimator?.cancel()
        runningAnimator = null
        currentContent?.let { removeView(it) }
        currentSvgaView?.let { svgaProvider?.release(it) }
        currentContent = null
        currentSvgaView = null
    }

    private fun dismissInternal() {
        if (dismissing) return
        dismissing = true
        removeCallbacks(autoNextRunnable)
        val content = currentContent
        val step = steps.getOrNull(currentStepIndex)
        if (content != null && step != null) {
            val exit = step.anim.buildExit(content)
            if (exit != null) {
                runningAnimator?.cancel()
                runningAnimator = exit
                exit.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = detach()
                })
                exit.start()
                return
            }
        }
        detach()
    }

    private fun detach() {
        removeCallbacks(autoNextRunnable)
        removeCurrentContent()
        (parent as? ViewGroup)?.removeView(this)
        onceKey?.let { markShown(context, it) }
        onDismissAction?.invoke()
    }

    // -------- drawing --------

    override fun dispatchDraw(canvas: Canvas) {
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawColor(maskColor)
        for (h in highlightRects) drawHole(canvas, h)
        canvas.restoreToCount(sc)
        super.dispatchDraw(canvas)
    }

    private fun drawHole(canvas: Canvas, h: HighlightRender) {
        val r = h.rect
        when (val s = h.shape) {
            HighlightShape.None -> Unit
            is HighlightShape.Rect -> {
                val p = s.paddingPx
                canvas.drawRect(
                    (r.left - p).toFloat(), (r.top - p).toFloat(),
                    (r.right + p).toFloat(), (r.bottom + p).toFloat(),
                    maskPaint,
                )
            }
            is HighlightShape.RoundRect -> {
                val p = s.paddingPx
                canvas.drawRoundRect(
                    (r.left - p).toFloat(), (r.top - p).toFloat(),
                    (r.right + p).toFloat(), (r.bottom + p).toFloat(),
                    s.radiusPx, s.radiusPx, maskPaint,
                )
            }
            is HighlightShape.Circle -> {
                val p = s.paddingPx
                val cx = (r.left + r.right) / 2f
                val cy = (r.top + r.bottom) / 2f
                val radius = (maxOf(r.width(), r.height()) / 2f) + p
                canvas.drawCircle(cx, cy, radius, maskPaint)
            }
            is HighlightShape.Oval -> {
                val p = s.paddingPx
                val rf = RectF(
                    (r.left - p).toFloat(), (r.top - p).toFloat(),
                    (r.right + p).toFloat(), (r.bottom + p).toFloat(),
                )
                canvas.drawOval(rf, maskPaint)
            }
        }
    }

    // -------- touch --------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val x = ev.x.toInt(); val y = ev.y.toInt()
            passThroughActive = highlightRects.any { it.clickThrough && it.rect.contains(x, y) }
        }
        if (passThroughActive) return false
        return super.dispatchTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val x = event.x.toInt(); val y = event.y.toInt()
            val hit = highlightRects.firstOrNull { it.rect.contains(x, y) }
            if (hit?.onClick != null) {
                hit.onClick.invoke()
                return true
            }
            if (dismissOnTouchOutside) dismissInternal() else advance()
            return true
        }
        return true
    }

    // -------- Builder & public API --------

    class Builder internal constructor(private val activity: Activity) {
        private val view = GuideView(activity)

        fun step(block: GuideStep.() -> Unit): Builder = apply {
            view.steps += GuideStep().apply(block)
        }

        fun maskColor(@ColorInt color: Int): Builder = apply { view.maskColor = color }

        fun dismissOnTouchOutside(enable: Boolean): Builder =
            apply { view.dismissOnTouchOutside = enable }

        /** 同一 key 在 SP 中只展示一次。可通过 [GuideView.clearKey] 重置 */
        fun oncePerKey(key: String): Builder = apply { view.onceKey = key }

        fun onDismiss(block: () -> Unit): Builder = apply { view.onDismissAction = block }

        /** 真正弹出引导。若 [oncePerKey] 已展示过或 step 为空，返回 null */
        fun show(): GuideController? {
            val key = view.onceKey
            if (key != null && hasShown(activity, key)) return null
            if (view.steps.isEmpty()) return null

            val decor = activity.window.decorView as ViewGroup
            decor.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            view.start()
            return view.controller
        }
    }

    internal data class HighlightRender(
        val rect: Rect,
        val shape: HighlightShape,
        val clickThrough: Boolean,
        val onClick: (() -> Unit)?,
    )

    companion object {
        private const val PREFS = "guide_view_prefs"

        @Volatile
        private var svgaProvider: SvgaProvider? = null

        /** 全局注入 SVGA provider；未注入时调用 svga() 会抛错 */
        @JvmStatic
        fun setSvgaProvider(provider: SvgaProvider?) {
            svgaProvider = provider
        }

        @JvmStatic
        fun with(activity: Activity): Builder = Builder(activity)

        /** 该 key 对应的引导是否已展示过 */
        @JvmStatic
        fun hasShown(context: Context, key: String): Boolean =
            prefs(context).getBoolean(key, false)

        /** 清除单个 key，让 oncePerKey 的引导可再次展示 */
        @JvmStatic
        fun clearKey(context: Context, key: String) {
            prefs(context).edit().remove(key).apply()
        }

        /** 清除全部引导 key */
        @JvmStatic
        fun clearAllKeys(context: Context) {
            prefs(context).edit().clear().apply()
        }

        private fun markShown(context: Context, key: String) {
            prefs(context).edit().putBoolean(key, true).apply()
        }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}

/** 引导运行时控制器，业务侧在 onClick 等回调中可拿到 */
interface GuideController {
    val currentIndex: Int
    val totalCount: Int
    fun next()
    fun prev()
    fun dismiss()
}
