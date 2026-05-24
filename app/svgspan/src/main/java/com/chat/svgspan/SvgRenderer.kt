package com.chat.svgspan

import android.graphics.drawable.PictureDrawable
import com.caverock.androidsvg.SVG
import java.io.InputStream

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
