package com.simple.mylibrary.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
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
 * 3）build模式，不适合有图片的情况，
 *  val ssb=SpanBuilder.with(ctx)
 *      .append("王五").color(Color.RED).bold().onClick { ... }
 *      .append(" 送出 ")
 *      .image(R.drawable.ic_gift, 24, 24).onClick { ... }
 *      .append(" x10").color(Color.YELLOW).underline()
 *      .build()
 *  tvMsg.text=ssb
 */
class SpanBuilder private constructor(private val context: Context) {

    private val ssb = SpannableStringBuilder()

    /** 当前操作片段集合：每个 Pair 表示 [start, endExclusive)。所有样式方法对其中每段生效。 */
    private var segments: List<Pair<Int, Int>> = emptyList()
    private val pendingImageLoads = mutableListOf<PendingImageLoad>()

    /**
     * 文字片段经 [marginPx] 上下平移后所需的"额外行垂直空间"（px，取所有调用中的最大 |top - bottom|）。
     *
     * **为什么需要这个值**：
     * - CenterAlignImageSpan 会根据图片高度撑高行高让文字垂直居中，但当 PNG 顶/底带透明边时，
     *   图片视觉中心与几何中心不一致，导致文字与图片"看着不居中"。
     * - 用 `marginPx(top = ..., bottom = ...)` 通过 baselineShift 把文字整体上/下平移可视觉对齐，
     *   但 baselineShift 只是平移、不会撑行高，平移过大会超出 TextView 的自然 ascent/descent 被裁切。
     *
     * **怎么用**：
     * - 调用 [into] 时本类会自动累加该值到 `TextView.lineSpacingExtra` 上，并保持
     *   `includeFontPadding = true`，避免裁切，使用者无需手动处理。
     * - 若用 [build] 自行处理 CharSequence，可读此字段，自行给 TextView 增加 padding/lineSpacing。
     */
    var extraVerticalPaddingPx: Int = 0
        private set

    /**
     * 远程图待加载任务。用 [placeholder] 这个 Span 实例追踪当前位置，
     */
    private data class PendingImageLoad(
        val url: String,
        val placeholder: CenterAlignImageSpan,
        val width: Int,
        val height: Int,
        val circle: Boolean,
    )

    companion object {
        /**
         * 创建一个新的 SpanBuilder 实例。
         * @param context 用于解析 Drawable 资源与挂载 Glide 请求的上下文（推荐传 Activity / View.context）。
         */
        @JvmStatic
        fun with(context: Context): SpanBuilder = SpanBuilder(context)
    }

    // ============================== 拼接模式 ==============================

    /**
     * 在尾部追加一段文本，并把"当前片段"更新为新追加的范围（后续样式方法会作用在这段上）。
     * @param text 要追加的文本，可以是 String / SpannableString 等任意 CharSequence。
     */
    fun append(text: CharSequence): SpanBuilder {
        val start = ssb.length
        ssb.append(text)
        segments = listOf(start to ssb.length)
        return this
    }

    /**
     * 追加文本并在末尾补一个换行。
     * @param text 要追加的文本，默认空串（仅插入换行；此时无文字片段，后续样式调用静默无效）。
     */
    fun appendLine(text: CharSequence = ""): SpanBuilder {
        val start = ssb.length
        ssb.append(text)
        val textEnd = ssb.length
        ssb.append("\n")
        segments = if (textEnd > start) listOf(start to textEnd) else emptyList()
        return this
    }

    /**
     * 添加本地 Drawable 资源图片。
     * @param resId 资源 ID。
     * @param width 显示宽度，单位 px；<=0 时使用图片原始宽度。
     * @param height 显示高度，单位 px；<=0 时使用图片原始高度。
     */
    fun image(@DrawableRes resId: Int, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return this
        return image(drawable, width, height)
    }

