package com.chat.mylibrary.guide

/**
 * 引导高亮区域的形状。padding 单位为像素，用于让挖洞略大于 View 本身。
 */
sealed class HighlightShape {
    object None : HighlightShape()
    data class Rect(val paddingPx: Int = 0) : HighlightShape()
    data class RoundRect(val radiusPx: Float, val paddingPx: Int = 0) : HighlightShape()
    data class Circle(val paddingPx: Int = 0) : HighlightShape()
    data class Oval(val paddingPx: Int = 0) : HighlightShape()
}
