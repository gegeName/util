package com.chat.spanutil.span

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.style.ImageSpan
import kotlin.math.max

class CenterAlignImageSpan : ImageSpan {
    constructor(drawable: Drawable) : super(drawable)

    constructor(b: Bitmap) : super(b)

    override fun getSize(
        paint: Paint, text: CharSequence?, start: Int, end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        val d = getDrawable()
        val rect = d.getBounds()
        if (fm != null) {
            val pfm = paint.getFontMetricsInt()
            val fontHeight = pfm.descent - pfm.ascent
            val imgHeight = rect.height()
            val extra = max(0, imgHeight - fontHeight)
            val half = extra / 2
            fm.ascent = pfm.ascent - half
            fm.top = fm.ascent
            fm.descent = pfm.descent + (extra - half)
            fm.bottom = fm.descent
        }
        return rect.right
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val b = getDrawable()
        val transY = top + (bottom - top - b.getBounds().height()) / 2
        canvas.save()
        canvas.translate(x, transY.toFloat())
        b.draw(canvas)
        canvas.restore()
    }
}