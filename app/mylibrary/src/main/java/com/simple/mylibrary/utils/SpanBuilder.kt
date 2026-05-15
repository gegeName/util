package com.simple.mylibrary.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.Px
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.simple.mylibrary.R
import kotlin.math.abs

/**
 * 链式 Span 构建器。支持两种使用模式：
 *
 * 1) **拼接模式**：客户端已知每个片段，按顺序 append。
 * ```
 * SpanBuilder.with(ctx)
 *     .append("张三").color(Color.RED).bold().onClick { ... }
 *     .append(" 送出 ")
 *     .image(R.drawable.ic_gift, 24, 24).onClick { ... }
 *     .append(" x10").color(Color.YELLOW).underline()
 *     .into(tvMsg)
 * ```
 *
 * 2) **服务端整段文案模式**：先 setText 装载，再按"子串/正则/范围/占位符"定位后施加样式。
 * ```
 * SpanBuilder.with(ctx)
 *     .setText("李四 just sent [gift] x10 to LiveRoom")
 *     .find("李四").color(Color.RED).bold().onClick { ... }
 *     .find("LiveRoom").color(Color.CYAN).italic()
 *     .findRegex(Regex("x\\d+")).color(Color.YELLOW)
 *     .replaceWithImage("[gift]", R.drawable.ic_gift, 24, 24)
 *     .into(tvMsg)
 * ```
 *
 * 3）build 模式，不适合有图片的情况：
 * ```
 * val ssb = SpanBuilder.with(ctx)
 *     .append("王五").color(Color.RED).bold()
 *     .build()
 * tvMsg.text = ssb
 * ```
 */
class SpanBuilder private constructor(private val context: Context) {

    private val ssb = SpannableStringBuilder()

    /** 当前操作片段集合：每个 Pair 表示 [start, endExclusive)。所有样式方法对其中每段生效。 */
    private var segments: List<Pair<Int, Int>> = emptyList()
    private val pendingImageLoads = mutableListOf<PendingImageLoad>()

    /** 等待 Glide 加载完成后再包边框的配置，key = placeholder span 实例 */
    private val pendingImageBorders = mutableMapOf<CenterAlignImageSpan, ImageBorderConfig>()

    /** glow() 使用了 BlurMaskFilter，需要关闭硬件加速；into() 时自动设置 LAYER_TYPE_SOFTWARE。 */
    private var needsSoftwareLayer = false

    /**
     * 文字片段经 [marginPx] 上下平移后所需的"额外行垂直空间"（px，取所有调用中的最大 |top - bottom|）。
     */
    var extraVerticalPaddingPx: Int = 0
        private set

    private data class PendingImageLoad(
        val url: String,
        val placeholder: CenterAlignImageSpan,
        val width: Int,
        val height: Int,
        val circle: Boolean,
    )

    /** 图片边框配置。gradientColors 非空时使用渐变边框，否则使用纯色边框。 */
    private data class ImageBorderConfig(
        val borderWidth: Float,
        val borderColor: Int,
        val cornerRadius: Float,
        val gradientColors: IntArray? = null,
        val gradientVertical: Boolean = false,
    )

    companion object {
        @JvmStatic
        fun with(context: Context): SpanBuilder = SpanBuilder(context)
    }

    // ============================== 拼接模式 ==============================

    fun append(text: CharSequence): SpanBuilder {
        val start = ssb.length
        ssb.append(text)
        segments = listOf(start to ssb.length)
        return this
    }

    fun appendLine(text: CharSequence = ""): SpanBuilder {
        val start = ssb.length
        ssb.append(text)
        val textEnd = ssb.length
        ssb.append("\n")
        segments = if (textEnd > start) listOf(start to textEnd) else emptyList()
        return this
    }

    fun image(@DrawableRes resId: Int, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return this
        return image(drawable, width, height)
    }

