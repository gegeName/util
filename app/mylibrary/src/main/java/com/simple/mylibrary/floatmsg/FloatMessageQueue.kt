package com.simple.mylibrary.floatmsg

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.ArrayDeque

/**
 * 通用浮屏消息队列。容器是空壳 ViewGroup,每条消息由 [onCreateView] 工厂自行 inflate
 * 一个 View,addView 进容器,执行 animateIn -> 停留 -> animateOut,结束后 removeView,
 * 再消费下一条。同一个容器可以承载多种消息类型(进场/系统通告/PK 等),
 * 每种类型自定义 layout,只共享队列状态机和动画策略。
 *
 * @param T 消息载荷类型
 * @param container 已经在父布局中定位好的空容器(如 FrameLayout)
 * @param onCreateView 工厂:根据 T 创建并绑定一个 View,每次入场都会被调用
 * @param animator 入/出场动画;不传则用 [SlideInLeftAnimator]
 * @param onMerge 队列满时合并策略;返回非 null 替换队尾,返回 null 或不传则丢弃新消息
 * @param holdDurationMs 完整入场后停留毫秒数,默认 1000ms
 * @param maxQueue 队列硬上限,默认 5
 * @param lifecycle 可选;传入则自动绑生命周期 ON_DESTROY 释放
 */
class FloatMessageQueue<T>(
    private val container: ViewGroup,
    private val onCreateView: (T) -> View,
    private val animator: FloatMessageAnimator = SlideInLeftAnimator(),
    private val onMerge: ((tail: T, incoming: T) -> T?)? = null,
    private val holdDurationMs: Long = 1000L,
    private val maxQueue: Int = 5,
    lifecycle: Lifecycle? = null
) {
    private companion object {
        const val TAG = "FloatMessageQueue"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<T>()
    private var isShowing = false
    private var released = false
    private var currentView: View? = null

    private val lifecycleObserver: DefaultLifecycleObserver? = lifecycle?.let { lc ->
        object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                release()
                owner.lifecycle.removeObserver(this)
            }
        }.also { lc.addObserver(it) }
    }

    fun enqueue(item: T) {
        runOnMain { enqueueInternal(item) }
    }

    fun release() {
        runOnMain { releaseInternal() }
    }

    private fun enqueueInternal(item: T) {
        if (released) return
        if (queue.size >= maxQueue) {
            val tail = queue.pollLast() ?: return
            val merged = onMerge?.invoke(tail, item)
            queue.addLast(merged ?: tail)
        } else {
            queue.addLast(item)
        }
        if (!isShowing) {
            isShowing = true
            consumeNext()
        }
    }

    private fun releaseInternal() {
        if (released) return
        released = true
        queue.clear()
        currentView?.let { v ->
            animator.cancel(v)
            detachView(v)
        }
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun consumeNext() {
        if (released) return
        while (true) {
            if (isHostDying()) {
                releaseInternal()
                return
            }
            val item = queue.pollFirst()
            if (item == null) {
                isShowing = false
                return
            }
            val view = try {
                onCreateView(item)
            } catch (e: Exception) {
                Log.e(TAG, "onCreateView failed, skip this item", e)
                continue
            }
            val added = try {
                container.addView(view)
                true
            } catch (e: Exception) {
                Log.e(TAG, "addView failed, skip this item", e)
                false
            }
            if (!added) continue
            currentView = view
            val started = try {
                animator.animateIn(view) {
                    if (released) return@animateIn
                    view.postDelayed({
                        if (released) return@postDelayed
                        animator.animateOut(view) {
                            if (released) return@animateOut
                            detachView(view)
                            consumeNext()
                        }
                    }, holdDurationMs)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "animateIn failed, skip this item", e)
                detachView(view)
                false
            }
            if (started) return
        }
    }

    private fun detachView(view: View) {
        if (view.parent != null) container.removeView(view)
        if (currentView === view) currentView = null
    }

    private fun isHostDying(): Boolean {
        val ctx = container.context
        return ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }
}
