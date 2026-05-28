package com.chat.rv_page

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView

/**
 * 监听 [RecyclerView] 内各 section item 的可见比例,跨过阈值时派发 (visible=true / false) 回调。
 *
 * 可见比例 = [View.getGlobalVisibleRect] 截到的面积 / `view.width * view.height`。
 * 实例不暴露给业务,由 [RvPageBuilder.start] 装配时按需 register。
 */
internal class VisibilityTracker(private val recyclerView: RecyclerView) {

    private class Subscriber(
        val adapter: RecyclerView.Adapter<*>,
        val thresholdPercent: Int,
        val dispatch: (localPos: Int, visible: Boolean) -> Unit
    )

    private class TrackedState(var localPos: Int, var visible: Boolean)

    private val subscribers = mutableListOf<Subscriber>()
    private val state = mutableMapOf<RecyclerView.ViewHolder, TrackedState>()
    private val rvRect = Rect()
    private val childRect = Rect()
    private var installed = false

    fun register(
        adapter: RecyclerView.Adapter<*>,
        thresholdPercent: Int,
        dispatch: (localPos: Int, visible: Boolean) -> Unit
    ) {
        subscribers.add(Subscriber(adapter, thresholdPercent.coerceIn(1, 100), dispatch))
        installIfNeeded()
    }

    fun hasSubscribers(): Boolean = subscribers.isNotEmpty()

    private fun installIfNeeded() {
        if (installed) return
        installed = true
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.addOnChildAttachStateChangeListener(attachListener)
        recyclerView.post { check() }
    }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = check()
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) = check()
    }

    private val attachListener = object : RecyclerView.OnChildAttachStateChangeListener {
        override fun onChildViewAttachedToWindow(view: View) {
            recyclerView.post { check() }
        }

        override fun onChildViewDetachedFromWindow(view: View) {
            val vh = recyclerView.getChildViewHolder(view) ?: return
            val st = state.remove(vh) ?: return
            if (!st.visible) return
            val inner = vh.bindingAdapter ?: return
            val sub = subscribers.firstOrNull { it.adapter === inner } ?: return
            sub.dispatch(st.localPos, false)
        }
    }

    private fun check() {
        if (subscribers.isEmpty()) return
        if (!recyclerView.getGlobalVisibleRect(rvRect)) {
            flushAllInvisible()
            return
        }
        val seen = HashSet<RecyclerView.ViewHolder>(recyclerView.childCount)
        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i) ?: continue
            val vh = recyclerView.getChildViewHolder(child) ?: continue
            val inner = vh.bindingAdapter ?: continue
            val sub = subscribers.firstOrNull { it.adapter === inner } ?: continue
            val localPos = vh.bindingAdapterPosition
            if (localPos == RecyclerView.NO_POSITION) continue
            seen.add(vh)
            val percent = computePercent(child)
            val nowVisible = percent >= sub.thresholdPercent
            val prev = state[vh]
            when {
                prev == null -> {
                    state[vh] = TrackedState(localPos, nowVisible)
                    if (nowVisible) sub.dispatch(localPos, true)
                }

                prev.localPos != localPos -> {
                    if (prev.visible) sub.dispatch(prev.localPos, false)
                    prev.localPos = localPos
                    prev.visible = nowVisible
                    if (nowVisible) sub.dispatch(localPos, true)
                }

                prev.visible != nowVisible -> {
                    prev.visible = nowVisible
                    sub.dispatch(localPos, nowVisible)
                }
            }
        }
        val iter = state.entries.iterator()
        while (iter.hasNext()) {
            val (vh, st) = iter.next()
            if (vh in seen) continue
            if (st.visible) {
                val inner = vh.bindingAdapter
                val sub = inner?.let { a -> subscribers.firstOrNull { it.adapter === a } }
                sub?.dispatch?.invoke(st.localPos, false)
            }
            iter.remove()
        }
    }

    private fun flushAllInvisible() {
        if (state.isEmpty()) return
        val iter = state.entries.iterator()
        while (iter.hasNext()) {
            val (vh, st) = iter.next()
            if (st.visible) {
                val inner = vh.bindingAdapter
                val sub = inner?.let { a -> subscribers.firstOrNull { it.adapter === a } }
                sub?.dispatch?.invoke(st.localPos, false)
            }
            iter.remove()
        }
    }

    private fun computePercent(child: View): Int {
        val w = child.width
        val h = child.height
        if (w <= 0 || h <= 0) return 0
        if (!child.getGlobalVisibleRect(childRect)) return 0
        val visibleArea = childRect.width().toLong() * childRect.height().toLong()
        val totalArea = w.toLong() * h.toLong()
        if (totalArea <= 0) return 0
        return ((visibleArea * 100L) / totalArea).toInt().coerceIn(0, 100)
    }
}