    /**
     * 添加 Drawable 图片。
     * @param drawable 要插入的 Drawable 对象。
     * @param width 显示宽度，单位 px；<=0 时使用 drawable.intrinsicWidth。
     * @param height 显示高度，单位 px；<=0 时使用 drawable.intrinsicHeight。
     */
    fun image(drawable: Drawable, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder {
        val w = if (width > 0) width else drawable.intrinsicWidth.coerceAtLeast(1)
        val h = if (height > 0) height else drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        ssb.setSpan(
            CenterAlignImageSpan(drawable),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        segments = listOf(start to end)
        return this
    }

    /**
     * 添加 Bitmap 图片（内部会包装成 BitmapDrawable）。
     * @param bitmap 要插入的位图。
     * @param width 显示宽度，单位 px；<=0 时使用 bitmap 原始宽度。
     * @param height 显示高度，单位 px；<=0 时使用 bitmap 原始高度。
     */
    fun image(bitmap: Bitmap, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder {
        return image(bitmap.toDrawable(context.resources), width, height)
    }

    /**
     * 添加远程 URL 图片。先插入透明占位 Drawable 撑出尺寸，加载完成后由 [into] 异步替换。
     * 必须配合 [into] 使用，否则不会触发实际下载。
     * @param url 图片远程地址。
     * @param width 显示宽度，单位 px（必须明确指定）。
     * @param height 显示高度，单位 px（必须明确指定）。
     * @param circle 是否裁剪为圆形，默认 false。
     */
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

    /**
     * 一次性装载完整文本（会清空已有内容）。后续通过 find / range / replaceWithImage 等方法定位子段施加样式。
     * @param text 要装载的整段文本（常用于服务端下发的完整文案）。
     */
    fun setText(text: CharSequence): SpanBuilder {
        ssb.clear()
        ssb.append(text)
        segments = listOf(0 to ssb.length)
        return this
    }

    /**
     * 定位首个匹配子串作为当前片段。未命中则当前片段置空，后续样式调用静默无效。
     * @param keyword 要查找的关键字（空串直接清空 segments）。
     * @param ignoreCase 是否忽略大小写，默认 false。
     */
    fun find(keyword: String, ignoreCase: Boolean = false): SpanBuilder {
        if (keyword.isEmpty()) {
            segments = emptyList()
            return this
        }
        val idx = ssb.toString().indexOf(keyword, ignoreCase = ignoreCase)
        segments = if (idx >= 0) listOf(idx to idx + keyword.length) else emptyList()
        return this
    }

    /**
     * 定位所有匹配子串作为当前片段集合。
     * @param keyword 要查找的关键字（空串直接清空 segments）。
     * @param ignoreCase 是否忽略大小写，默认 false。
     */
    fun findAll(keyword: String, ignoreCase: Boolean = false): SpanBuilder {
        if (keyword.isEmpty()) {
            segments = emptyList()
            return this
        }
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

    /**
     * 按正则定位所有匹配作为当前片段集合。
     * @param regex 正则表达式，例如 `Regex("x\\d+")` 匹配 "x10" / "x99"。
     */
    fun findRegex(regex: Regex): SpanBuilder {
        segments = regex.findAll(ssb).map { it.range.first to it.range.last + 1 }.toList()
        return this
    }

    /**
     * 直接指定范围作为当前片段。
     * @param start 起始下标（包含），自动 clamp 到 [0, ssb.length]。
     * @param endExclusive 结束下标（不包含），自动 clamp 到 [start, ssb.length]。
     */
    fun range(start: Int, endExclusive: Int): SpanBuilder {
        val s = start.coerceIn(0, ssb.length)
        val e = endExclusive.coerceIn(s, ssb.length)
        segments = if (s < e) listOf(s to e) else emptyList()
        return this
    }

    /** 整段文本作为当前片段（常用于先 setText 再统一施加默认样式）。 */
    fun all(): SpanBuilder {
        segments = if (ssb.isNotEmpty()) listOf(0 to ssb.length) else emptyList()
        return this
    }

    /**
     * 用本地图片替换文本中所有 placeholder 子串。替换后当前片段为这些图片位置（可继续 onClick）。
     * 例：服务端文案含 `[gift]` 占位符，可整段替换为礼物图片。
     * @param placeholder 要被替换的占位字符串（空串不做处理）。
     * @param resId 替换图的资源 ID。
     * @param width 显示宽度，单位 px。
     * @param height 显示高度，单位 px。
     */
    fun replaceWithImage(
        placeholder: String,
        @DrawableRes resId: Int,
        @Px width: Int,
        @Px height: Int,
    ): SpanBuilder {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return this
        return replaceWithImage(placeholder, drawable, width, height)
    }

    /**
     * 用 Drawable 替换文本中所有 placeholder 子串。
     * @param placeholder 要被替换的占位字符串（空串不做处理）。
     * @param drawable 替换图。
     * @param width 显示宽度，单位 px；<=0 时使用 drawable.intrinsicWidth。
     * @param height 显示高度，单位 px；<=0 时使用 drawable.intrinsicHeight。
     */
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
            ssb.setSpan(
                CenterAlignImageSpan(drawable),
                pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        segments = newSegs
        return this
    }

    /**
     * 用远程 URL 图片替换文本中所有 placeholder。必须配合 [into] 使用，否则不会触发实际下载。
     * @param placeholder 要被替换的占位字符串（空串不做处理）。
     * @param url 替换图的远程地址。
     * @param width 显示宽度，单位 px（必须明确指定）。
     * @param height 显示高度，单位 px（必须明确指定）。
     * @param circle 是否裁剪为圆形，默认 false。
     */
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

    /**
     * 内部辅助：把所有 placeholder 替换为单字符占位，替换处调用 onPlaced 写入 ImageSpan / 记录加载任务。
     */
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

    // ============================== 样式（对当前片段集合生效） ==============================

    /**
     * 设置文字颜色。
     * @param color ARGB int，例如 `Color.RED` 或 `"#FFD243".toColorInt()`。
     */
    fun color(@ColorInt color: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 设置文字背景色。
     * @param color ARGB int。
     */
    fun backgroundColor(@ColorInt color: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(BackgroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 给当前片段叠加线性渐变色（文字前景）。
     *
     * 实现走 [GradientTextSpan]（ReplacementSpan），在 draw 时按片段实际宽度/高度构造
     * [LinearGradient]，渐变起止精确对齐到文字范围（不受前置文本宽度影响）。
     *
     * **与其他样式的兼容性**：
     * - 仍生效：[bold] / [italic] / [sizePx]（修改 paint，会传到 ReplacementSpan.draw）
     * - 不再生效：[color] / [backgroundColor]（颜色被渐变覆盖）、[underline] / [strikethrough]
     *   （ReplacementSpan 接管绘制，不会自动画下/删除线）
     * - 仍生效：[onClick]（点击通过 ClickableSpan 命中测试，与绘制无关）
     *
     * @param colors    渐变颜色数组，长度 >=2，例如 `intArrayOf(0xFFFFEB3B.toInt(), 0xFFFF5722.toInt())`
     * @param positions 各颜色在 [0,1] 上的分布位置；传 null 自动等分；非 null 时长度必须与 colors 一致
     * @param vertical  true=自上而下（沿 y 轴）；false=自左到右（沿 x 轴，默认）
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
        return applyEach { s, e ->
            ssb.setSpan(
                GradientTextSpan(colors, positions, vertical),
                s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * [gradientColor] 的二色便捷重载。
     * @param startColor 起始颜色（左/上）
     * @param endColor   结束颜色（右/下）
     * @param vertical   true=自上而下；false=自左到右（默认）
     */
    fun gradientColor(
        @ColorInt startColor: Int,
        @ColorInt endColor: Int,
        vertical: Boolean = false,
    ): SpanBuilder = gradientColor(intArrayOf(startColor, endColor), null, vertical)

    /** 加粗。 */
    fun bold(): SpanBuilder = applyStyle(Typeface.BOLD)

    /** 斜体。 */
    fun italic(): SpanBuilder = applyStyle(Typeface.ITALIC)

    /** 加粗 + 斜体。 */
    fun boldItalic(): SpanBuilder = applyStyle(Typeface.BOLD_ITALIC)

    private fun applyStyle(style: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 下划线。 */
    fun underline(): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 删除线。 */
    fun strikethrough(): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 设置绝对字号。
     * @param sizePx 字号像素值（注意是 px，不是 sp；若要按 sp 设置请用 `SizeUtils.sp2px(...)` 转换）。
     */
    fun sizePx(@Px sizePx: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(AbsoluteSizeSpan(sizePx), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 限制当前片段文字长度，超出则在**中间**截断并插入省略号（保留头部与尾部，省略中间）。
     *
     * 示例：`"abcdefghij" + maxChars=6 + ellipsis="..."` → `"abc...hij"`。
     *
     * @param maxChars 最大保留字符数（不含 ellipsis 本身），头尾各分一半，奇数偏头部 1 位
     * @param ellipsis 中间插入的占位符，默认 "..."
     */
    fun maxLengthMiddle(maxChars: Int, ellipsis: String = "..."): SpanBuilder {
        if (maxChars <= 0) return this
        val sorted = segments.sortedByDescending { it.first }
        val updated = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { (s, e) ->
            val segLen = e - s
            if (segLen <= maxChars) {
                updated.add(s to e)
                return@forEach
            }
            val headLen = (maxChars + 1) / 2
            val tailLen = maxChars - headLen
            val cutStart = s + headLen
            val cutEnd = e - tailLen
            if (cutStart >= cutEnd) {
                updated.add(s to e)
                return@forEach
            }
            val covering = ssb.getSpans(s, e, Any::class.java).mapNotNull { sp ->
                val ss = ssb.getSpanStart(sp)
                val se = ssb.getSpanEnd(sp)
                if (se >= e) Triple(sp, ss, ssb.getSpanFlags(sp)) else null
            }
            ssb.replace(cutStart, cutEnd, ellipsis)
            val newE = s + headLen + ellipsis.length + tailLen
            covering.forEach { (sp, ss, flags) ->
                ssb.removeSpan(sp)
                ssb.setSpan(sp, ss, newE, flags)
            }
            shiftUpdated(updated, fromExclusive = e, by = newE - e)
            updated.add(s to newE)
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    /**
     * 限制当前片段文字长度，超出则截断并追加省略号。
     *
     * @param maxChars 最大保留字符数（不含 ellipsis 本身）
     * @param ellipsis 截断后追加的字符，默认 "..."
     */
    fun maxLength(maxChars: Int, ellipsis: String = "..."): SpanBuilder {
        if (maxChars <= 0) return this
        val sorted = segments.sortedByDescending { it.first }
        val updated = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { (s, e) ->
            val segLen = e - s
            if (segLen <= maxChars) {
                updated.add(s to e)
                return@forEach
            }
            val covering = ssb.getSpans(s, e, Any::class.java).mapNotNull { sp ->
                val ss = ssb.getSpanStart(sp)
                val se = ssb.getSpanEnd(sp)
                if (se >= e) Triple(sp, ss, ssb.getSpanFlags(sp)) else null
            }
            ssb.replace(s + maxChars, e, ellipsis)
            val newE = s + maxChars + ellipsis.length
            covering.forEach { (sp, ss, flags) ->
                ssb.removeSpan(sp)
                ssb.setSpan(sp, ss, newE, flags)
            }
            shiftUpdated(updated, fromExclusive = e, by = newE - e)
            updated.add(s to newE)
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    /**
     * 给当前片段添加上下左右 margin（单位 px）。
     *
     * 行为：
     * - **图片片段**：用 [InsetDrawable] 包裹原图，CenterAlignImageSpan 按新边界绘制，
     *   行高自动撑开，四方向间距均生效。
     * - **文字片段**：
     *   - 左右 margin → 插入零宽 [ReplacementSpan] 占位。
     *   - 上下 margin → 通过 baselineShift（[VerticalShiftSpan]）整体上下平移文字。
     *     常用场景：PNG 顶部/底部带透明边导致 CenterAlignImageSpan 视觉不居中时，
     *     用 `top` 让文字下移、`bottom` 让文字上移，对齐图片视觉中心。
     *     注意：baselineShift 不会撑高行高，只是平移；若需更大间距请同时调整 line spacing。
     *
     * 文本插入会让后续位置索引偏移，方法内部已自动同步 segments 集合，可继续链式调用。
     *
     * @param left 左侧间距 px，<=0 不生效。
     * @param top 上侧间距 px，<=0 不生效。图片：包入 inset；文字：baselineShift += top（文字下移）。
     * @param right 右侧间距 px，<=0 不生效。
     * @param bottom 下侧间距 px，<=0 不生效。图片：包入 inset；文字：baselineShift -= bottom（文字上移）。
     */
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
                    ssb.setSpan(
                        CenterAlignImageSpan(inset),
                        s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                updated.add(s to e)
            } else {
                val curS = s
                var curE = e
                if (right > 0) {
                    ssb.insert(curE, " ")
                    ssb.setSpan(
                        BlankWidthSpan(right),
                        curE, curE + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    shiftUpdated(updated, fromExclusive = curE, by = 1)
                    curE += 1
                }
                if (left > 0) {
                    ssb.insert(curS, " ")
                    ssb.setSpan(
                        BlankWidthSpan(left),
                        curS, curS + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    shiftUpdated(updated, fromExclusive = curS, by = 1)
                    curE += 1
                }
                val shiftDown = top - bottom
                if (shiftDown != 0) {
                    ssb.setSpan(
                        VerticalShiftSpan(shiftDown),
                        curS, curE, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    extraVerticalPaddingPx = maxOf(extraVerticalPaddingPx, abs(shiftDown))
                }
                updated.add(curS to curE)
            }
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    /**
     * 给整段中**所有文字片段**统一上下平移（跳过 CenterAlignImageSpan 占位区域，不影响图片）。
     *
     * @param top 上侧间距 px，<=0 不生效。baselineShift += top（文字下移）。
     * @param bottom 下侧间距 px，<=0 不生效。baselineShift -= bottom（文字上移）。
     */
    fun textVerticalMarginPx(@Px top: Int = 0, @Px bottom: Int = 0): SpanBuilder {
        val shiftDown = top - bottom
        if (shiftDown == 0 || ssb.isEmpty()) return this
        val len = ssb.length
        val imageSpans = ssb.getSpans(0, len, CenterAlignImageSpan::class.java)
        val imageRanges = imageSpans
            .map { ssb.getSpanStart(it) to ssb.getSpanEnd(it) }
            .sortedBy { it.first }
        var cursor = 0
        var placedAny = false
        for ((s, e) in imageRanges) {
            if (cursor < s) {
                ssb.setSpan(
                    VerticalShiftSpan(shiftDown),
                    cursor, s, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                placedAny = true
            }
            cursor = maxOf(cursor, e)
        }
        if (cursor < len) {
            ssb.setSpan(
                VerticalShiftSpan(shiftDown),
                cursor, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            placedAny = true
        }
        if (placedAny) {
            extraVerticalPaddingPx = maxOf(extraVerticalPaddingPx, abs(shiftDown))
        }
        return this
    }

    /** 当文本插入导致位移时，把已处理片段中 start >= fromExclusive 的位置全部 +by。 */
    private fun shiftUpdated(
        list: MutableList<Pair<Int, Int>>,
        fromExclusive: Int,
        by: Int,
    ) {
        for (i in list.indices) {
            val (us, ue) = list[i]
            if (us >= fromExclusive) list[i] = (us + by) to (ue + by)
        }
    }

    /**
     * 给当前片段集合添加点击事件。每段会得到独立的 ClickableSpan
     *
     * 注意：`into(textView)` 会自动挂 LinkMovementMethod，无需调用方再设置。
     *
     * @param underline 是否显示系统默认下划线，默认 false。
     * @param overrideColor 强制设置点击文字颜色（ARGB int）；传 null 表示沿用 [color] 已设置的颜色，
     *                      不会被系统默认链接蓝色覆盖。
     * @param listener 点击回调，参数为被点击的 View（一般是承载 SpannableString 的 TextView）。
     */
    fun onClick(
        underline: Boolean = false,
        @ColorInt overrideColor: Int? = null,
        listener: (View) -> Unit,
    ): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                listener(widget)
            }

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

    /**
     * 直接获取构建好的 CharSequence。
     *
     * 注意：此方法不会触发 [image] / [replaceWithImage] 注册的远程 URL 加载，
     * 如果用了 URL 图片，请改用 [into]。
     */
    fun build(): CharSequence = ssb

    /**
     * 将结果设置到 TextView，并触发已注册的远程图片异步加载。自动挂 [LinkMovementMethod]，让 onClick 生效。
     * @param textView 承载 SpannableString 的 TextView。
     */
    fun into(textView: TextView) {
        textView.movementMethod = LinkMovementMethod.getInstance()
        applyExtraVerticalPadding(textView)
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
                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable>?,
                    ) {
                        if (textView.getTag(tagKey) !== ssb) return
                        val curStart = ssb.getSpanStart(load.placeholder)
                        val curEnd = ssb.getSpanEnd(load.placeholder)
                        if (curStart !in 0 until curEnd || curEnd > ssb.length) return
                        resource.setBounds(0, 0, load.width, load.height)
                        ssb.removeSpan(load.placeholder)
                        ssb.setSpan(
                            CenterAlignImageSpan(resource),
                            curStart,
                            curEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        if (initialTextSet) textView.text = ssb
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        }
        initialTextSet = true
        textView.text = ssb
    }

    private fun transparentPlaceholder(w: Int, h: Int): Drawable {
        return Color.TRANSPARENT.toDrawable().apply { setBounds(0, 0, w, h) }
    }

    /**
     * 把 [extraVerticalPaddingPx] 幂等地累加到 TextView.lineSpacingExtra：
     *
     * 用 [R.id.span_builder_added_line_spacing] 这条 tag 在 TextView 自身上记录上次累加的增量，
     * 每次先抵消再加新值，避免 RecyclerView 复用时反复 bind 导致 lineSpacingExtra 无限增长，
     * 同时保留用户在 XML/代码里原本设的基础值。状态与 View 生命周期天然绑定，TextView 被回收时
     * tag 也随之释放，无需额外清理。
     */
    private fun applyExtraVerticalPadding(textView: TextView) {
        val tagKey = R.id.span_builder_added_line_spacing
        val previousAdded = (textView.getTag(tagKey) as? Int) ?: 0
        if (previousAdded == 0 && extraVerticalPaddingPx == 0) return
        val baseExtra = textView.lineSpacingExtra - previousAdded
        textView.includeFontPadding = true
        textView.setLineSpacing(
            baseExtra + extraVerticalPaddingPx.toFloat(),
            textView.lineSpacingMultiplier
        )
        textView.setTag(tagKey, extraVerticalPaddingPx.takeIf { it != 0 })
    }

    /**
     * 通过 baselineShift 整体上下平移文字。正数下移、负数上移。
     * 仅用于视觉微调对齐（如配合 CenterAlignImageSpan 抵消 PNG 透明边），不会撑高行高。
     */
    private class VerticalShiftSpan(@Px private val shiftDown: Int) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) {
            tp.baselineShift += shiftDown
        }

        override fun updateMeasureState(tp: TextPaint) {
            tp.baselineShift += shiftDown
        }
    }

    /**
     * 线性渐变文字 ReplacementSpan。
     *
     * 用 ReplacementSpan 的原因：CharacterStyle 上挂 `paint.shader` 时 shader 坐标系是 canvas
     * 全局坐标，segment 在行内的偏移无法在 updateDrawState 阶段拿到，导致渐变起止与文字位置错位。
     * ReplacementSpan.draw 拿得到 `x`，可以构造精确对齐到本段文字的 shader。
     */
    private class GradientTextSpan(
        private val colors: IntArray,
        private val positions: FloatArray?,
        private val vertical: Boolean,
    ) : ReplacementSpan() {

        private var measuredWidth = 0f
        private var cachedShader: LinearGradient? = null
        private var cachedWidth = 0f
        private var cachedTop = 0
        private var cachedBottom = 0
        private val shaderMatrix = Matrix()

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

            val shader = obtainShader(width, top, bottom)
            shaderMatrix.reset()
            shaderMatrix.setTranslate(x, 0f)
            shader.setLocalMatrix(shaderMatrix)

            val originalShader = paint.shader
            paint.shader = shader
            canvas.drawText(text, s, e, x, y.toFloat(), paint)
            paint.shader = originalShader
        }

        private fun obtainShader(width: Float, top: Int, bottom: Int): LinearGradient {
            val cached = cachedShader
            val sizeChanged = if (vertical) {
                top != cachedTop || bottom != cachedBottom
            } else {
                width != cachedWidth
            }
            if (cached != null && !sizeChanged) return cached

            val shader = if (vertical) {
                LinearGradient(
                    0f, top.toFloat(), 0f, bottom.toFloat(),
                    colors, positions, Shader.TileMode.CLAMP
                )
            } else {
                LinearGradient(
                    0f, 0f, width, 0f,
                    colors, positions, Shader.TileMode.CLAMP
                )
            }
            cachedShader = shader
            cachedWidth = width
            cachedTop = top
            cachedBottom = bottom
            return shader
        }
    }

    /** 仅占据指定宽度、不绘制任何内容的 ReplacementSpan，用于实现文本左右 margin。 */
    private class BlankWidthSpan(@Px private val width: Int) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?,
        ): Int = width

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
        }
    }
}

