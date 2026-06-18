package com.simple.mylibrary.widget.html

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.AlignmentSpan
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.util.Base64
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnPreDraw

/**
 * 支持富文本展示的 TextView。
 *
 * 能力：
 * 1. 用系统 [HtmlCompat] 解析常见 HTML 标签（b/i/u/strong/em/p/br/div/font/a/ul/ol/li/h1-6 等）。
 * 2. 图片异步加载，并按图片实际尺寸自动排版——大图独占一行居中，小图与文字同行。
 * 3. 图片可点击（[setOnImageClickListener]）。
 * 4. `<video>` 标签展示首帧 + 中心播放按钮，点击回调（[setOnVideoClickListener]），不内置播放器。
 *
 * 不绑定具体图片库，通过 [setMediaLoader] 注入 [HtmlMediaLoader] 实现（Glide / Coil 等）。
 *
 * 用法：
 * ```
 * htmlTextView.setMediaLoader(loader)
 * htmlTextView.setOnImageClickListener { url -> }
 * htmlTextView.setOnVideoClickListener { url -> }
 * htmlTextView.setHtml(html)
 * ```
 */
class HtmlTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 大图换行阈值：显示宽度 ≥ 可用宽度 × 该比例时，图片独占一行并居中。取值 (0,1]。 */
    var blockImageWidthRatio: Float = 0.5f

    /** 视频播放按钮图标；为空时由 [AsyncDrawable] 绘制默认按钮。 */
    var playButtonDrawable: Drawable? = null

    /** 大图圆角半径（px），默认 0（不圆角）。仅作用于独占一行、撑满宽度的大图。 */
    var blockImageCornerRadius: Float = 0f

    private var mediaLoader: HtmlMediaLoader? = null
    private var formulaRenderer: HtmlFormulaRenderer? = null

    /** 解析后的原始富文本，作为每次重排的模板（保证多次重建不叠加换行）。 */
    private var originalSpanned: Spanned? = null

    /** 已加载完成的媒体缓存：url -> 真实 drawable（bounds 已按显示尺寸设置）。 */
    private val loadedCache = HashMap<String, Drawable>()

    /** 已发起加载的 url，避免重复请求。 */
    private val requestedUrls = HashSet<String>()

    private var onImageClick: ((String) -> Unit)? = null
    private var onVideoClick: ((String) -> Unit)? = null

    private val imageGetter = HtmlImageGetter()

    init {
        movementMethod = LinkMovementMethod.getInstance()
        // 去掉点击图片 / 链接时的高亮块
        highlightColor = Color.TRANSPARENT
    }

    fun setMediaLoader(loader: HtmlMediaLoader) {
        mediaLoader = loader
    }

    fun setFormulaRenderer(renderer: HtmlFormulaRenderer) {
        formulaRenderer = renderer
    }

    fun setOnImageClickListener(listener: (url: String) -> Unit) {
        onImageClick = listener
    }

    fun setOnVideoClickListener(listener: (url: String) -> Unit) {
        onVideoClick = listener
    }

    /**
     * 设置并展示一段 HTML。会先以占位形式立即渲染文本，随后异步加载图片 / 视频首帧并自动重排。
     */
    fun setHtml(html: String) {
        loadedCache.clear()
        requestedUrls.clear()

        val processed = preprocessFormulas(preprocessVideoTags(html))
        val spanned = HtmlCompat.fromHtml(
            processed,
            HtmlCompat.FROM_HTML_MODE_LEGACY,
            imageGetter,
            null
        )
        originalSpanned = spanned

        // 先渲染（图片为占位），再在拿到宽度后加载媒体
        rebuildAndSetText()
        runWhenWidthReady { loadAllMedia() }
    }

    // region 媒体加载与重排

    private fun loadAllMedia() {
        val spanned = originalSpanned ?: return
        val avail = availableWidth()
        if (avail <= 0) return

        val spans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)

        // 1) 公式：本地同步渲染，不依赖 mediaLoader
        val renderer = formulaRenderer
        if (renderer != null) {
            var rendered = false
            for (span in spans) {
                val async = span.drawable as? AsyncDrawable ?: continue
                if (!async.isFormula || async.wrapped != null) continue
                val d = renderer.render(async.url, async.isBlock, textSize, currentTextColor)
                if (d != null) {
                    applyLoaded(async, d, avail)
                    rendered = true
                }
            }
            if (rendered) rebuildAndSetText()
        }

        // 2) 图片 / 视频：异步走 mediaLoader（按 url 分组，相同 url 只请求一次）
        val loader = mediaLoader ?: return
        val groups = LinkedHashMap<String, MutableList<AsyncDrawable>>()
        for (span in spans) {
            val async = span.drawable as? AsyncDrawable ?: continue
            if (async.isFormula) continue
            groups.getOrPut(async.url) { mutableListOf() }.add(async)
        }

        for ((url, asyncList) in groups) {
            val sample = asyncList.first()
            val cached = loadedCache[url]
            if (cached != null) {
                asyncList.forEach { applyLoaded(it, cached, avail) }
                continue
            }
            if (!requestedUrls.add(url)) continue

            val callback: (Drawable?) -> Unit = cb@{ drawable ->
                if (drawable == null) return@cb
                loadedCache[url] = drawable
                asyncList.forEach { applyLoaded(it, drawable, avail) }
                rebuildAndSetText()
            }
            if (sample.isVideo) {
                loader.loadVideoFrame(url, avail, callback)
            } else {
                loader.loadImage(url, avail, callback)
            }
        }
    }

    /** 按可用宽度等比约束尺寸，并把真实 drawable 装入占位。 */
    private fun applyLoaded(async: AsyncDrawable, drawable: Drawable, avail: Int) {
        var iw = drawable.intrinsicWidth
        var ih = drawable.intrinsicHeight
        if (iw <= 0 || ih <= 0) {
            // 无固有尺寸（部分视频帧）时给一个 16:9 兜底
            iw = avail
            ih = avail * 9 / 16
        }
        val dw: Int
        val dh: Int
        if (async.isFormula) {
            // 公式：保持渲染原始尺寸，仅当超过可用宽才等比缩小；isBlock 在解析时已定，不改
            if (avail > 0 && iw > avail) {
                dw = avail
                dh = (ih.toLong() * avail / iw).toInt().coerceAtLeast(1)
            } else {
                dw = iw
                dh = ih
            }
            async.cornerRadius = 0f
        } else {
            // 图片/视频：原始宽达到可用宽度阈值即视为大图，撑满整宽并独占一行；否则按原尺寸 inline
            val isBlock = avail > 0 && iw >= avail * blockImageWidthRatio
            if (isBlock) {
                dw = avail
                dh = (ih.toLong() * avail / iw).toInt().coerceAtLeast(1)
            } else {
                dw = iw
                dh = ih
            }
            async.isBlock = isBlock
            async.cornerRadius = if (isBlock) blockImageCornerRadius else 0f
            async.playButton = playButtonDrawable
        }
        async.wrapped = drawable
        async.setBounds(0, 0, dw, dh)
    }

    /**
     * 以 [originalSpanned] 为模板重建文本：
     * - 为每个图片区间附加点击 [ClickableSpan]；
     * - 已加载且达到阈值的大图：前后补换行并居中（独占一行）；
     * - 小图保持 inline。
     */
    private fun rebuildAndSetText() {
        val src = originalSpanned ?: return
        val builder = SpannableStringBuilder(src)
        val avail = availableWidth()

        // 从后往前处理，插入换行时不影响尚未处理 span 的索引
        val spans = builder.getSpans(0, builder.length, ImageSpan::class.java)
            .sortedByDescending { builder.getSpanStart(it) }

        for (span in spans) {
            val async = span.drawable as? AsyncDrawable ?: continue
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            if (start < 0 || end < 0) continue

            if (async.isFormula) {
                if (!async.isBlock) {
                    // 行内公式：替换为基线对齐的 ImageSpan，与文字同行；不可点击
                    builder.removeSpan(span)
                    builder.setSpan(
                        ImageSpan(async, ImageSpan.ALIGN_BASELINE),
                        start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    continue
                }
                // 块级公式：走下方"居中独占一行"逻辑（不加点击）
            } else {
                // 图片/视频：可点击
                builder.setSpan(
                    MediaClickableSpan(async),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (!async.isBlock) continue

            // 大图独占一行：把图片前后紧邻的换行压缩为恰好一个，避免出现空行（紧贴文字）。
            // 后侧：删掉紧随的所有换行，再补一个
            while (true) {
                val e = builder.getSpanEnd(span)
                if (e < builder.length && builder[e] == '\n') builder.delete(e, e + 1) else break
            }
            builder.insert(builder.getSpanEnd(span), "\n")
            // 前侧：删掉紧邻的所有换行，非文本开头时补一个
            while (true) {
                val s = builder.getSpanStart(span)
                if (s > 0 && builder[s - 1] == '\n') builder.delete(s - 1, s) else break
            }
            if (builder.getSpanStart(span) > 0) {
                builder.insert(builder.getSpanStart(span), "\n")
            }
            // 居中对齐该行
            val s2 = builder.getSpanStart(span)
            val e2 = builder.getSpanEnd(span)
            builder.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                s2, e2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        setText(builder, BufferType.SPANNABLE)
    }

    private inner class MediaClickableSpan(private val async: AsyncDrawable) : ClickableSpan() {
        override fun onClick(widget: View) {
            if (async.isVideo) {
                onVideoClick?.invoke(async.url)
            } else {
                onImageClick?.invoke(async.url)
            }
        }

        // 图片点击不需要链接样式（下划线 / 变色）
        override fun updateDrawState(ds: android.text.TextPaint) {
            // no-op
        }
    }

    // endregion

    // region 工具

    private fun availableWidth(): Int = width - compoundPaddingLeft - compoundPaddingRight

    private fun runWhenWidthReady(action: () -> Unit) {
        if (availableWidth() > 0) {
            action()
        } else {
            doOnPreDraw { action() }
        }
    }

    /**
     * 把 `<video>` 标签改写为 `htmlvideo://` 前缀的占位 `<img>`，
     * 从而复用图片的解析 / 加载 / 换行 / 点击管线。
     */
    private fun preprocessVideoTags(html: String): String {
        var result = VIDEO_TAG_REGEX.replace(html) { match ->
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            val url = SRC_REGEX.find(attrs)?.groupValues?.getOrNull(1)
                ?: SRC_REGEX.find(inner)?.groupValues?.getOrNull(1)
            if (url.isNullOrEmpty()) "" else videoImgTag(url)
        }
        result = VIDEO_SELF_CLOSING_REGEX.replace(result) { match ->
            val url = SRC_REGEX.find(match.groupValues[1])?.groupValues?.getOrNull(1)
            if (url.isNullOrEmpty()) "" else videoImgTag(url)
        }
        return result
    }

    private fun videoImgTag(url: String) =
        "<img src=\"${HtmlImageGetter.VIDEO_SCHEME}$url\">"

    /**
     * 把正文中的 LaTeX 公式抠出来（先于 HTML 解析），改写为 `htmlformula://` 占位 `<img>`，
     * 避免 LaTeX 里的 `< > & \` 破坏 HTML 解析。块级 $$...$$ / \[...\]，行内 $...$ / \(...\)。
     */
    private fun preprocessFormulas(html: String): String {
        var r = html
        r = BLOCK_DOLLAR_REGEX.replace(r) { formulaImgTag(it.groupValues[1], true) }
        r = BLOCK_BRACKET_REGEX.replace(r) { formulaImgTag(it.groupValues[1], true) }
        r = INLINE_PAREN_REGEX.replace(r) { formulaImgTag(it.groupValues[1], false) }
        r = INLINE_DOLLAR_REGEX.replace(r) { formulaImgTag(it.groupValues[1], false) }
        return r
    }

    private fun formulaImgTag(latex: String, isBlock: Boolean): String {
        val trimmed = latex.trim()
        if (trimmed.isEmpty()) return ""
        val flag = if (isBlock) "b" else "i"
        val encoded = Base64.encodeToString(
            trimmed.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "<img src=\"${HtmlImageGetter.FORMULA_SCHEME}$flag$encoded\">"
    }

    // endregion

    companion object {
        private val REGEX_OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val VIDEO_TAG_REGEX = Regex("<video([^>]*)>(.*?)</video>", REGEX_OPTS)
        private val VIDEO_SELF_CLOSING_REGEX = Regex("<video([^>]*)/>", REGEX_OPTS)
        private val SRC_REGEX = Regex("src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

        // LaTeX 公式定界符。块级先于行内处理，$$ 先于单 $。
        private val BLOCK_DOLLAR_REGEX = Regex("\\$\\$(.+?)\\$\\$", RegexOption.DOT_MATCHES_ALL)
        private val BLOCK_BRACKET_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
        private val INLINE_PAREN_REGEX = Regex("\\\\\\((.+?)\\\\\\)", RegexOption.DOT_MATCHES_ALL)
        // 行内 $...$：开界 $ 不被转义且其后紧跟非空白；闭界 $ 前为非空白/非反斜杠且其后不是数字
        // （后一条排除 "$5 ... $10" 这类货币写法被误判为公式）
        private val INLINE_DOLLAR_REGEX = Regex("(?<!\\\\)\\\$(?=\\S)(.+?)(?<![\\s\\\\])\\\$(?!\\d)")
    }
}
