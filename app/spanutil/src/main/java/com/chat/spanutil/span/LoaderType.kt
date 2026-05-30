package com.chat.spanutil.span

import com.chat.spanutil.SpanBuilder

/**
 * 标识 [SpanImageLoader] 在 SpanBuilder 中的用途分类，配合
 * [SpanBuilder.setLoader] / [SpanBuilder.requireLoader] 使用。
 *
 * @param suggestedModule 该类型推荐的扩展库名，仅用于错误提示
 */
enum class LoaderType(val suggestedModule: String) {
    /** 普通位图：默认实现见 glidespan 库。 */
    Image("glidespan"),

    /** GIF / WebP 动图：默认实现见 glidespan 库（专用 asGif 请求 + 循环配置）。 */
    Gif("glidespan"),

    /** SVG 矢量图:默认实现见 svgspan 库。 */
    Svg("svgspan"),

    /** SVGA 动效:默认实现见 svgaspan 库。 */
    Svga("svgaspan"),
}
