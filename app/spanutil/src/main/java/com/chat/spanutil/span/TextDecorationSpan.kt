package com.chat.spanutil.span

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.style.ReplacementSpan
import androidx.annotation.ColorInt
import androidx.annotation.Px

/**
 * 统一文字装饰 Span：渐变填充 + 描边 + 发光 + 阴影，可单独或任意组合使用。
 */
class TextDecorationSpan : ReplacementSpan() {

    var gradientColors: IntArray? = null
    var gradientPositions: FloatArray? = null
    var gradientVertical: Boolean = false

    @ColorInt var strokeColor: Int = Color.TRANSPARENT
    @Px var strokeWidthPx: Float = 0f

    @ColorInt var glowColor: Int = Color.TRANSPARENT
    @Px var glowRadiusPx: Float = 0f

    @ColorInt var shadowColor: Int = Color.TRANSPARENT
    @Px var shadowRadius: Float = 0f
    @Px var shadowDx: Float = 0f
    @Px var shadowDy: Float = 0f

    private var measuredWidth = 0f
    private var cachedShader: LinearGradient? = null
    private var cachedShaderWidth = 0f
    private var cachedShaderTop = 0
    private var cachedShaderBottom = 0
    private val shaderMatrix = Matrix()
    private var cachedBlurFilter: BlurMaskFilter? = null
    private var cachedBlurRadius = 0f

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        if (fm != null) paint.getFontMetricsInt(fm)
        val len = text?.length ?: 0
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        measuredWidth = if (text != null && s < e) paint.measureText(text, s, e) else 0f
        return measuredWidth.toInt()
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
        paint: Paint,
    ) {
        if (text == null) return
        val len = text.length
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        if (s >= e) return
        val width = if (measuredWidth > 0f) measuredWidth else paint.measureText(text, s, e)
        if (width <= 0f) return

        val savedStyle = paint.style
        val savedColor = paint.color
        val savedShader = paint.shader
        val savedStrokeWidth = paint.strokeWidth
        val savedMaskFilter = paint.maskFilter

        if (glowRadiusPx > 0f) {
            if (cachedBlurRadius != glowRadiusPx) {
                cachedBlurFilter = BlurMaskFilter(glowRadiusPx, BlurMaskFilter.Blur.NORMAL)
                cachedBlurRadius = glowRadiusPx
            }
            paint.style = Paint.Style.FILL
            paint.color = glowColor
            paint.shader = null
            paint.maskFilter = cachedBlurFilter
            canvas.drawText(text, s, e, x, y.toFloat(), paint)
            paint.maskFilter = null
        }

        if (strokeWidthPx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidthPx
            paint.color = strokeColor
            paint.shader = null
            canvas.drawText(text, s, e, x, y.toFloat(), paint)
        }

        paint.style = Paint.Style.FILL
        paint.strokeWidth = savedStrokeWidth
        paint.maskFilter = null
        if (shadowRadius > 0f) {
            paint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        }
        if (gradientColors != null) {
            val shader = obtainShader(width, top, bottom)
            shaderMatrix.reset()
            shaderMatrix.setTranslate(x, 0f)
            shader.setLocalMatrix(shaderMatrix)
            paint.shader = shader
            paint.color = savedColor
        } else {
            paint.shader = savedShader
            paint.color = savedColor
        }
        canvas.drawText(text, s, e, x, y.toFloat(), paint)

        paint.style = savedStyle
        paint.color = savedColor
        paint.shader = savedShader
        paint.strokeWidth = savedStrokeWidth
        paint.maskFilter = savedMaskFilter
        if (shadowRadius > 0f) paint.clearShadowLayer()
    }

    private fun obtainShader(width: Float, top: Int, bottom: Int): LinearGradient {
        val sizeChanged = if (gradientVertical) {
            top != cachedShaderTop || bottom != cachedShaderBottom
        } else {
            width != cachedShaderWidth
        }
        val cached = cachedShader
        if (cached != null && !sizeChanged) return cached
        val shader = if (gradientVertical) {
            LinearGradient(0f, top.toFloat(), 0f, bottom.toFloat(),
                gradientColors!!, gradientPositions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, width, 0f,
                gradientColors!!, gradientPositions, Shader.TileMode.CLAMP)
        }
        cachedShader = shader
        cachedShaderWidth = width
        cachedShaderTop = top
        cachedShaderBottom = bottom
        return shader
    }


    fun withGradient(colors: IntArray, positions: FloatArray?, vertical: Boolean): TextDecorationSpan {
        if (gradientColors !== colors) cachedShader = null
        gradientColors = colors
        gradientPositions = positions
        gradientVertical = vertical
        return this
    }

    fun withStroke(color: Int, width: Float): TextDecorationSpan {
        strokeColor = color
        strokeWidthPx = width
        return this
    }

    fun withGlow(color: Int, radius: Float): TextDecorationSpan {
        if (cachedBlurRadius != radius) cachedBlurFilter = null
        glowColor = color
        glowRadiusPx = radius
        return this
    }

    fun withShadow(color: Int, radius: Float, dx: Float, dy: Float): TextDecorationSpan {
        shadowColor = color
        shadowRadius = radius
        shadowDx = dx
        shadowDy = dy
        return this
    }
}
