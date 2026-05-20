package com.lhj.spanutil.span

import android.os.SystemClock
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.Choreographer
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * 字符级动画的"动作"接口。一帧绘制时由 driver 调用,
 * 业务方在这里改 [tp] 的属性(alpha / baselineShift / textSkewX / shader 等)。
 *
 * 实现方应做到无状态:同样的 (charIndex, progress) 必须产出同样的画面,
 * 否则倒放(REVERSE)和重置(RESTART)会出现错乱。
 *
 * @param tp           当前帧的 TextPaint,直接改属性即可
 * @param progress     该字符当前进度 [0, 1]
 * @param charIndex    字符在当前 segment 内的索引(0-based)
 * @param totalChars   segment 总字符数
 */
fun interface CharAnim {
    fun apply(tp: TextPaint, progress: Float, charIndex: Int, totalChars: Int)
}

/**
 * 一组开箱即用的字符动画。也可以做组合:
 * ```
 * val anim = CharAnim { tp, p, i, n ->
 *     CharAnims.Fade.apply(tp, p, i, n)
 *     CharAnims.Rise.apply(tp, p, i, n)
 * }
 * ```
 */
object CharAnims {

    /** 单纯透明度淡入。 */
    val Fade = CharAnim { tp, p, _, _ ->
        tp.alpha = (tp.alpha * p).toInt().coerceIn(0, 255)
    }

    /** 从下方升起 + 淡入。 */
    val Rise = CharAnim { tp, p, _, _ ->
        tp.alpha = (tp.alpha * p).toInt().coerceIn(0, 255)
        tp.baselineShift = ((1f - p) * tp.textSize * 0.4f).toInt()
    }

    /** 弹跳入场:先冲过头再回弹。 */
    val Bounce = CharAnim { tp, p, _, _ ->
        tp.alpha = (tp.alpha * p).toInt().coerceIn(0, 255)
        val overshoot = if (p < 0.7f) {
            -(p / 0.7f) * tp.textSize * 0.5f
        } else {
            -((1f - p) / 0.3f) * tp.textSize * 0.15f
        }
        tp.baselineShift = overshoot.toInt()
    }

    /** 横向偏移入场:整段从左侧滑入。需要 [CharAnim] 支持 charIndex 才能错位。 */
    val Slide = CharAnim { tp, p, i, n ->
        tp.alpha = (tp.alpha * p).toInt().coerceIn(0, 255)
        // baselineShift 只能改 y;水平方向 Span 无法直接 translate,
        // 此处用 textSkewX 模拟一点动感
        tp.textSkewX = (1f - p) * -0.3f
    }
}

/**
 * 动画循环控制。整体行为分两部分:
 *
 * - [count] 决定循环次数。1 = 跑一次就停;`Int.MAX_VALUE` = 无限。
 * - [direction] 决定循环之间的衔接方式:
 *   - RESTART: 每次都从 0 → 1。
 *   - REVERSE: 第 1 次 0→1,第 2 次 1→0,以此类推。
 *
 * - [pauseMs] 是两次循环之间的停顿(进度保持上一次结束的状态),用来做"打字机播完停一会儿再倒放"的效果。
 *   单次播放(count=1)时无意义。
 */
data class RepeatConfig(
    val count: Int = 1,
    val direction: Direction = Direction.RESTART,
    val pauseMs: Long = 0L,
) {
    enum class Direction { RESTART, REVERSE }

    companion object {
        val ONCE = RepeatConfig(count = 1)
        val INFINITE_RESTART = RepeatConfig(count = Int.MAX_VALUE, direction = Direction.RESTART)
        val INFINITE_REVERSE = RepeatConfig(count = Int.MAX_VALUE, direction = Direction.REVERSE)

        fun infiniteRestart(pauseMs: Long = 0L) =
            RepeatConfig(Int.MAX_VALUE, Direction.RESTART, pauseMs)

        fun infiniteReverse(pauseMs: Long = 0L) =
            RepeatConfig(Int.MAX_VALUE, Direction.REVERSE, pauseMs)
    }
}

/**
 * 字符级动画驱动器。每个被 [com.simple.mylibrary.utils.SpanBuilder.charAnimation]
 * 标记的字符绑定一个 [CharAnimSpan],共享同一 driver,driver 负责跑时钟、算进度。
 *
 * 工作流程:
 * 1. SpanBuilder.into 给 segment 内每个字符 setSpan 一个 [CharAnimSpan]。
 * 2. driver [start] 后用 Choreographer 每帧 `textView.invalidate()`,
 *    TextView 重绘时各 Span 调 [progressFor] 拉进度,然后由 [anim] 改 paint。
 * 3. 单轮跑完后根据 [repeat] 决定继续 / 反向 / 暂停 / 停止。
 * 4. RecyclerView 复用时由 SpanBuilder.attachAnimationLifecycle 调 [stop]。
 */
