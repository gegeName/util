package com.chat.uifoundation.html

import android.graphics.drawable.Drawable

/**
 * 正文 LaTeX 公式渲染接口（注入式，与 [HtmlMediaLoader] 一致，库不绑定具体实现）。
 *
 * 渲染为同步过程（如 JLatexMath 本地渲染），在主线程被调用。
 * 返回的 [Drawable] 需带有正确的 intrinsicWidth / intrinsicHeight。
 */
interface HtmlFormulaRenderer {

    /**
     * 把一段 LaTeX 渲染成 Drawable。
     *
     * @param latex      纯 LaTeX 源（不含 $ / \( \) 等定界符）
     * @param isBlock    是否块级公式（$$...$$ / \[...\]）。块级居中独占一行，行内与文字同行
     * @param textSizePx 当前正文字号（px），便于公式与文字大小匹配
     * @param color      当前正文颜色
     * @return 渲染结果；失败返回 null
     */
    fun render(latex: String, isBlock: Boolean, textSizePx: Float, color: Int): Drawable?
}
