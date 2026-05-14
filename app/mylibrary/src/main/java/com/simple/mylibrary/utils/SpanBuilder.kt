package com.simple.mylibrary.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 *     .setText("张三 just sent [gift] x10 to LiveRoom")
 *     .find("张三").color(Color.RED).bold().onClick { ... }
 *     .find("LiveRoom").color(Color.CYAN).italic()
 *     .findRegex(Regex("x\\d+")).color(Color.YELLOW)
 *     .replaceWithImage("[gift]", R.drawable.ic_gift, 24, 24)
 *     .into(tvMsg)
 * ```
 *
 * - 所有样式方法（color/bold/italic/underline/strikethrough/onClick 等）作用于"当前片段集合"。
 *   片段集合由最近一次 append/image/find/findAll/findRegex/range/replaceWithImage 更新。
 * - 图片使用 [CenterAlignImageSpan]：当图片高度大于字体高度时会自动撑高行高，让文字垂直居中。
 * - URL 图片走 Glide 异步加载，先用透明占位 Drawable 撑位，加载完成后替换 Span 并刷新 TextView，
 *   用 tag 校验避免 RecyclerView 复用时回调错位。
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

    private data class PendingImageLoad(
        val url: String,
        val start: Int,
        val width: Int,
        val height: Int,
        val circle: Boolean,
    )

    companion object {
        /**
         * 记录每个 TextView 上次被本类累加到 lineSpacingExtra 上的增量，
         * 用于 RecyclerView 复用场景下做幂等抵消，避免反复 bind 让行高无限增长。
         */
        private val addedLineSpacing = java.util.WeakHashMap<TextView, Int>()

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
     * @param text 要追加的文本，默认空串（仅插入换行）。
     *
     * 注意：调用后"当前片段"只覆盖刚追加的文本本身、不含换行符，
     * 这样链式 `.color()` / `.backgroundColor()` 等才能作用在文字上,
     * 否则会被换行符吃掉(视觉上没反应)。
     */
    fun appendLine(text: CharSequence = ""): SpanBuilder {
        val start = ssb.length
        ssb.append(text)
        val end = ssb.length
        ssb.append("\n")
        segments = if (start < end) listOf(start to end) else emptyList()
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
        ssb.setSpan(
            CenterAlignImageSpan(transparentPlaceholder(width, height)),
            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        pendingImageLoads.add(PendingImageLoad(url, start, width, height, circle))
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
        // 旧文本作废，之前注册的 URL 异步加载也全部失效。
        pendingImageLoads.clear()
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
            ssb.setSpan(
                CenterAlignImageSpan(transparentPlaceholder(width, height)),
                pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            pendingImageLoads.add(PendingImageLoad(url, pos, width, height, circle))
        }
        segments = newSegs
        return this
    }

    /**
     * 内部辅助：从后往前查找并替换 placeholder 为单字符占位（避免下标位移），
     * 同步维护 pendingImageLoads，并对每个 placeholder 最终落点调用 onPlaced
     * 写入 ImageSpan / 记录加载任务。
     *
     * 关键点：原实现返回的是「替换前」的原始下标，多次匹配时较早的下标在
     * 后续 replace 中已经被左移,直接把它当成 segments 会让链式的 color /
     * onClick 等调用 setSpan 时落到不存在的位置上(甚至越界)。
     * 这里改成返回「替换后」的最终下标。
     */
    private fun replacePlaceholderWith(
        placeholder: String,
        onPlaced: (pos: Int) -> Unit,
    ): List<Pair<Int, Int>> {
        if (placeholder.isEmpty()) return emptyList()
        val src = ssb.toString()
        val originalPositions = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = src.indexOf(placeholder, from)
            if (idx < 0) break
            originalPositions.add(idx)
            from = idx + placeholder.length
        }
        if (originalPositions.isEmpty()) return emptyList()

        // 从尾向头替换,避免被前一个替换扰动下标。
        // 每次替换后同步 pendingImageLoads,防止异步回调越界。
        originalPositions.asReversed().forEach { pos ->
            ssb.replace(pos, pos + placeholder.length, " ")
            shiftPendingLoadsForReplace(pos, pos + placeholder.length, 1)
        }

        // 计算每个 placeholder 在最终 ssb 中的下标:
        // 第 i 个 (按原始顺序) 前面有 i 个 placeholder 各缩短了 (len - 1) 个字符。
        val perReplacementDelta = placeholder.length - 1
        val finalPositions = originalPositions.mapIndexed { i, p ->
            p - i * perReplacementDelta
        }

        // 替换全部完成后再统一挂 span / 注册 URL 加载,
        // 这样 onPlaced 收到的就是最终下标,后续 segments / pendingImageLoads
        // 都不会再被位移影响。
        finalPositions.forEach { onPlaced(it) }

        return finalPositions.map { it to it + 1 }
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
     * 限制当前片段文字长度，超出则截断并追加省略号。
     *
     * @param maxChars 最大保留字符数（不含 ellipsis 本身）
     * @param ellipsis 截断后追加的字符，默认 "..."
     *
     * 行为：
     * - 仅对当前片段集合中长度超过 maxChars 的"文字片段"生效；图片片段（长度为 1 的占位）天然不会被截断。
     * - 已挂在整段上的样式 span（color/bold/underline/onClick 等）会自动延展到 ellipsis 末尾，
     *   保证截断后的视觉风格一致（避免出现"昵称是金色但 ... 是白色"的情况）。
     * - 多片段时按降序处理，文本删除/插入造成的位移会自动同步到 segments 集合，可继续链式调用。
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
            shiftPendingLoadsForReplace(s + maxChars, e, ellipsis.length)
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
                    shiftPendingLoadsForReplace(curE, curE, 1)
                    ssb.setSpan(
                        BlankWidthSpan(right),
                        curE, curE + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    shiftUpdated(updated, fromExclusive = curE, by = 1)
                    curE += 1
                }
                if (left > 0) {
                    ssb.insert(curS, " ")
                    shiftPendingLoadsForReplace(curS, curS, 1)
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
                    extraVerticalPaddingPx =
                        maxOf(extraVerticalPaddingPx, kotlin.math.abs(shiftDown))
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
     * 设计动机：[marginPx] 只作用于"当前片段"。当行内含 CenterAlignImageSpan 且 PNG 顶/底有透明边时，
     * 整行所有文字都需要朝同一方向平移才能对齐图片视觉中心 —— 单段调用 [marginPx] 顾此失彼。
     * 此方法一次性给所有文字段挂 baselineShift。
     *
     * 行为：
     * - 扫描整段，把所有 CenterAlignImageSpan 覆盖范围之外的连续区间识别为"文字片段"。
     * - 每段文字片段挂 [VerticalShiftSpan]，shift = top - bottom（正下移 / 负上移）。
     * - 累计 [extraVerticalPaddingPx]，[into] 时自动撑 lineSpacingExtra 避免裁切。
     * - 调用时机：建议在所有 [image] / [replaceWithImage] / [append] 完成后再调，
     *   后续新增的文字片段不会被自动覆盖。
     * - **不更新当前片段集合**，链式后续 onClick/color 等仍作用于上一个 append/find 的范围。
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
        for ((s, e) in imageRanges) {
            if (cursor < s) {
                ssb.setSpan(
                    VerticalShiftSpan(shiftDown),
                    cursor, s, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            cursor = maxOf(cursor, e)
        }
        if (cursor < len) {
            ssb.setSpan(
                VerticalShiftSpan(shiftDown),
                cursor, len, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        extraVerticalPaddingPx = maxOf(extraVerticalPaddingPx, kotlin.math.abs(shiftDown))
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
     * 当 ssb 在 [replaceStart, replaceEnd) 区间被替换成长度为 newLength 的新内容时，
     * 同步维护 pendingImageLoads:
     *
     * - 完全在替换区前 → 不动
     * - 完全在替换区后 → start 增加 delta = newLength - (replaceEnd - replaceStart)
     * - 与替换区有重叠（占位符所在位置被冲掉） → 直接丢弃,
     *   避免回调时 setSpan 落到已经不存在的下标上引发 IndexOutOfBoundsException
     *
     * 用反向遍历以便安全地从 MutableList 中移除元素。
     */
    private fun shiftPendingLoadsForReplace(
        replaceStart: Int,
        replaceEnd: Int,
        newLength: Int,
    ) {
        if (pendingImageLoads.isEmpty()) return
        val delta = newLength - (replaceEnd - replaceStart)
        for (i in pendingImageLoads.indices.reversed()) {
            val load = pendingImageLoads[i]
            val loadEnd = load.start + 1
            when {
                loadEnd <= replaceStart -> {
                    // entirely before — no change
                }
                load.start >= replaceEnd -> {
                    if (delta != 0) {
                        pendingImageLoads[i] = load.copy(start = load.start + delta)
                    }
                }
                else -> {
                    pendingImageLoads.removeAt(i)
                }
            }
        }
    }

    /**
     * 给当前片段集合添加点击事件。每段会得到独立的 ClickableSpan 实例（位置稳定）。
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
     *
     * 远程图片用 `textView.tag` 校验，可在 RecyclerView 复用时避免回调落到错误的 TextView 上。
     *
     * 若 [extraVerticalPaddingPx] > 0（因 [marginPx] 上下平移文字导致），会自动累加到
     * `TextView.lineSpacingExtra` 上并保持 `includeFontPadding = true`，避免文字被裁切。
     *
     * @param textView 承载 SpannableString 的 TextView。
     */
    fun into(textView: TextView) {
        textView.movementMethod = LinkMovementMethod.getInstance()
        applyExtraVerticalPadding(textView)
        textView.text = ssb
        if (pendingImageLoads.isEmpty()) return

        val tagKey = textView.id.takeIf { it != View.NO_ID } ?: hashCode()

        /**
         * 用一个 Set 而不是单一 url 作为 tag：
         *
         * 原写法在循环里每次 setTag(tagKey, load.url)，多张 URL 图共存时
         * 后写的 url 会盖掉前面的，结果只有最后一张能通过 tag 校验，
         * 前面的图都被 onResourceReady 里那条 `!=` 误判为「视图已复用」而静默丢弃。
         *
         * 改成"本次 into 期待的所有 url 集合"做引用相等检查：
         *   - RecyclerView 复用：下一次 into 会写入一个新的 Set 实例，
         *     旧回调里捕获的 expectedUrls 与当前 tag !==，判定失效。
         *   - 多张 URL：同一个 Set 实例覆盖整轮回调，依次成功。
         */
        val expectedUrls = pendingImageLoads.mapTo(HashSet()) { it.url }
        textView.setTag(tagKey, expectedUrls)

        pendingImageLoads.forEach { load ->
            var options = RequestOptions().override(load.width, load.height)
            if (load.circle) options = options.circleCrop()
            Glide.with(textView).asDrawable().load(load.url).apply(options)
                .into(object : CustomTarget<Drawable>() {
                    override fun onResourceReady(
                        resource: Drawable,
                        transition: Transition<in Drawable>?,
                    ) {
                        // 视图已被复用 / 同一 TextView 再次 into 过：tag 已被新一轮覆盖。
                        if (textView.getTag(tagKey) !== expectedUrls) return

                        /**
                         * 注册 URL 加载之后，ssb 可能被 maxLength / setText /
                         * replaceWithImage 等截断或重排，load.start 不再有效。
                         * 这里做边界校验，越界就丢弃这次替换，避免
                         * IndexOutOfBoundsException: setSpan ends beyond length。
                         */
                        val end = load.start + 1
                        if (load.start < 0 || end > ssb.length) return

                        resource.setBounds(0, 0, load.width, load.height)
                        ssb.getSpans(load.start, end, CenterAlignImageSpan::class.java)
                            .forEach { ssb.removeSpan(it) }
                        ssb.setSpan(
                            CenterAlignImageSpan(resource),
                            load.start,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        textView.text = ssb
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {}
                })
        }
    }

    private fun transparentPlaceholder(w: Int, h: Int): Drawable {
        return Color.TRANSPARENT.toDrawable().apply { setBounds(0, 0, w, h) }
    }

    /**
     * 把 [extraVerticalPaddingPx] 幂等地累加到 TextView.lineSpacingExtra：
     *
     * 用 tag 记录上次本类加过的增量，每次先抵消再加新值，避免 RecyclerView 复用时
     * 反复 bind 导致 lineSpacingExtra 无限增长，同时保留用户在 XML/代码里原本设的基础值。
     */
    private fun applyExtraVerticalPadding(textView: TextView) {
        val previousAdded = addedLineSpacing[textView] ?: 0
        if (previousAdded == 0 && extraVerticalPaddingPx == 0) return
        val baseExtra = textView.lineSpacingExtra - previousAdded
        textView.includeFontPadding = true
        textView.setLineSpacing(
            baseExtra + extraVerticalPaddingPx.toFloat(),
            textView.lineSpacingMultiplier
        )
        if (extraVerticalPaddingPx == 0) {
            addedLineSpacing.remove(textView)
        } else {
            addedLineSpacing[textView] = extraVerticalPaddingPx
        }
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
