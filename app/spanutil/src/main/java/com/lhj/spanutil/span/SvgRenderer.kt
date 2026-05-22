package com.lhj.spanutil.span

import android.graphics.drawable.PictureDrawable
import com.caverock.androidsvg.SVG
import java.io.InputStream

/**
 * 用 AndroidSVG 把 SVG 输入流解析成 [PictureDrawable]。
 */
internal object SvgRenderer {

    fun render(input: InputStream, w: Int, h: Int): PictureDrawable {
        val svg = SVG.getFromInputStream(input)
        if (svg.documentViewBox == null) {
            svg.documentWidth = w.toFloat()
            svg.documentHeight = h.toFloat()
        }
        val picture = svg.renderToPicture(w, h)
        return PictureDrawable(picture).apply { setBounds(0, 0, w, h) }
    }
}
