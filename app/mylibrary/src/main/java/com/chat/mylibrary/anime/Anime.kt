package com.chat.mylibrary.anime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.view.View
import androidx.core.view.doOnNextLayout

/**
 * 链式 View 动画封装。
 *
 * 用法：
 * ```
 * Anime.on(view)
 *     .fadeIn().duration(300)
 *     .after().translateY(0f, from = 50f).duration(400)
 *     .with().pulse()
 *     .onUpdate { f -> /* 0f..1f */ }
 *     .start()
 * ```
 */
class Anime private constructor(private val view: View) {

    private data class Step(
        val builders: MutableList<(View) -> PropertyValuesHolder> = mutableListOf(),
        var duration: Long = 300,
        var delay: Long = 0,
        var interpolator: TimeInterpolator? = null,
        var repeatCount: Int = 0,
        var repeatMode: Int = ValueAnimator.RESTART,
        var parallel: Boolean = false,
    )

    private val steps = mutableListOf<Step>()
    private var cur = Step()

    private var onStart: (() -> Unit)? = null
    private var onEnd: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var onUpdate: ((Float) -> Unit)? = null

    private var running: AnimatorSet? = null
    private var detachListener: View.OnAttachStateChangeListener? = null
    private var built = false
    private var pendingStart = false

    companion object {
        /** 创建作用于 [view] 的 Anime 构建器；一个实例对应一条动画链。 */
        @JvmStatic
        fun on(view: View) = Anime(view)
    }

    // —— 属性（to 必填，from 可选；不传 from 则从当前值过渡）——

    /** translationX 过渡到 [to]；指定 [from] 则从该值起算，否则用 view 当前值。 */
    fun translateX(to: Float, from: Float? = null) = prop("translationX", to, from)

    /** translationY 过渡到 [to]；指定 [from] 则从该值起算，否则用 view 当前值。 */
    fun translateY(to: Float, from: Float? = null) = prop("translationY", to, from)

    /** scaleX 过渡到 [to]；指定 [from] 则从该值起算，否则用 view 当前值。 */
    fun scaleX(to: Float, from: Float? = null) = prop("scaleX", to, from)

    /** scaleY 过渡到 [to]；指定 [from] 则从该值起算，否则用 view 当前值。 */
    fun scaleY(to: Float, from: Float? = null) = prop("scaleY", to, from)

    /** 同时设置 scaleX 与 scaleY 到 [to]，等价于 `scaleX(to, from).scaleY(to, from)`。 */
    fun scale(to: Float, from: Float? = null) = scaleX(to, from).scaleY(to, from)

    /** alpha 过渡到 [to]；指定 [from] 则从该值起算，否则用 view 当前值。 */
    fun alpha(to: Float, from: Float? = null) = prop("alpha", to, from)

    /** rotation 过渡到 [to] 度。 */
    fun rotation(to: Float, from: Float? = null) = prop("rotation", to, from)

    /** 绕 X 轴 3D 旋转到 [to] 度，可与 [rotationY] 组合做翻转效果。 */
    fun rotationX(to: Float, from: Float? = null) = prop("rotationX", to, from)

    /** 绕 Y 轴 3D 旋转到 [to] 度，可与 [rotationX] 组合做翻转效果。 */
    fun rotationY(to: Float, from: Float? = null) = prop("rotationY", to, from)

    private fun prop(name: String, to: Float, from: Float?) = apply {
        cur.builders.add { _ ->
            if (from != null) PropertyValuesHolder.ofFloat(name, from, to)
            else PropertyValuesHolder.ofFloat(name, to)
        }
    }

    // —— 预设构建 helper（internal：供同 module 的 AnimePresets 扩展使用）——
    internal fun keyframes(name: String, vararg values: Float) = apply {
        cur.builders.add { _ -> PropertyValuesHolder.ofFloat(name, *values) }
    }

    internal fun dynamic(name: String, compute: (View) -> FloatArray) = apply {
        cur.builders.add { v -> PropertyValuesHolder.ofFloat(name, *compute(v)) }
    }

    internal fun pivot(xc: (View) -> Float, yc: (View) -> Float) = apply {
        cur.builders.add { v -> xc(v).let { PropertyValuesHolder.ofFloat("pivotX", it, it) } }
        cur.builders.add { v -> yc(v).let { PropertyValuesHolder.ofFloat("pivotY", it, it) } }
    }

    // —— 时序 ——

    /** 设置当前段时长 ([ms])，默认 300ms。 */
    fun duration(ms: Long) = apply { cur.duration = ms }

    /** 设置当前段起始延迟 ([ms])，默认 0。 */
    fun delay(ms: Long) = apply { cur.delay = ms }

    /** 设置当前段缓动函数 [i]，可用 [Interpolators] 内置预设。 */
    fun interpolator(i: TimeInterpolator) = apply { cur.interpolator = i }

    /** 当前段额外重复 [count] 次（总播放次数 = count + 1）；传 -1 等价 [repeatForever]。 */
    fun repeat(count: Int) = apply { cur.repeatCount = count }

    /** 当前段无限循环；与 onUpdate 同用时进度回调不会触发（总时长为 -1）。 */
    fun repeatForever() = apply { cur.repeatCount = ValueAnimator.INFINITE }

    /** 当前段重复时往返播放（REVERSE 模式），与 [repeat] / [repeatForever] 配合做 yo-yo 效果。 */
    fun reverse() = apply { cur.repeatMode = ValueAnimator.REVERSE }

    /** 串行：本段在上一段播完之后再播（对应 `AnimatorSet.Builder.after`）。 */
    fun after() = apply {
        steps.add(cur)
        cur = Step(parallel = false)
    }

    /** 并行：与上一段同时播，但 duration / interpolator / delay 独立（对应 `AnimatorSet.Builder.with`）。 */
    fun with() = apply {
        steps.add(cur)
        cur = Step(parallel = true)
    }

    // —— 回调 ——

    /** 注册启动回调；在真实 AnimatorSet 启动时触发一次。 */
    fun onStart(cb: () -> Unit) = apply { onStart = cb }

    /** 注册自然结束回调；[cancel] 路径不触发。 */
    fun onEnd(cb: () -> Unit) = apply { onEnd = cb }

    /** 注册取消回调；用户主动 [cancel] 或 view detach 触发。 */
    fun onCancel(cb: () -> Unit) = apply { onCancel = cb }

    /** 注册进度回调；参数为整体 fraction (0f..1f)，无限循环段不会触发。 */
    fun onUpdate(cb: (Float) -> Unit) = apply { onUpdate = cb }

    // —— 控制 ——

    /** 取消正在运行（或 pending 等待 layout）的动画；未启动或已结束时是 no-op。 */
    fun cancel() {
        val wasPending = pendingStart
        pendingStart = false
        val r = running
        if (r != null) r.cancel()
        else if (wasPending) cleanup()
    }

    /** 是否处于运行中或 pending 等待 layout 阶段。 */
    fun isRunning(): Boolean = pendingStart || running?.isRunning == true

    /**
     * 启动动画。
     *
     * 若 view 尚未 layout，则挂 [View.doOnNextLayout] 等下一帧再启动；
     * 返回的 [AnimatorSet] 在 pending 路径为空 placeholder，仅作类型对齐，不建议对其调用 cancel。
     */
    fun start(): AnimatorSet {
        running?.let { return it }
        if (pendingStart) return AnimatorSet()
        if (view.width == 0 && view.height == 0 && !view.isLaidOut) {
            pendingStart = true
            attachLifecycleGuard()
            view.doOnNextLayout {
                if (pendingStart) {
                    pendingStart = false
                    start()
                }
            }
            return AnimatorSet()
        }
        if (!built) {
            steps.add(cur)
            built = true
        }

        val effective = steps.filter { it.builders.isNotEmpty() }

        if (effective.isEmpty()) {
            onStart?.invoke()
            onEnd?.invoke()
            return AnimatorSet()
        }

        val animators = effective.map { s ->
            val pvhs = s.builders.map { it(view) }.toTypedArray()
            ObjectAnimator.ofPropertyValuesHolder(view, *pvhs).apply {
                duration = s.duration
                startDelay = s.delay
                s.interpolator?.let { interpolator = it }
                repeatCount = s.repeatCount
                repeatMode = s.repeatMode
            }
        }

        val groups = mutableListOf<MutableList<Animator>>()
        effective.forEachIndexed { i, s ->
            if (i == 0 || !s.parallel) groups.add(mutableListOf(animators[i]))
            else groups.last().add(animators[i])
        }
        val blocks: List<Animator> = groups.map { g ->
            if (g.size == 1) g[0] else AnimatorSet().apply { playTogether(g) }
        }

        val playable = AnimatorSet().apply { playSequentially(blocks) }

        val total = playable.totalDuration
        val tick = onUpdate?.takeIf { total >= 0 }?.let { cb ->
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = total
                addUpdateListener { cb(it.animatedFraction) }
            }
        }
        val root = AnimatorSet().apply {
            if (tick != null) playTogether(playable, tick) else play(playable)
        }

        var canceled = false
        root.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(a: Animator) { onStart?.invoke() }
            override fun onAnimationCancel(a: Animator) {
                canceled = true
                onCancel?.invoke()
            }
            override fun onAnimationEnd(a: Animator) {
                if (!canceled) onEnd?.invoke()
                cleanup()
            }
        })

        running = root
        attachLifecycleGuard()
        root.start()
        return root
    }

    // —— 生命周期 ——
    private fun attachLifecycleGuard() {
        if (detachListener != null) return
        val l = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { cancel() }
        }
        detachListener = l
        view.addOnAttachStateChangeListener(l)
    }

    private fun cleanup() {
        detachListener?.let { view.removeOnAttachStateChangeListener(it) }
        detachListener = null
        running = null
    }
}
