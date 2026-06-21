package com.chat.mylibrary.html

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Html
import android.text.Layout
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.util.AttributeSet
import android.util.Base64
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.text.HtmlCompat
import androidx.core.view.doOnPreDraw
import androidx.core.text.parseAsHtml
import org.xml.sax.XMLReader

/**
 * 支持富文本展示的 TextView。
 *
 * 能力：
 * 1. 用系统 [HtmlCompat] 解析常见 HTML 标签（b/i/u/strong/em/p/br/div/font/a/ul/ol/li/h1-6 等）。
 * 2. 图片异步加载，并按图片实际尺寸自动排版——大图独占一行居中，小图与文字同行。
 * 3. 图片可点击（[setOnImageClickListener]）。
 * 4. `<video>` 标签展示首帧 + 中心播放按钮，点击回调（[setOnVideoClickListener]），不内置播放器。
 * 5. 额外支持 audio/iframe/embed/object/table/pre/code/mark/blockquote/del/sup/sub 等标签兜底解析。
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
    private var onAudioClick: ((String) -> Unit)? = null
    private var onEmbedClick: ((String) -> Unit)? = null

    private val imageGetter = HtmlImageGetter()
    private val tagHandler = RichTagHandler()

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

    fun setOnAudioClickListener(listener: (url: String) -> Unit) {
        onAudioClick = listener
    }

    fun setOnEmbedClickListener(listener: (url: String) -> Unit) {
        onEmbedClick = listener
    }

    /**
     * 设置并展示一段 HTML。会先以占位形式立即渲染文本，随后异步加载图片 / 视频首帧并自动重排。
     */
    fun setHtml(html: String) {
        loadedCache.clear()
        requestedUrls.clear()

        val processed = preprocessHtml(html)
        val spanned = processed.parseAsHtml(HtmlCompat.FROM_HTML_MODE_LEGACY, imageGetter, tagHandler)
        originalSpanned = spanned

        rebuildAndSetText()
        runWhenWidthReady { loadAllMedia() }
    }

    // region 媒体加载与重排

    private fun loadAllMedia() {
        val spanned = originalSpanned ?: return
        val avail = availableWidth()
        if (avail <= 0) return

        val spans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)

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
            iw = avail
            ih = avail * 9 / 16
        }
        val dw: Int
        val dh: Int
        if (async.isFormula) {
            if (avail in 1 until iw) {
                dw = avail
                dh = (ih.toLong() * avail / iw).toInt().coerceAtLeast(1)
            } else {
                dw = iw
                dh = ih
            }
            async.cornerRadius = 0f
        } else {
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

        val spans = builder.getSpans(0, builder.length, ImageSpan::class.java)
            .sortedByDescending { builder.getSpanStart(it) }

        for (span in spans) {
            val async = span.drawable as? AsyncDrawable ?: continue
            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            if (start < 0 || end < 0) continue

            if (async.isFormula) {
                if (!async.isBlock) {
                    builder.removeSpan(span)
                    builder.setSpan(
                        ImageSpan(async, ImageSpan.ALIGN_BASELINE),
                        start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    continue
                }
            } else {
                builder.setSpan(
                    MediaClickableSpan(async),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            if (!async.isBlock) continue

            while (true) {
                val e = builder.getSpanEnd(span)
                if (e < builder.length && builder[e] == '\n') builder.delete(e, e + 1) else break
            }
            builder.insert(builder.getSpanEnd(span), "\n")
            while (true) {
                val s = builder.getSpanStart(span)
                if (s > 0 && builder[s - 1] == '\n') builder.delete(s - 1, s) else break
            }
            if (builder.getSpanStart(span) > 0) {
                builder.insert(builder.getSpanStart(span), "\n")
            }
            val s2 = builder.getSpanStart(span)
            val e2 = builder.getSpanEnd(span)
            builder.setSpan(
                AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
                s2, e2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        applySpecialLinkSpans(builder)
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

        override fun updateDrawState(ds: android.text.TextPaint) {
            // no-op
        }
    }

    private inner class SpecialLinkClickableSpan(
        private val kind: String,
        private val url: String
    ) : ClickableSpan() {

        override fun onClick(widget: View) {
            when (kind) {
                SPECIAL_AUDIO -> onAudioClick?.invoke(url)
                SPECIAL_EMBED -> onEmbedClick?.invoke(url)
            }
        }

        override fun updateDrawState(ds: android.text.TextPaint) {
            ds.color = linkTextColors.defaultColor
            ds.isUnderlineText = true
        }
    }

    private fun applySpecialLinkSpans(builder: SpannableStringBuilder) {
        val spans = builder.getSpans(0, builder.length, URLSpan::class.java)
        for (span in spans) {
            val kind = when {
                span.url.startsWith(AUDIO_SCHEME) -> SPECIAL_AUDIO
                span.url.startsWith(EMBED_SCHEME) -> SPECIAL_EMBED
                else -> null
            } ?: continue

            val start = builder.getSpanStart(span)
            val end = builder.getSpanEnd(span)
            val flags = builder.getSpanFlags(span)
            val payload = if (kind == SPECIAL_AUDIO) {
                span.url.substring(AUDIO_SCHEME.length)
            } else {
                span.url.substring(EMBED_SCHEME.length)
            }
            val url = decodePayload(payload)
            builder.removeSpan(span)
            if (start >= 0 && end > start && url.isNotEmpty()) {
                builder.setSpan(SpecialLinkClickableSpan(kind, url), start, end, flags)
            }
        }
    }

    private fun availableWidth(): Int = width - compoundPaddingLeft - compoundPaddingRight

    /**
     * 返回指定媒体 url 当前在 TextView 内容中的显示区域，坐标相对本 TextView 左上角。
     *
     * 用于业务层把播放器、预览层等真实 View 覆盖到图片 / 视频占位区域上。
     */
    fun findMediaBounds(url: String): Rect? {
        val spanned = text as? Spanned ?: return null
        val layout = layout ?: return null
        val spans = spanned.getSpans(0, spanned.length, ImageSpan::class.java)
        for (span in spans) {
            val async = span.drawable as? AsyncDrawable ?: continue
            if (async.url != url) continue
            val start = spanned.getSpanStart(span)
            if (start < 0) continue

            val line = layout.getLineForOffset(start)
            val drawableBounds = async.bounds
            val mediaWidth = drawableBounds.width().coerceAtLeast(1)
            val mediaHeight = drawableBounds.height().coerceAtLeast(1)
            if (width <= 0 || mediaWidth <= 1 || mediaHeight <= 1) return null
            val contentLeft = totalPaddingLeft
            val contentWidth = (width - totalPaddingLeft - totalPaddingRight).coerceAtLeast(0)
            val left = if (async.isBlock && contentWidth > mediaWidth) {
                contentLeft + (contentWidth - mediaWidth) / 2
            } else {
                contentLeft + layout.getPrimaryHorizontal(start).toInt()
            }
            val bottom = totalPaddingTop + layout.getLineBottom(line)
            val top = bottom - mediaHeight
            return Rect(left, top, left + mediaWidth, bottom)
        }
        return null
    }

    private fun runWhenWidthReady(action: () -> Unit) {
        if (availableWidth() > 0) {
            action()
        } else {
            doOnPreDraw { action() }
        }
    }

    private fun preprocessHtml(html: String): String {
        val protected = protectRawTextBlocks(html)
        var result = protected.html
        result = preprocessOrderedLists(result)
        result = preprocessTables(result)
        result = preprocessVideoTags(result)
        result = preprocessAudioTags(result)
        result = preprocessEmbedTags(result)
        result = preprocessFormulas(result)
        return restoreRawTextBlocks(result, protected.blocks)
    }

    /**
     * 把 `<video>` 标签改写为 `htmlvideo://` 前缀的占位 `<img>`，
     * 从而复用图片的解析 / 加载 / 换行 / 点击管线。
     */
    private fun preprocessVideoTags(html: String): String {
        var result = VIDEO_TAG_REGEX.replace(html) { match ->
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            val url = findAttr(attrs, "src") ?: findAttr(inner, "src")
            if (url.isNullOrEmpty()) "" else videoImgTag(url)
        }
        result = VIDEO_SELF_CLOSING_REGEX.replace(result) { match ->
            val url = findAttr(match.groupValues[1], "src")
            if (url.isNullOrEmpty()) "" else videoImgTag(url)
        }
        return result
    }

    private fun videoImgTag(url: String) =
        "<img src=\"${htmlAttr(HtmlImageGetter.VIDEO_SCHEME + url)}\">"

    /**
     * `<audio>` 不适合在 TextView 内播放，降级为可点击文本并把真实 url 交给外部处理。
     */
    private fun preprocessAudioTags(html: String): String {
        var result = AUDIO_TAG_REGEX.replace(html) { match ->
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            val url = findAttr(attrs, "src") ?: findAttr(inner, "src")
            if (url.isNullOrEmpty()) "" else specialLinkTag(AUDIO_SCHEME, url, "音频")
        }
        result = AUDIO_SELF_CLOSING_REGEX.replace(result) { match ->
            val url = findAttr(match.groupValues[1], "src")
            if (url.isNullOrEmpty()) "" else specialLinkTag(AUDIO_SCHEME, url, "音频")
        }
        return result
    }

    /**
     * iframe/embed/object 统一降级为外部内容链接，避免在 TextView 内嵌网页渲染。
     */
    private fun preprocessEmbedTags(html: String): String {
        var result = IFRAME_TAG_REGEX.replace(html) { match ->
            val url = findAttr(match.groupValues[1], "src")
            if (url.isNullOrEmpty()) "" else specialLinkTag(EMBED_SCHEME, url, "外部内容")
        }
        result = EMBED_TAG_REGEX.replace(result) { match ->
            val url = findAttr(match.groupValues[1], "src")
            if (url.isNullOrEmpty()) "" else specialLinkTag(EMBED_SCHEME, url, "外部内容")
        }
        result = OBJECT_TAG_REGEX.replace(result) { match ->
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            val url = findAttr(attrs, "data") ?: findAttr(inner, "src")
            if (url.isNullOrEmpty()) "" else specialLinkTag(EMBED_SCHEME, url, "外部内容")
        }
        return result
    }

    /**
     * TextView 不能真正排版表格，这里保留单元格里的行内 HTML，并把 tr/td/th 降级成可读的文本行。
     */
    private fun preprocessTables(html: String): String =
        TABLE_TAG_REGEX.replace(html) { match ->
            val inner = match.groupValues[1]
            val rows = TABLE_ROW_REGEX.findAll(inner).map { row ->
                val rowInner = row.groupValues[1]
                val cells = TABLE_CELL_REGEX.findAll(rowInner)
                    .map { it.groupValues[1].trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                if (cells.isEmpty()) {
                    stripBlockTableTags(rowInner).trim()
                } else {
                    cells.joinToString("&nbsp;&nbsp;&nbsp;&nbsp;")
                }
            }.filter { it.isNotEmpty() }.toList()

            if (rows.isEmpty()) {
                stripBlockTableTags(inner)
            } else {
                rows.joinToString("<br>")
            }
        }

    /**
     * 系统 Html 对 `<ol type/start>` 的支持不稳定，这里把有序列表改写为显式序号文本。
     *
     * 支持：
     * - `<ol type="1">` / decimal：1. 2. 3.
     * - `<ol type="A">` / upper-alpha：A. B. C.
     * - `<ol type="a">` / lower-alpha：a. b. c.
     * - `<ol type="I">` / upper-roman：I. II. III.
     * - `<ol type="i">` / lower-roman：i. ii. iii.
     * - `<ol style="list-style-type: cjk-ideographic">`：一、二、三
     * - `start="n"` 起始序号。
     */
    private fun preprocessOrderedLists(html: String): String =
        ORDERED_LIST_REGEX.replace(html) { match ->
            val attrs = match.groupValues[1]
            val inner = match.groupValues[2]
            if (LIST_OPEN_REGEX.containsMatchIn(inner)) {
                return@replace match.value
            }
            val style = findAttr(attrs, "style").orEmpty()
            val type = findAttr(attrs, "type") ?: listStyleType(style) ?: "1"
            val start = findAttr(attrs, "start")?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val items = LIST_ITEM_REGEX.findAll(inner)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }
                .toList()

            if (items.isEmpty()) {
                inner
            } else {
                items.mapIndexed { index, item ->
                    "${orderedMarker(start + index, type)}&nbsp;$item"
                }.joinToString("<br>")
            }
        }

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

    private fun specialLinkTag(scheme: String, url: String, label: String): String =
        "<a href=\"${htmlAttr(scheme + encodePayload(url))}\">[${htmlText(label)}]</a>"

    private fun findAttr(source: String, name: String): String? {
        val regex = Regex(
            "\\b${Regex.escape(name)}\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>`]+))",
            RegexOption.IGNORE_CASE
        )
        val match = regex.find(source) ?: return null
        for (i in 1..3) {
            val value = match.groupValues.getOrNull(i)
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun protectRawTextBlocks(html: String): ProtectedHtml {
        val blocks = ArrayList<String>()
        val result = RAW_TEXT_TAG_REGEX.replace(html) { match ->
            val token = "$RAW_TEXT_TOKEN_PREFIX${blocks.size}__"
            blocks.add(match.value)
            token
        }
        return ProtectedHtml(result, blocks)
    }

    private fun restoreRawTextBlocks(html: String, blocks: List<String>): String {
        var result = html
        blocks.forEachIndexed { index, block ->
            result = result.replace("$RAW_TEXT_TOKEN_PREFIX${index}__", block)
        }
        return result
    }

    private fun stripBlockTableTags(html: String): String =
        html.replace(TABLE_ROW_OPEN_CLOSE_REGEX, "<br>")
            .replace(TABLE_CELL_OPEN_REGEX, "")
            .replace(TABLE_CELL_CLOSE_REGEX, "&nbsp;&nbsp;&nbsp;&nbsp;")

    private fun listStyleType(style: String): String? {
        val match = LIST_STYLE_TYPE_REGEX.find(style) ?: return null
        return match.groupValues[1].trim()
    }

    private fun orderedMarker(value: Int, type: String): String {
        val raw = type.trim()
        val normalized = raw.lowercase()
        return when (normalized) {
            "a" -> "${alphaNumber(value, uppercase = raw == "A")}."
            "lower-alpha", "lower-latin" -> "${alphaNumber(value, uppercase = false)}."
            "upper-alpha", "upper-latin" -> "${alphaNumber(value, uppercase = true)}."
            "i" -> if (raw == "I") "${romanNumber(value)}." else "${romanNumber(value).lowercase()}."
            "lower-roman" -> "${romanNumber(value).lowercase()}."
            "upper-roman" -> "${romanNumber(value)}."
            "cjk-ideographic", "chinese", "chinese-informal" -> "${chineseNumber(value)}、"
            "decimal-leading-zero" -> "%02d.".format(value)
            else -> "$value."
        }
    }

    private fun alphaNumber(value: Int, uppercase: Boolean): String {
        var n = value.coerceAtLeast(1)
        val chars = StringBuilder()
        while (n > 0) {
            n--
            chars.append(('A'.code + n % 26).toChar())
            n /= 26
        }
        val result = chars.reverse().toString()
        return if (uppercase) result else result.lowercase()
    }

    private fun romanNumber(value: Int): String {
        var n = value.coerceIn(1, 3999)
        val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val result = StringBuilder()
        for (i in values.indices) {
            while (n >= values[i]) {
                result.append(symbols[i])
                n -= values[i]
            }
        }
        return result.toString()
    }

    private fun chineseNumber(value: Int): String {
        val digits = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
        if (value in 1..10) return if (value == 10) "十" else digits[value]
        if (value in 11..19) return "十${digits[value % 10]}"
        if (value in 20..99) {
            val ten = value / 10
            val one = value % 10
            return "${digits[ten]}十${if (one == 0) "" else digits[one]}"
        }
        return value.toString()
    }

    private fun encodePayload(value: String): String =
        Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )

    private fun decodePayload(value: String): String = try {
        String(
            Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            Charsets.UTF_8
        )
    } catch (e: Exception) {
        ""
    }

    private fun htmlAttr(value: String): String = TextUtils.htmlEncode(value)

    private fun htmlText(value: String): String = TextUtils.htmlEncode(value)

    private data class ProtectedHtml(
        val html: String,
        val blocks: List<String>
    )

    private class RichTagHandler : Html.TagHandler {

        override fun handleTag(opening: Boolean, tag: String, output: Editable, xmlReader: XMLReader) {
            when (tag.lowercase()) {
                "code" -> if (opening) start(output, tag) else end(output, tag) { start, end ->
                    output.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(BackgroundColorSpan(0x11000000), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "pre" -> {
                    if (opening) {
                        ensureNewLine(output)
                        start(output, tag)
                    } else {
                        end(output, tag) { start, end ->
                            output.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            output.setSpan(BackgroundColorSpan(0x11000000), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        ensureNewLine(output)
                    }
                }
                "mark" -> if (opening) start(output, tag) else end(output, tag) { start, end ->
                    output.setSpan(BackgroundColorSpan(0x33FFCC00), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "blockquote" -> {
                    if (opening) {
                        ensureNewLine(output)
                        start(output, tag)
                    } else {
                        end(output, tag) { start, end ->
                            output.setSpan(QuoteSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            output.setSpan(LeadingMarginSpan.Standard(24), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            output.setSpan(ForegroundColorSpan(Color.DKGRAY), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        ensureNewLine(output)
                    }
                }
                "del", "s", "strike" -> if (opening) start(output, tag) else end(output, tag) { start, end ->
                    output.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "sup" -> if (opening) start(output, tag) else end(output, tag) { start, end ->
                    output.setSpan(SuperscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "sub" -> if (opening) start(output, tag) else end(output, tag) { start, end ->
                    output.setSpan(SubscriptSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    output.setSpan(RelativeSizeSpan(0.75f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                "kbd", "samp", "var" -> if (opening) start(output, tag) else end(output, tag) { start, end ->
                    output.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        private fun start(output: Editable, tag: String) {
            output.setSpan(TagMark(tag), output.length, output.length, Spanned.SPAN_MARK_MARK)
        }

        private fun end(output: Editable, tag: String, apply: (start: Int, end: Int) -> Unit) {
            val mark = lastMark(output, tag) ?: return
            val start = output.getSpanStart(mark)
            val end = output.length
            output.removeSpan(mark)
            if (start >= 0 && end > start) {
                apply(start, end)
            }
        }

        private fun lastMark(output: Editable, tag: String): TagMark? {
            val marks = output.getSpans(0, output.length, TagMark::class.java)
            for (i in marks.indices.reversed()) {
                if (marks[i].tag == tag) return marks[i]
            }
            return null
        }

        private fun ensureNewLine(output: Editable) {
            if (output.isNotEmpty() && output.last() != '\n') {
                output.append('\n')
            }
        }

        private data class TagMark(val tag: String)
    }

    // endregion

    companion object {
        private const val SPECIAL_AUDIO = "audio"
        private const val SPECIAL_EMBED = "embed"
        private const val AUDIO_SCHEME = "htmlaudio://"
        private const val EMBED_SCHEME = "htmlembed://"
        private const val RAW_TEXT_TOKEN_PREFIX = "__HTML_TEXT_VIEW_RAW_"

        private val REGEX_OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        private val VIDEO_TAG_REGEX = Regex("<video([^>]*)>(.*?)</video>", REGEX_OPTS)
        private val VIDEO_SELF_CLOSING_REGEX = Regex("<video([^>]*)/>", REGEX_OPTS)
        private val AUDIO_TAG_REGEX = Regex("<audio([^>]*)>(.*?)</audio>", REGEX_OPTS)
        private val AUDIO_SELF_CLOSING_REGEX = Regex("<audio([^>]*)/>", REGEX_OPTS)
        private val IFRAME_TAG_REGEX = Regex("<iframe([^>]*)>.*?</iframe>", REGEX_OPTS)
        private val EMBED_TAG_REGEX = Regex("<embed([^>]*)/?>", REGEX_OPTS)
        private val OBJECT_TAG_REGEX = Regex("<object([^>]*)>(.*?)</object>", REGEX_OPTS)
        private val ORDERED_LIST_REGEX = Regex("<ol([^>]*)>(.*?)</ol>", REGEX_OPTS)
        private val LIST_OPEN_REGEX = Regex("<[ou]l\\b", RegexOption.IGNORE_CASE)
        private val LIST_ITEM_REGEX = Regex("<li[^>]*>(.*?)</li>", REGEX_OPTS)
        private val LIST_STYLE_TYPE_REGEX = Regex("list-style-type\\s*:\\s*([^;]+)", RegexOption.IGNORE_CASE)
        private val TABLE_TAG_REGEX = Regex("<table[^>]*>(.*?)</table>", REGEX_OPTS)
        private val TABLE_ROW_REGEX = Regex("<tr[^>]*>(.*?)</tr>", REGEX_OPTS)
        private val TABLE_CELL_REGEX = Regex("<t[dh][^>]*>(.*?)</t[dh]>", REGEX_OPTS)
        private val TABLE_ROW_OPEN_CLOSE_REGEX = Regex("</?tr[^>]*>", RegexOption.IGNORE_CASE)
        private val TABLE_CELL_OPEN_REGEX = Regex("<t[dh][^>]*>", RegexOption.IGNORE_CASE)
        private val TABLE_CELL_CLOSE_REGEX = Regex("</t[dh]>", RegexOption.IGNORE_CASE)
        private val RAW_TEXT_TAG_REGEX = Regex("<(pre|code)\\b[^>]*>.*?</\\1>", REGEX_OPTS)

        private val BLOCK_DOLLAR_REGEX = Regex("\\$\\$(.+?)\\$\\$", RegexOption.DOT_MATCHES_ALL)
        private val BLOCK_BRACKET_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
        private val INLINE_PAREN_REGEX = Regex("\\\\\\((.+?)\\\\\\)", RegexOption.DOT_MATCHES_ALL)
        private val INLINE_DOLLAR_REGEX = Regex("(?<!\\\\)\\\$(?=\\S)(.+?)(?<![\\s\\\\])\\\$(?!\\d)")
    }
}