class CharAnimationDriver(
    val totalChars: Int,
    val perCharDelayMs: Long,
    val charDurationMs: Long,
    val anim: CharAnim,
    val repeat: RepeatConfig = RepeatConfig.ONCE,
) : Releasable {
    private var loopStartMs: Long = 0L
    private var loopIndex: Int = 0   // 当前是第几轮(从 0 开始)
    @Volatile private var stopped: Boolean = false

    /**
     * 一次性终态。stop / release 后置为 true,后续 [start] 全部忽略。
     *
     * 解决场景:RecyclerView 快速滚动时,SpanBuilder 用 `textView.post { driver.start() }`
     * 延迟启动 driver。若同一 holder 在 post 消费前又被 bind,旧 driver 已经被
     * `attachAnimationLifecycle` stop,但消息队列里仍有 stale runnable 持有它。
     * 没有 disposed 锁的话,stale runnable 会让旧 driver "复活"重新挂 Choreographer,
     * 持续空跑直到下一次 bind 才真停。
     */
    @Volatile private var disposed: Boolean = false
    private var tvRef: WeakReference<TextView>? = null

    /** 单轮跑完所需毫秒(不含 pause)。 */
    private val loopDurationMs: Long = totalChars * perCharDelayMs + charDurationMs

    /**
     * 暂停期(progress 已稳定在 0/1)期间不需要每帧 invalidate,
     * 直接 skipFrames 减少 RecyclerView 滚动时多 driver 叠加的主线程压力。
     */
    private val callback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val tv = tvRef?.get() ?: run { stop(); return }
            if (stopped) return
            advanceLoopIfNeeded()
            if (stopped) return
            // 进入 pause 阶段时进度恒定,无需 invalidate
            if (!isInPausePhase()) tv.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun isInPausePhase(): Boolean {
        if (repeat.pauseMs <= 0) return false
        val elapsed = SystemClock.uptimeMillis() - loopStartMs
        return elapsed in loopDurationMs..(loopDurationMs + repeat.pauseMs)
    }

    /**
     * 启动动画。已 [stop] / [release] 的 driver 不能再 start(disposed 是终态);
     * 只是 [pause] 的 driver 可以再 start 恢复(loopStartMs 重置,从头开始播)。
     */
    fun start(textView: TextView) {
        if (totalChars <= 0) return
        if (disposed) return
        loopStartMs = SystemClock.uptimeMillis()
        loopIndex = 0
        stopped = false
        tvRef = WeakReference(textView)
        Choreographer.getInstance().postFrameCallback(callback)
    }

    /**
     * 暂停 Choreographer 回调,但不 dispose,后续可 [start] 恢复。
     * RecyclerView 把 holder 滚出屏幕(detach)时调,attach 回来时再 restart。
     */
    fun pause() {
        stopped = true
        Choreographer.getInstance().removeFrameCallback(callback)
    }

    /**
     * 终态停止。pause 之外还把 disposed 置为 true,后续 [start] 会被忽略,
     * 防止 RecyclerView 快速复用时 stale `textView.post` 让旧 driver "复活"。
     */
    fun stop() {
        pause()
        disposed = true
        tvRef = null
    }

    override fun release() = stop()

    private fun advanceLoopIfNeeded() {
        val now = SystemClock.uptimeMillis()
        val elapsed = now - loopStartMs
        // 单轮 + pause 还没结束,继续等
        if (elapsed < loopDurationMs + repeat.pauseMs) return
        loopIndex++
        if (loopIndex >= repeat.count) {
            stopped = true
            return
        }
        loopStartMs = now
    }

    /**
     * 字符 charIndex 在当前帧的进度,已考虑循环方向。
     * 暂停期(pauseMs 内)进度保持在上一轮的结束态。
     */
    fun progressFor(charIndex: Int): Float {
        if (loopStartMs == 0L) return 0f
        val elapsed = SystemClock.uptimeMillis() - loopStartMs
        val charStart = charIndex * perCharDelayMs
        val rawProgress = when {
            elapsed <= charStart -> 0f
            elapsed >= charStart + charDurationMs -> 1f
            else -> (elapsed - charStart).toFloat() / charDurationMs
        }
        // 暂停期:rawProgress 必然已经到 1,继续按 1 输出
        // REVERSE 模式下偶数轮 0→1,奇数轮 1→0
        return when (repeat.direction) {
            RepeatConfig.Direction.RESTART -> rawProgress
            RepeatConfig.Direction.REVERSE ->
                if (loopIndex % 2 == 0) rawProgress else 1f - rawProgress
        }
    }
}

/**
 * 单字符动画 Span。每个字符独立一个实例,共享同一个 [driver]。
 * 实际改 paint 的逻辑全在 [CharAnimationDriver.anim] 里,这里只做胶水:
 * 把 charIndex 和当前 progress 喂给 anim。
 */
class CharAnimSpan(
    private val charIndex: Int,
    private val driver: CharAnimationDriver,
) : CharacterStyle(), UpdateAppearance {

    override fun updateDrawState(tp: TextPaint) {
        val progress = driver.progressFor(charIndex)
        driver.anim.apply(tp, progress, charIndex, driver.totalChars)
    }
}
