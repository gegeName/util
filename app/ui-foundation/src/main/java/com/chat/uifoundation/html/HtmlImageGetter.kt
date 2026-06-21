package com.chat.uifoundation.html

import android.text.Html
import android.util.Base64

/**
 * 将 HTML 中的 `<img>` 转换为 [AsyncDrawable] 占位。
 *
 * 解析为同步过程，此处仅返回 1x1 占位；真实加载与尺寸/换行重排由 HtmlTextView 完成。
 * 视频已在预处理阶段被改写为 `htmlvideo://<真实url>` 的 img；
 * 公式被改写为 `htmlformula://<b|i><base64(latex)>` 的 img，这里据前缀区分图片 / 视频 / 公式。
 */
internal class HtmlImageGetter : Html.ImageGetter {

    override fun getDrawable(source: String?): AsyncDrawable {
        val src = source.orEmpty()
        return when {
            src.startsWith(FORMULA_SCHEME) -> {
                val payload = src.substring(FORMULA_SCHEME.length)
                val blockFlag = payload.startsWith("b")
                val latex = decodeLatex(payload.drop(1))
                AsyncDrawable(latex, isVideo = false, isFormula = true).apply {
                    isBlock = blockFlag
                    setBounds(0, 0, 1, 1)
                }
            }
            src.startsWith(VIDEO_SCHEME) -> {
                AsyncDrawable(src.substring(VIDEO_SCHEME.length), isVideo = true).apply {
                    setBounds(0, 0, 1, 1)
                }
            }
            else -> AsyncDrawable(src, isVideo = false).apply {
                setBounds(0, 0, 1, 1)
            }
        }
    }

    private fun decodeLatex(b64: String): String = try {
        String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    companion object {
        /** 视频占位 img 的伪协议前缀 */
        const val VIDEO_SCHEME = "htmlvideo://"

        /** 公式占位 img 的伪协议前缀；其后第一个字符 b/i 标记块级/行内，再接 base64(latex) */
        const val FORMULA_SCHEME = "htmlformula://"
    }
}
