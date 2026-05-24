package com.chat.spanutil.span

import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import androidx.annotation.Px

/**
 * 通过 baselineShift 整体上下平移文字（正数下移、负数上移）。
 * 仅用于视觉微调，不会撑高行高。
 */
internal class VerticalShiftSpan(@Px private val shiftDown: Int) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.baselineShift += shiftDown }
    override fun updateMeasureState(tp: TextPaint) { tp.baselineShift += shiftDown }
}

/**
 * 仅占据指定宽度、不绘制任何内容的占位 Span，用于实现文字左右 margin。
 */
internal class BlankWidthSpan(@Px private val width: Int) : ReplacementSpan() {
    override fun getSize(
        paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?,
    ): Int = width

    override fun draw(
        canvas: Canvas, text: CharSequence?, start: Int, end: Int,
        x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
    ) {}
}