    fun image(drawable: Drawable, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder {
        val w = if (width > 0) width else drawable.intrinsicWidth.coerceAtLeast(1)
        val h = if (height > 0) height else drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        ssb.setSpan(CenterAlignImageSpan(drawable), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        segments = listOf(start to end)
        return this
    }

    fun image(bitmap: Bitmap, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder =
        image(bitmap.toDrawable(context.resources), width, height)

    fun image(url: String, @Px width: Int, @Px height: Int, circle: Boolean = false): SpanBuilder {
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        val placeholderSpan = CenterAlignImageSpan(transparentPlaceholder(width, height))
        ssb.setSpan(placeholderSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        pendingImageLoads.add(PendingImageLoad(url, placeholderSpan, width, height, circle))
        segments = listOf(start to end)
        return this
    }

    // ============================== 服务端文案模式 ==============================

    fun setText(text: CharSequence): SpanBuilder {
        ssb.clear()
        ssb.append(text)
        segments = listOf(0 to ssb.length)
        return this
    }

    fun find(keyword: String, ignoreCase: Boolean = false): SpanBuilder {
        if (keyword.isEmpty()) { segments = emptyList(); return this }
        val idx = ssb.toString().indexOf(keyword, ignoreCase = ignoreCase)
        segments = if (idx >= 0) listOf(idx to idx + keyword.length) else emptyList()
        return this
    }

    fun findAll(keyword: String, ignoreCase: Boolean = false): SpanBuilder {
        if (keyword.isEmpty()) { segments = emptyList(); return this }
        val list = mutableListOf<Pair<Int, Int>>()
        val src = ssb.toString()
        var from = 0
        while (true) {
            val idx = src.indexOf(keyword, from, ignoreCase)
            if (idx < 0) break
            list.add(idx to idx + keyword.length)
            from = idx + keyword.length
        }
        segments = list
        return this
    }

    fun findRegex(regex: Regex): SpanBuilder {
        segments = regex.findAll(ssb).map { it.range.first to it.range.last + 1 }.toList()
        return this
    }

    fun range(start: Int, endExclusive: Int): SpanBuilder {
        val s = start.coerceIn(0, ssb.length)
        val e = endExclusive.coerceIn(s, ssb.length)
        segments = if (s < e) listOf(s to e) else emptyList()
        return this
    }

    fun all(): SpanBuilder {
        segments = if (ssb.isNotEmpty()) listOf(0 to ssb.length) else emptyList()
        return this
    }

    fun replaceWithImage(
        placeholder: String,
        @DrawableRes resId: Int,
        @Px width: Int,
        @Px height: Int,
    ): SpanBuilder {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return this
        return replaceWithImage(placeholder, drawable, width, height)
    }

    fun replaceWithImage(
        placeholder: String,
        drawable: Drawable,
        @Px width: Int = -1,
        @Px height: Int = -1,
    ): SpanBuilder {
        if (placeholder.isEmpty()) return this
        val w = if (width > 0) width else drawable.intrinsicWidth.coerceAtLeast(1)
        val h = if (height > 0) height else drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        val newSegs = replacePlaceholderWith(placeholder) { pos ->
            ssb.setSpan(CenterAlignImageSpan(drawable), pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        segments = newSegs
        return this
    }

    fun replaceWithImage(
        placeholder: String,
        url: String,
        @Px width: Int,
        @Px height: Int,
        circle: Boolean = false,
    ): SpanBuilder {
        if (placeholder.isEmpty()) return this
        val newSegs = replacePlaceholderWith(placeholder) { pos ->
            val placeholderSpan = CenterAlignImageSpan(transparentPlaceholder(width, height))
            ssb.setSpan(placeholderSpan, pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pendingImageLoads.add(PendingImageLoad(url, placeholderSpan, width, height, circle))
        }
        segments = newSegs
        return this
    }

    private fun replacePlaceholderWith(
        placeholder: String,
        onPlaced: (pos: Int) -> Unit,
    ): List<Pair<Int, Int>> {
        val src = ssb.toString()
        val originalPositions = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = src.indexOf(placeholder, from)
            if (idx < 0) break
            originalPositions.add(idx)
            from = idx + placeholder.length
        }
        val shrinkPerHit = placeholder.length - 1
        val newPositions = mutableListOf<Int>()
        originalPositions.forEachIndexed { i, origPos ->
            val pos = origPos - i * shrinkPerHit
            ssb.replace(pos, pos + placeholder.length, " ")
            onPlaced(pos)
            newPositions.add(pos)
        }
        return newPositions.map { it to it + 1 }
    }

    // ============================== 样式 ==============================

    fun color(@ColorInt color: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun backgroundColor(@ColorInt color: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(BackgroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 给当前片段叠加线性渐变色（文字前景）。
     * 可与 [stroke] / [glow] 叠加，三者合用时内部共享同一个 [TextDecorationSpan]。
     *
     * @param colors    渐变颜色数组，长度 ≥ 2
     * @param positions 各颜色在 [0,1] 上的分布位置；null = 自动等分
     * @param vertical  true=自上而下；false=自左到右（默认）
     */
    fun gradientColor(
        colors: IntArray,
        positions: FloatArray? = null,
        vertical: Boolean = false,
    ): SpanBuilder {
        require(colors.size >= 2) { "gradientColor needs at least 2 colors" }
        require(positions == null || positions.size == colors.size) {
            "positions.size must equal colors.size"
        }
        return applyOrMergeDecoration { existing ->
            existing?.withGradient(colors, positions, vertical)
                ?: TextDecorationSpan().withGradient(colors, positions, vertical)
        }
    }

    /** [gradientColor] 的二色便捷重载。 */
    fun gradientColor(
        @ColorInt startColor: Int,
        @ColorInt endColor: Int,
        vertical: Boolean = false,
    ): SpanBuilder = gradientColor(intArrayOf(startColor, endColor), null, vertical)

    /**
     * 给当前片段文字添加描边（边框）。
     * 可与 [gradientColor] / [glow] 叠加使用。
     *
     * @param color       描边颜色
     * @param strokeWidth 描边宽度 px；描边沿字形轮廓向内外各扩展 strokeWidth/2
     */
    fun stroke(
        @ColorInt color: Int,
        @Px strokeWidth: Float,
    ): SpanBuilder = applyOrMergeDecoration { existing ->
        existing?.withStroke(color, strokeWidth)
            ?: TextDecorationSpan().withStroke(color, strokeWidth)
    }

    /**
     * 给当前片段文字添加发光效果（BlurMaskFilter）。
     * 可与 [gradientColor] / [stroke] 叠加使用。
     * 注意：发光依赖 BlurMaskFilter，[into] 会自动为 TextView 设置 LAYER_TYPE_SOFTWARE。
     *
     * @param color  发光颜色
     * @param radius 发光半径 px，越大扩散范围越宽
     */
    fun glow(
        @ColorInt color: Int,
        @Px radius: Float,
    ): SpanBuilder {
        needsSoftwareLayer = true
        return applyOrMergeDecoration { existing ->
            existing?.withGlow(color, radius)
                ?: TextDecorationSpan().withGlow(color, radius)
        }
    }

    private fun applyOrMergeDecoration(
        update: (TextDecorationSpan?) -> TextDecorationSpan,
    ): SpanBuilder = applyEach { s, e ->
        val existing = ssb.getSpans(s, e, TextDecorationSpan::class.java)
            .firstOrNull { ssb.getSpanStart(it) == s && ssb.getSpanEnd(it) == e }
        val newSpan = update(existing)
        // existing 就地修改后返回的是同一个对象，不需要 remove+set；只有 existing==null 时才是新建
        if (existing == null) {
            ssb.setSpan(newSpan, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun bold(): SpanBuilder = applyStyle(Typeface.BOLD)
    fun italic(): SpanBuilder = applyStyle(Typeface.ITALIC)
    fun boldItalic(): SpanBuilder = applyStyle(Typeface.BOLD_ITALIC)

    private fun applyStyle(style: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun underline(): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun strikethrough(): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun sizePx(@Px sizePx: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(AbsoluteSizeSpan(sizePx), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    fun maxLengthMiddle(maxChars: Int, ellipsis: String = "..."): SpanBuilder {
        if (maxChars <= 0) return this
        val sorted = segments.sortedByDescending { it.first }
        val updated = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { (s, e) ->
            val segLen = e - s
            if (segLen <= maxChars) { updated.add(s to e); return@forEach }
            val headLen = (maxChars + 1) / 2
            val tailLen = maxChars - headLen
            val cutStart = s + headLen
            val cutEnd = e - tailLen
            if (cutStart >= cutEnd) { updated.add(s to e); return@forEach }
            val covering = ssb.getSpans(s, e, Any::class.java).mapNotNull { sp ->
                val ss = ssb.getSpanStart(sp); val se = ssb.getSpanEnd(sp)
                if (se >= e) Triple(sp, ss, ssb.getSpanFlags(sp)) else null
            }
            ssb.replace(cutStart, cutEnd, ellipsis)
            val newE = s + headLen + ellipsis.length + tailLen
            covering.forEach { (sp, ss, flags) -> ssb.removeSpan(sp); ssb.setSpan(sp, ss, newE, flags) }
            shiftUpdated(updated, fromExclusive = e, by = newE - e)
            updated.add(s to newE)
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    fun maxLength(maxChars: Int, ellipsis: String = "..."): SpanBuilder {
        if (maxChars <= 0) return this
        val sorted = segments.sortedByDescending { it.first }
        val updated = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { (s, e) ->
            val segLen = e - s
            if (segLen <= maxChars) { updated.add(s to e); return@forEach }
            val covering = ssb.getSpans(s, e, Any::class.java).mapNotNull { sp ->
                val ss = ssb.getSpanStart(sp); val se = ssb.getSpanEnd(sp)
                if (se >= e) Triple(sp, ss, ssb.getSpanFlags(sp)) else null
            }
            ssb.replace(s + maxChars, e, ellipsis)
            val newE = s + maxChars + ellipsis.length
            covering.forEach { (sp, ss, flags) -> ssb.removeSpan(sp); ssb.setSpan(sp, ss, newE, flags) }
            shiftUpdated(updated, fromExclusive = e, by = newE - e)
            updated.add(s to newE)
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    /**
     * 给当前图片片段添加纯色边框。必须在 [image] 之后调用。
     *
     * @param color        边框颜色
     * @param borderWidth  边框宽度 px
     * @param cornerRadius 边框圆角半径 px，0 = 直角
     */
    fun imageBorder(
        @ColorInt color: Int,
        @Px borderWidth: Float,
        @Px cornerRadius: Float = 0f,
    ): SpanBuilder = applyImageBorder(
        ImageBorderConfig(borderWidth, color, cornerRadius)
    )

    /**
     * 给当前图片片段添加渐变边框（二色）。必须在 [image] 之后调用。
     *
     * @param startColor   渐变起始色
     * @param endColor     渐变结束色
     * @param borderWidth  边框宽度 px
     * @param cornerRadius 边框圆角半径 px，0 = 直角
     * @param vertical     true=自上而下；false=自左到右（默认）
     */
    fun imageBorderGradient(
        @ColorInt startColor: Int,
        @ColorInt endColor: Int,
        @Px borderWidth: Float,
        @Px cornerRadius: Float = 0f,
        vertical: Boolean = false,
    ): SpanBuilder = applyImageBorder(
        ImageBorderConfig(borderWidth, startColor, cornerRadius, intArrayOf(startColor, endColor), vertical)
    )

    /**
     * 给当前图片片段添加渐变边框（多色）。必须在 [image] 之后调用。
     */
    fun imageBorderGradient(
        colors: IntArray,
        @Px borderWidth: Float,
        @Px cornerRadius: Float = 0f,
        vertical: Boolean = false,
    ): SpanBuilder {
        require(colors.size >= 2) { "imageBorderGradient needs at least 2 colors" }
        return applyImageBorder(
            ImageBorderConfig(borderWidth, colors[0], cornerRadius, colors, vertical)
        )
    }

    private fun applyImageBorder(config: ImageBorderConfig): SpanBuilder = applyEach { s, e ->
        val imageSpans = ssb.getSpans(s, e, CenterAlignImageSpan::class.java)
        if (imageSpans.isEmpty()) return@applyEach
        imageSpans.forEach { span ->
            if (pendingImageLoads.any { it.placeholder === span }) {
                pendingImageBorders[span] = config
                return@forEach
            }
            val orig = span.drawable
            val wrapped = BorderedImageDrawable(orig, config)
            wrapped.bounds = orig.bounds
            val newSpan = CenterAlignImageSpan(wrapped)
            ssb.removeSpan(span)
            ssb.setSpan(newSpan, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    fun marginPx(
        @Px left: Int = 0,
        @Px top: Int = 0,
        @Px right: Int = 0,
        @Px bottom: Int = 0,
    ): SpanBuilder {
        if (left <= 0 && top <= 0 && right <= 0 && bottom <= 0) return this
        val sorted = segments.sortedByDescending { it.first }
        val updated = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { (s, e) ->
            val imageSpans = ssb.getSpans(s, e, CenterAlignImageSpan::class.java)
            if (imageSpans.isNotEmpty()) {
                imageSpans.forEach { span ->
                    val orig = span.drawable
                    val bounds = orig.bounds
                    val w = bounds.width().coerceAtLeast(1)
                    val h = bounds.height().coerceAtLeast(1)
                    val inset = InsetDrawable(orig, left, top, right, bottom)
                    inset.setBounds(0, 0, w + left + right, h + top + bottom)
                    ssb.removeSpan(span)
                    ssb.setSpan(CenterAlignImageSpan(inset), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                updated.add(s to e)
            } else {
                val curS = s
                var curE = e
                if (right > 0) {
                    ssb.insert(curE, " ")
                    ssb.setSpan(BlankWidthSpan(right), curE, curE + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    shiftUpdated(updated, fromExclusive = curE, by = 1)
                    curE += 1
                }
                if (left > 0) {
                    ssb.insert(curS, " ")
                    ssb.setSpan(BlankWidthSpan(left), curS, curS + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    shiftUpdated(updated, fromExclusive = curS, by = 1)
                    curE += 1
                }
                val shiftDown = top - bottom
                if (shiftDown != 0) {
                    ssb.setSpan(VerticalShiftSpan(shiftDown), curS, curE, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    extraVerticalPaddingPx = maxOf(extraVerticalPaddingPx, abs(shiftDown))
                }
                updated.add(curS to curE)
            }
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    fun textVerticalMarginPx(@Px top: Int = 0, @Px bottom: Int = 0): SpanBuilder {
        val shiftDown = top - bottom
        if (shiftDown == 0 || ssb.isEmpty()) return this
        val len = ssb.length
        val imageSpans = ssb.getSpans(0, len, CenterAlignImageSpan::class.java)
        val imageRanges = imageSpans.map { ssb.getSpanStart(it) to ssb.getSpanEnd(it) }.sortedBy { it.first }
        var cursor = 0
        var placedAny = false
        for ((s, e) in imageRanges) {
            if (cursor < s) {
                ssb.setSpan(VerticalShiftSpan(shiftDown), cursor, s, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                placedAny = true
            }
            cursor = maxOf(cursor, e)
        }
        if (cursor < len) {
            ssb.setSpan(VerticalShiftSpan(shiftDown), cursor, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            placedAny = true
        }
        if (placedAny) extraVerticalPaddingPx = maxOf(extraVerticalPaddingPx, abs(shiftDown))
        return this
    }

    private fun shiftUpdated(list: MutableList<Pair<Int, Int>>, fromExclusive: Int, by: Int) {
        for (i in list.indices) {
            val (us, ue) = list[i]
            if (us >= fromExclusive) list[i] = (us + by) to (ue + by)
        }
    }

    fun onClick(
        underline: Boolean = false,
        @ColorInt overrideColor: Int? = null,
        listener: (View) -> Unit,
    ): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = listener(widget)
            override fun updateDrawState(ds: TextPaint) {
                overrideColor?.let { ds.color = it }
                ds.isUnderlineText = underline
            }
        }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private inline fun applyEach(block: (Int, Int) -> Unit): SpanBuilder {
        segments.forEach { (s, e) -> if (s < e) block(s, e) }
        return this
    }

    // ============================== 输出 ==============================

    fun build(): CharSequence = ssb

    fun into(textView: TextView) {
        textView.movementMethod = LinkMovementMethod.getInstance()
        applyExtraVerticalPadding(textView)
        if (needsSoftwareLayer && !textView.isInEditMode) {
            textView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
        val tagKey = R.id.span_builder_load_token
        textView.setTag(tagKey, ssb)
        if (pendingImageLoads.isEmpty()) {
            textView.text = ssb
            return
        }
        var initialTextSet = false
        pendingImageLoads.forEach { load ->
            var options = RequestOptions().override(load.width, load.height)
            if (load.circle) options = options.circleCrop()
            Glide.with(textView).asDrawable().load(load.url).apply(options)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                        if (textView.getTag(tagKey) !== ssb) return
                        val curStart = ssb.getSpanStart(load.placeholder)
                        val curEnd = ssb.getSpanEnd(load.placeholder)
                        if (curStart !in 0 until curEnd || curEnd > ssb.length) return
                        resource.setBounds(0, 0, load.width, load.height)
                        ssb.removeSpan(load.placeholder)
                        val borderConfig = pendingImageBorders.remove(load.placeholder)
                        val finalDrawable = if (borderConfig != null) {
                            BorderedImageDrawable(resource, borderConfig).also {
                                it.setBounds(0, 0, load.width, load.height)
                            }
                        } else {
                            resource
                        }
                        ssb.setSpan(CenterAlignImageSpan(finalDrawable), curStart, curEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        if (initialTextSet) textView.text = ssb
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        }
        initialTextSet = true
        textView.text = ssb
    }

    private fun transparentPlaceholder(w: Int, h: Int): Drawable =
        Color.TRANSPARENT.toDrawable().apply { setBounds(0, 0, w, h) }

    private fun applyExtraVerticalPadding(textView: TextView) {
        val tagKey = R.id.span_builder_added_line_spacing
        val previousAdded = (textView.getTag(tagKey) as? Int) ?: 0
        if (previousAdded == 0 && extraVerticalPaddingPx == 0) return
        val baseExtra = textView.lineSpacingExtra - previousAdded
        textView.includeFontPadding = true
        textView.setLineSpacing(baseExtra + extraVerticalPaddingPx.toFloat(), textView.lineSpacingMultiplier)
        textView.setTag(tagKey, extraVerticalPaddingPx.takeIf { it != 0 })
    }


    private class VerticalShiftSpan(@Px private val shiftDown: Int) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.baselineShift += shiftDown }
        override fun updateMeasureState(tp: TextPaint) { tp.baselineShift += shiftDown }
    }

    /**
     * 统一文字装饰 Span：渐变填充 + 描边（边框）+ 发光，可单独或任意组合使用。
     *
     * 绘制顺序：发光 → 描边 → 填充（渐变 / 原色）
     *
     * 组合示例：
     * ```
     * .append("文字")
     *     .gradientColor(Color.RED, Color.YELLOW)   // 渐变
     *     .stroke(Color.WHITE, 3f)                   // 白色描边
     *     .glow(Color.RED, 12f)                      // 红色发光
     * ```
     */
    internal class TextDecorationSpan : ReplacementSpan() {

        internal var gradientColors: IntArray? = null
        internal var gradientPositions: FloatArray? = null
        internal var gradientVertical: Boolean = false
        // 描边
        internal var strokeColor: Int = Color.TRANSPARENT
        internal var strokeWidthPx: Float = 0f
        // 发光
        internal var glowColor: Int = Color.TRANSPARENT
        internal var glowRadiusPx: Float = 0f

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
        }

        private fun obtainShader(width: Float, top: Int, bottom: Int): LinearGradient {
            val cached = cachedShader
            val sizeChanged = if (gradientVertical) {
                top != cachedShaderTop || bottom != cachedShaderBottom
            } else {
                width != cachedShaderWidth
            }
            if (cached != null && !sizeChanged) return cached
            val shader = if (gradientVertical) {
                LinearGradient(
                    0f, top.toFloat(), 0f, bottom.toFloat(),
                    gradientColors!!, gradientPositions, Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    0f, 0f, width, 0f,
                    gradientColors!!, gradientPositions, Shader.TileMode.CLAMP
                )
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
            if (cachedBlurRadius != radius) cachedBlurFilter = null  // 半径变了需重建 filter
            glowColor = color
            glowRadiusPx = radius
            return this
        }
    }

    private class BlankWidthSpan(@Px private val width: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int = width
        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {}
    }

    /**
     * 在原始 Drawable 外围绘制边框的包装 Drawable。
     * bounds = 原图 bounds（不扩大尺寸），边框向内绘制，不侵占外部布局空间。
     *
     * Paint / RectF 放在 companion object（静态）：
     * draw() 在主线程单线程执行，多个实例不会并发，共享同一组对象，
     * 避免每次 onBindViewHolder 创建新实例时反复 new Paint/RectF 导致内存抖动。
     */
    private class BorderedImageDrawable(
        private val inner: Drawable,
        private val config: ImageBorderConfig,
    ) : Drawable() {

        private var cachedShader: LinearGradient? = null
        private var cachedW = 0
        private var cachedH = 0

        override fun draw(canvas: Canvas) {
            val b = bounds
            inner.bounds = b
            inner.draw(canvas)

            val half = config.borderWidth / 2f
            sBorderRectF.set(b.left + half, b.top + half, b.right - half, b.bottom - half)

            sBorderPaint.strokeWidth = config.borderWidth

            if (config.gradientColors != null) {
                val w = b.width(); val h = b.height()
                if (cachedShader == null || cachedW != w || cachedH != h) {
                    cachedShader = if (config.gradientVertical) {
                        LinearGradient(0f, 0f, 0f, h.toFloat(), config.gradientColors, null, Shader.TileMode.CLAMP)
                    } else {
                        LinearGradient(0f, 0f, w.toFloat(), 0f, config.gradientColors, null, Shader.TileMode.CLAMP)
                    }
                    cachedW = w; cachedH = h
                }
                sBorderPaint.shader = cachedShader
            } else {
                sBorderPaint.shader = null
                sBorderPaint.color = config.borderColor
            }

            if (config.cornerRadius > 0f) {
                canvas.drawRoundRect(sBorderRectF, config.cornerRadius, config.cornerRadius, sBorderPaint)
            } else {
                canvas.drawRect(sBorderRectF, sBorderPaint)
            }
        }

        override fun getIntrinsicWidth(): Int = inner.intrinsicWidth
        override fun getIntrinsicHeight(): Int = inner.intrinsicHeight
        override fun setAlpha(alpha: Int) { inner.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { inner.colorFilter = cf }
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun onBoundsChange(bounds: Rect) { cachedShader = null }

        companion object {
            private val sBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
            }
            private val sBorderRectF = RectF()
        }
    }
}
