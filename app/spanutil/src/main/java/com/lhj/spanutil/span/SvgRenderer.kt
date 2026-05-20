package com.lhj.spanutil.span

import android.graphics.drawable.PictureDrawable
import com.caverock.androidsvg.SVG
import java.io.InputStream

/**
 * 用 AndroidSVG 把 SVG 输入流解析成 [PictureDrawable]。
 *
 * 解析与渲染都在调用线程执行,远程下载场景由调用方在 IO 线程调用,
 * 本地资源调用方在主线程同步调用即可(矢量小,通常 <5ms)。
 *
 * 渲染出的 PictureDrawable 已经按 (w, h) 设好 bounds,可直接交给 ImageSpan。
 */
internal object SvgRenderer {

    fun render(input: InputStream, w: Int, h: Int): PictureDrawable {
        val svg = SVG.getFromInputStream(input)
        // 强制让 SVG 渲染到指定尺寸:viewBox 已存在时按 viewBox 缩放,
        // 没有 viewBox 则以 documentWidth/Height 为目标。
        if (svg.documentViewBox == null) {
            svg.documentWidth = w.toFloat()
            svg.documentHeight = h.toFloat()
        }
        val picture = svg.renderToPicture(w, h)
        return PictureDrawable(picture).apply { setBounds(0, 0, w, h) }
    }
}
