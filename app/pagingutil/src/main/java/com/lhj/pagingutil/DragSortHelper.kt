package com.lhj.pagingutil

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

/**
 * Paging 列表的拖动排序辅助器。
 */
class DragSortHelper<T : Any> internal constructor(
    private val pagingAdapter: PagingDataAdapter<T, *>,
    private val keyOf: (T) -> Any,
    private val longPressEnabled: Boolean,
    private val vibrateOnDragStart: Boolean,
    private val canDrag: ((item: T, localPos: Int) -> Boolean)?,
    private val onMoved: (fromKey: Any, toKey: Any, fromLocal: Int, toLocal: Int) -> Unit
) {

    private fun localPos(holder: RecyclerView.ViewHolder): Int {
        if (holder.bindingAdapter !== pagingAdapter) return -1
        return holder.bindingAdapterPosition
    }

    private fun isDraggable(local: Int): Boolean {
        if (local < 0 || local >= pagingAdapter.itemCount) return false
        val predicate = canDrag ?: return true
        val item = pagingAdapter.peek(local) ?: return false
        return predicate(item, local)
    }

    /**
     * 计算 `notifyItemMoved(from, to)` 之后，需要把哪些 locked 项 move 回原位。
     * 返回 `(shiftedIndex, originalIndex)` 列表：locked 在整体平移后落到 shiftedIndex,
     * 需要再发一次 notifyItemMoved(shiftedIndex, originalIndex) 让它视觉上保持不动。
     *
     * @param from 被拖项原位置
     * @param to   被拖项目标位置
     */
    private fun collectLockedBetween(from: Int, to: Int): List<Pair<Int, Int>> {
        if (from == to) return emptyList()
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val step = if (from < to) -1 else 1
        val result = mutableListOf<Pair<Int, Int>>()
        for (i in (lo + 1) until hi) {
            if (!isDraggable(i)) result.add((i + step) to i)
        }
        return result
    }

    private val callback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
        0
    ) {
        override fun isLongPressDragEnabled(): Boolean = longPressEnabled
        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            if (!isDraggable(localPos(viewHolder))) return 0
            return super.getMovementFlags(recyclerView, viewHolder)
        }

        override fun canDropOver(
            rv: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return isDraggable(localPos(target))
        }

        override fun onMove(
            rv: RecyclerView,
            from: RecyclerView.ViewHolder,
            to: RecyclerView.ViewHolder
        ): Boolean {
            val fromLocal = localPos(from)
            val toLocal = localPos(to)
            if (!isDraggable(fromLocal) || !isDraggable(toLocal)) return false

            val snapshot = pagingAdapter.snapshot()
            val fromItem = snapshot[fromLocal] ?: return false
            val toItem = snapshot[toLocal] ?: return false

            val lockedShift = collectLockedBetween(fromLocal, toLocal)
            pagingAdapter.notifyItemMoved(fromLocal, toLocal)
            lockedShift.forEach { (shifted, original) ->
                pagingAdapter.notifyItemMoved(shifted, original)
            }
            onMoved(keyOf(fromItem), keyOf(toItem), fromLocal, toLocal)
            return true
        }

        override fun chooseDropTarget(
            selected: RecyclerView.ViewHolder,
            dropTargets: MutableList<RecyclerView.ViewHolder>,
            curX: Int,
            curY: Int
        ): RecyclerView.ViewHolder? {
            val filtered = dropTargets.filter { isDraggable(localPos(it)) }.toMutableList()
            return super.chooseDropTarget(selected, filtered, curX, curY)
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)

            if (vibrateOnDragStart && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                val view = viewHolder?.itemView ?: return
                val context = view.context
                val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.GESTURE_START
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }

                @Suppress("DEPRECATION")
                val didFeedback = view.performHapticFeedback(
                    constant,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                if (!didFeedback) {
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        context.getSystemService(Vibrator::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    vibrator?.let {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            it.vibrate(
                                VibrationEffect.createOneShot(
                                    20,
                                    VibrationEffect.DEFAULT_AMPLITUDE
                                )
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            it.vibrate(20)
                        }
                    }
                }
            }
        }
    }

    internal val touchHelper = ItemTouchHelper(callback)

    /** 业务自己控制何时启动拖动（手柄拖动场景）：调用 holder 的拖动手势 */
    fun startDrag(holder: RecyclerView.ViewHolder) = touchHelper.startDrag(holder)
}
