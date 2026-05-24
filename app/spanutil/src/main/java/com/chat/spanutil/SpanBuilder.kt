package com.chat.spanutil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.Px
import androidx.annotation.RawRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.chat.spanutil.SpanBuilder.Companion.setImageTransformer
import com.chat.spanutil.SpanBuilder.Companion.setTextSpanFactory
import com.chat.spanutil.span.BlankWidthSpan
import com.chat.spanutil.span.BorderedImageDrawable
import com.chat.spanutil.span.CenterAlignImageSpan
import com.chat.spanutil.span.CharAnim
import com.chat.spanutil.span.CharAnimSpan
import com.chat.spanutil.span.CharAnimationDriver
import com.chat.spanutil.span.CharAnims
import com.chat.spanutil.span.EmojiRegistry
import com.chat.spanutil.span.HostAware
import com.chat.spanutil.span.LoaderType
import com.chat.spanutil.span.Releasable
import com.chat.spanutil.span.RepeatConfig
import com.chat.spanutil.span.RoundMaskDrawable
import com.chat.spanutil.span.SpanImageLoader
import com.chat.spanutil.span.TextDecorationSpan
import com.chat.spanutil.span.VerticalShiftSpan
import java.lang.ref.WeakReference
import java.util.EnumMap
import kotlin.math.abs

/**
 * 链式 Span 构建器。支持三种使用模式：
 *
 * 1) **拼接模式**：按顺序 append 每个片段。
 * 2) **服务端文案模式**：先 setText 装载，再 find/findRegex/range 定位后施加样式。
 * 3) **build 模式**：不触发网络图片加载，直接拿 CharSequence。
 */
class SpanBuilder private constructor(private val context: Context) {

    private val ssb = SpannableStringBuilder()
    private var segments: List<Pair<Int, Int>> = emptyList()

    private val pendingImageLoads = mutableListOf<PendingImageLoad>()
    private val pendingImageBorders = mutableMapOf<CenterAlignImageSpan, ImageBorderConfig>()
    private val pendingImageTransforms =
        mutableMapOf<CenterAlignImageSpan, (Drawable, Int, Int) -> Drawable>()

    private val animatables = mutableListOf<Animatable>()
    private var needsSoftwareLayer = false

    private var hasClickable = false

    private val clickHolders = mutableListOf<ClickListenerHolder>()

    private var charAnimDriver: CharAnimationDriver? = null
    private var charAnimRange: Pair<Int, Int>? = null

    var extraVerticalPaddingPx: Int = 0
        private set

    private data class PendingImageLoad(
        val url: Any,
        val placeholder: CenterAlignImageSpan,
        val width: Int,
        val height: Int,
        val circle: Boolean,
        val loader: SpanImageLoader? = null,
    )

    private data class ImageBorderConfig(
        val borderWidth: Float,
        val borderColor: Int,
        val cornerRadius: Float,
        val gradientColors: IntArray? = null,
        val gradientVertical: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ImageBorderConfig
            return borderWidth == other.borderWidth &&
                    borderColor == other.borderColor &&
                    cornerRadius == other.cornerRadius &&
                    gradientVertical == other.gradientVertical &&
                    gradientColors.contentEquals(other.gradientColors)
        }

        override fun hashCode(): Int {
            var result = borderWidth.hashCode()
            result = 31 * result + borderColor
            result = 31 * result + cornerRadius.hashCode()
            result = 31 * result + gradientVertical.hashCode()
            result = 31 * result + (gradientColors?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        @JvmStatic
        fun with(context: Context): SpanBuilder = SpanBuilder(context)

        private val loaders = EnumMap<LoaderType, SpanImageLoader>(LoaderType::class.java)

        @JvmStatic
        fun setLoader(type: LoaderType, loader: SpanImageLoader) {
            loaders[type] = loader
        }

        internal fun requireLoader(type: LoaderType): SpanImageLoader? {
            val loader = loaders[type]
            if (loader == null) {
                SpanLog.e("SpanBuilder") {
                    "${type.name} loader not registered, skip. " +
                            "Call SpanBuilder.setLoader(${type.name}, ...) first " +
                            "(e.g. from ${type.suggestedModule} 库)"
                }
            }
            return loader
        }

        private var textSpanFactory: ((start: Int, end: Int, text: CharSequence) -> Any)? = null

        @JvmStatic
        fun setTextSpanFactory(factory: (start: Int, end: Int, text: CharSequence) -> Any) {
            textSpanFactory = factory
        }

        private var imageTransformer: ((drawable: Drawable, width: Int, height: Int) -> Drawable)? =
            null

        @JvmStatic
        fun setImageTransformer(transformer: (drawable: Drawable, width: Int, height: Int) -> Drawable) {
            imageTransformer = transformer
        }
    }

    /**
     * 追加一段文字，后续样式方法仅对该片段生效。
     *
     * @param text 要追加的文字
     */
    fun append(text: CharSequence): SpanBuilder {
        val start = ssb.length
        ssb.append(text)
        segments = listOf(start to ssb.length)
        return this
    }

    /**
     * 追加一段文字并在末尾插入换行符，后续样式方法仅对文字片段（不含换行符）生效。
     *
     * @param text 要追加的文字，默认空字符串（仅插入换行）
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
     * 追加一张图片（资源 ID），后续样式方法对该图片片段生效。
     *
     * @param resId  Drawable 资源 ID
     * @param width  显示宽度 px；-1 = 使用 Drawable 固有宽度
     * @param height 显示高度 px；-1 = 使用 Drawable 固有高度
     */
    fun image(@DrawableRes resId: Int, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return this
        return image(drawable, width, height)
    }

    /**
     * 追加一张图片（Drawable），后续样式方法对该图片片段生效。
     *
     * @param drawable 要插入的 Drawable
     * @param width    显示宽度 px；-1 = 使用 Drawable 固有宽度
     * @param height   显示高度 px；-1 = 使用 Drawable 固有高度
     */
    fun image(drawable: Drawable, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder {
        val w = if (width > 0) width else drawable.intrinsicWidth.coerceAtLeast(1)
        val h = if (height > 0) height else drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        ssb.setSpan(CenterAlignImageSpan(drawable), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (drawable is Animatable) animatables.add(drawable)
        segments = listOf(start to end)
        return this
    }

    /**
     * 追加一张图片（Bitmap），后续样式方法对该图片片段生效。
     *
     * @param bitmap 要插入的 Bitmap
     * @param width  显示宽度 px；-1 = 使用 Bitmap 固有宽度
     * @param height 显示高度 px；-1 = 使用 Bitmap 固有高度
     */
    fun image(bitmap: Bitmap, @Px width: Int = -1, @Px height: Int = -1): SpanBuilder =
        image(bitmap.toDrawable(context.resources), width, height)

    /**
     * 追加一张网络图片（异步加载），先用透明占位符占位，加载完成后自动刷新 TextView。
     * 注意：必须配合 [into] 使用，[build] 模式无法触发图片加载回调。
     *
     * @param url    图片 URL
     * @param width  显示宽度 px
     * @param height 显示高度 px
     * @param circle 是否裁剪为圆形，默认 false
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

    /**
     * 添加本地 GIF / WebP 动图(@RawRes 或 @DrawableRes)。走 Glide 异步解码,
     * 加载完成后由 [into] 自动启动动画(同时也支持静态图,等价于通过 Glide 加载的 [image]).
     *
     * 必须配合 [into] 使用,否则不会触发实际加载。
     *
     * @param resId 资源 ID(放 res/raw 或 res/drawable 均可)。
     * @param width 显示宽度,单位 px。
     * @param height 显示高度,单位 px。
     * @param circle 是否裁剪为圆形,默认 false。
     */
    fun gif(
        @RawRes @DrawableRes resId: Int,
        @Px width: Int,
        @Px height: Int,
        circle: Boolean = false
    ): SpanBuilder {
        val loader = requireLoader(LoaderType.Gif) ?: return this
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        val placeholderSpan = CenterAlignImageSpan(transparentPlaceholder(width, height))
        ssb.setSpan(placeholderSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        pendingImageLoads.add(PendingImageLoad(resId, placeholderSpan, width, height, circle, loader = loader))
        segments = listOf(start to end)
        return this
    }

    /**
     * 添加远程 GIF / WebP 动图。
     *
     * @param url    远端 URL
     * @param width  显示宽度 px
     * @param height 显示高度 px
     * @param circle 是否裁剪为圆形，默认 false
     */
    fun gif(url: String, @Px width: Int, @Px height: Int, circle: Boolean = false): SpanBuilder {
        val loader = requireLoader(LoaderType.Gif) ?: return this
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        val placeholderSpan = CenterAlignImageSpan(transparentPlaceholder(width, height))
        ssb.setSpan(placeholderSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        pendingImageLoads.add(PendingImageLoad(url, placeholderSpan, width, height, circle, loader = loader))
        segments = listOf(start to end)
        return this
    }

    /**
     * 添加 SVG 矢量图,统一入口,支持:
     *
     * @param url    SVG 资源地址(URL 或本地路径)
     * @param width  显示宽度 px
     * @param height 显示高度 px
     * @param circle 是否裁剪为圆形,默认 false
     */
    fun svg(url: String, @Px width: Int, @Px height: Int, circle: Boolean = false): SpanBuilder =
        addSvgLoad(url, width, height, circle)

    /**
     * 添加本地 SVG 资源(放 res/raw 的原始 .svg)。
     *
     * @param resId res/raw 下的 .svg 资源 ID
     * @param width 显示宽度 px
     * @param height 显示高度 px
     * @param circle 是否裁剪为圆形,默认 false
     */
    fun svg(
        @RawRes resId: Int,
        @Px width: Int,
        @Px height: Int,
        circle: Boolean = false
    ): SpanBuilder =
        addSvgLoad(resId, width, height, circle)

    private fun addSvgLoad(url: Any, width: Int, height: Int, circle: Boolean): SpanBuilder {
        val loader = requireLoader(LoaderType.Svg) ?: return this
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        val placeholderSpan = CenterAlignImageSpan(transparentPlaceholder(width, height))
        ssb.setSpan(placeholderSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        pendingImageLoads.add(
            PendingImageLoad(url, placeholderSpan, width, height, circle, loader = loader)
        )
        segments = listOf(start to end)
        return this
    }

    /**
     * 添加 SVGA 动效(直播礼物 / 复杂动画首选)。
     *
     * @param url    .svga 远端 URL 或 assets 文件名
     * @param width  显示宽度 px
     * @param height 显示高度 px
     * @param circle 是否裁剪为圆形,默认 false
     */
    fun svga(url: String, @Px width: Int, @Px height: Int, circle: Boolean = false): SpanBuilder {
        val loader = requireLoader(LoaderType.Svga) ?: return this
        val start = ssb.length
        ssb.append(" ")
        val end = ssb.length
        val placeholderSpan = CenterAlignImageSpan(transparentPlaceholder(width, height))
        ssb.setSpan(placeholderSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        pendingImageLoads.add(
            PendingImageLoad(url, placeholderSpan, width, height, circle, loader = loader)
        )
        segments = listOf(start to end)
        return this
    }

    /**
     * 装载整段文字（清空之前内容），后续可用 [find]/[findAll]/[findRegex]/[range] 定位片段再施加样式。
     *
     * @param text 要装载的完整文字
     */
    fun setText(text: CharSequence): SpanBuilder {
        ssb.clear()
        ssb.append(text)
        segments = listOf(0 to ssb.length)
        return this
    }

    /**
     * 查找第一个匹配的子串并将其设为当前片段；未找到则片段为空。
     *
     * @param keyword    要查找的关键词
     * @param ignoreCase 是否忽略大小写，默认 false
     */
    fun find(keyword: String, ignoreCase: Boolean = false): SpanBuilder {
        if (keyword.isEmpty()) {
            segments = emptyList(); return this
        }
        val idx = ssb.toString().indexOf(keyword, ignoreCase = ignoreCase)
        segments = if (idx >= 0) listOf(idx to idx + keyword.length) else emptyList()
        return this
    }

    /**
     * 查找所有匹配的子串并将其设为当前片段列表；未找到则片段为空。
     *
     * @param keyword    要查找的关键词
     * @param ignoreCase 是否忽略大小写，默认 false
     */
    fun findAll(keyword: String, ignoreCase: Boolean = false): SpanBuilder {
        if (keyword.isEmpty()) {
            segments = emptyList(); return this
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
     * 用正则查找所有匹配项并将其设为当前片段列表。
     *
     * @param regex 匹配规则
     */
    fun findRegex(regex: Regex): SpanBuilder {
        segments = regex.findAll(ssb).map { it.range.first to it.range.last + 1 }.toList()
        return this
    }

    /**
     * 手动指定一个范围作为当前片段，范围越界时自动修正到合法区间。
     *
     * @param start        起始索引（含）
     * @param endExclusive 结束索引（不含）
     */
    fun range(start: Int, endExclusive: Int): SpanBuilder {
        val s = start.coerceIn(0, ssb.length)
        val e = endExclusive.coerceIn(s, ssb.length)
        segments = if (s < e) listOf(s to e) else emptyList()
        return this
    }

    /**
     * 将整个文字内容设为当前片段；内容为空时片段为空。
     */
    fun all(): SpanBuilder {
        segments = if (ssb.isNotEmpty()) listOf(0 to ssb.length) else emptyList()
        return this
    }

    /**
     * 把文字中所有出现的 [placeholder] 替换为图片（资源 ID）。
     *
     * @param placeholder 文字中的占位符字符串
     * @param resId       替换用的 Drawable 资源 ID
     * @param width       图片显示宽度 px
     * @param height      图片显示高度 px
     */
    fun replaceWithImage(
        placeholder: String, @DrawableRes resId: Int, @Px width: Int, @Px height: Int,
    ): SpanBuilder {
        val drawable = ContextCompat.getDrawable(context, resId) ?: return this
        return replaceWithImage(placeholder, drawable, width, height)
    }

    /**
     * 把文字中所有出现的 [placeholder] 替换为图片（Drawable）。
     *
     * @param placeholder 文字中的占位符字符串
     * @param drawable    替换用的 Drawable
     * @param width       图片显示宽度 px；-1 = 使用 Drawable 固有宽度
     * @param height      图片显示高度 px；-1 = 使用 Drawable 固有高度
     */
    fun replaceWithImage(
        placeholder: String, drawable: Drawable, @Px width: Int = -1, @Px height: Int = -1,
    ): SpanBuilder {
        if (placeholder.isEmpty()) return this
        val w = if (width > 0) width else drawable.intrinsicWidth.coerceAtLeast(1)
        val h = if (height > 0) height else drawable.intrinsicHeight.coerceAtLeast(1)
        drawable.setBounds(0, 0, w, h)
        segments = replacePlaceholderWith(placeholder) { pos ->
            ssb.setSpan(
                CenterAlignImageSpan(drawable),
                pos,
                pos + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return this
    }

    /**
     * 把文字中所有出现的 [placeholder] 替换为网络图片（异步加载）。
     * 注意：必须配合 [into] 使用，[build] 模式无法触发图片加载回调。
     *
     * @param placeholder 文字中的占位符字符串
     * @param url         图片 URL
     * @param width       图片显示宽度 px
     * @param height      图片显示高度 px
     * @param circle      是否裁剪为圆形，默认 false
     */
    fun replaceWithImage(
        placeholder: String, url: String, @Px width: Int, @Px height: Int, circle: Boolean = false,
    ): SpanBuilder {
        if (placeholder.isEmpty()) return this
        segments = replacePlaceholderWith(placeholder) { pos ->
            val placeholderSpan = CenterAlignImageSpan(transparentPlaceholder(width, height))
            ssb.setSpan(placeholderSpan, pos, pos + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            pendingImageLoads.add(PendingImageLoad(url, placeholderSpan, width, height, circle))
        }
        return this
    }

    private fun replacePlaceholderWith(
        placeholder: String, onPlaced: (pos: Int) -> Unit,
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

    /**
     * 扫描当前文本,把所有匹配 [pattern] 的 token(默认 `:smile:` / `[smile]`)
     * 用 [EmojiRegistry] 里注册的资源(@DrawableRes 或 URL)替换为图片。
     *
     * @param width   每个 emoji 的显示宽度 px
     * @param height  每个 emoji 的显示高度 px
     * @param pattern token 匹配规则,默认匹配 `:abc:` 与 `[abc]`
     */
    fun replaceEmoji(
        @Px width: Int,
        @Px height: Int,
        pattern: Regex = EmojiRegistry.DEFAULT_PATTERN,
    ): SpanBuilder {
        if (ssb.isEmpty()) return this
        val matches = pattern.findAll(ssb.toString()).toList().asReversed()
        val placedRanges = mutableListOf<Pair<Int, Int>>()
        matches.forEach { m ->
            val token = m.value
            val resource = EmojiRegistry.resolve(token) ?: return@forEach
            val start = m.range.first
            val end = m.range.last + 1
            ssb.replace(start, end, " ")
            when (resource) {
                is Int -> {
                    val drawable = ContextCompat.getDrawable(context, resource) ?: return@forEach
                    drawable.setBounds(0, 0, width, height)
                    ssb.setSpan(
                        CenterAlignImageSpan(drawable),
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }

                is String -> {
                    val placeholderSpan =
                        CenterAlignImageSpan(transparentPlaceholder(width, height))
                    ssb.setSpan(
                        placeholderSpan,
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    pendingImageLoads.add(
                        PendingImageLoad(resource, placeholderSpan, width, height, false)
                    )
                }
            }
            placedRanges.add(start to start + 1)
        }
        segments = placedRanges.sortedBy { it.first }
        return this
    }

    @JvmOverloads
    fun charAnimation(
        anim: CharAnim = CharAnims.Fade,
        perCharDelayMs: Long = 60L,
        charDurationMs: Long = 360L,
        repeat: RepeatConfig = RepeatConfig.ONCE,
    ): SpanBuilder {
        if (segments.isEmpty()) return this
        val start = segments.first().first
        val end = segments.last().second
        val total = end - start
        if (total <= 0) return this
        val driver = CharAnimationDriver(total, perCharDelayMs, charDurationMs, anim, repeat)
        for (i in 0 until total) {
            ssb.setSpan(
                CharAnimSpan(i, driver),
                start + i,
                start + i + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        charAnimDriver = driver
        charAnimRange = start to end
        return this
    }

    /**
     * 设置当前片段的文字颜色（前景色）。
     *
     * @param color 颜色值（ARGB）
     */
    fun color(@ColorInt color: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(ForegroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 设置当前片段的文字背景色。
     *
     * @param color 背景颜色值（ARGB）
     */
    fun backgroundColor(@ColorInt color: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(BackgroundColorSpan(color), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 给当前片段叠加线性渐变色（文字前景）。
     * 可与 [stroke] / [glow] / [shadow] 叠加，共享同一个 [TextDecorationSpan]。
     *
     * @param colors    渐变颜色数组，长度 ≥ 2
     * @param positions 各颜色在 [0,1] 上的分布位置；null = 自动等分
     * @param vertical  true=自上而下；false=自左到右（默认）
     */
    fun gradientColor(
        colors: IntArray, positions: FloatArray? = null, vertical: Boolean = false,
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

    /**
     * [gradientColor] 的二色便捷重载。
     *
     * @param startColor 渐变起始色
     * @param endColor   渐变结束色
     * @param vertical   true=自上而下；false=自左到右（默认）
     */
    fun gradientColor(
        @ColorInt startColor: Int, @ColorInt endColor: Int, vertical: Boolean = false,
    ): SpanBuilder = gradientColor(intArrayOf(startColor, endColor), null, vertical)

    /**
     * 给当前片段文字添加描边（边框）。可与 [gradientColor] / [glow] / [shadow] 叠加使用。
     *
     * @param color       描边颜色
     * @param strokeWidth 描边宽度 px；描边沿字形轮廓向内外各扩展 strokeWidth/2
     */
    fun stroke(@ColorInt color: Int, @Px strokeWidth: Float): SpanBuilder =
        applyOrMergeDecoration { existing ->
            existing?.withStroke(color, strokeWidth) ?: TextDecorationSpan().withStroke(
                color,
                strokeWidth
            )
        }

    /**
     * 给当前片段文字添加发光效果（BlurMaskFilter）。
     * 可与 [gradientColor] / [stroke] / [shadow] 叠加使用。
     * 注意：[into] 会自动为 TextView 设置 LAYER_TYPE_SOFTWARE。
     *
     * @param color  发光颜色
     * @param radius 发光半径 px，越大扩散范围越宽
     */
    fun glow(@ColorInt color: Int, @Px radius: Float): SpanBuilder {
        needsSoftwareLayer = true
        return applyOrMergeDecoration { existing ->
            existing?.withGlow(color, radius) ?: TextDecorationSpan().withGlow(color, radius)
        }
    }

    /**
     * 给当前片段文字添加阴影（TextPaint.setShadowLayer）。
     * 可与 [gradientColor] / [stroke] / [glow] 叠加使用。
     * 注意：[into] 会自动为 TextView 设置 LAYER_TYPE_SOFTWARE。
     *
     * @param color  阴影颜色
     * @param radius 阴影模糊半径 px，越大越模糊
     * @param dx     阴影水平偏移 px，正值向右
     * @param dy     阴影垂直偏移 px，正值向下
     */
    fun shadow(
        @ColorInt color: Int,
        @Px radius: Float,
        @Px dx: Float = 0f,
        @Px dy: Float = 0f
    ): SpanBuilder {
        needsSoftwareLayer = true
        return applyOrMergeDecoration { existing ->
            existing?.withShadow(color, radius, dx, dy) ?: TextDecorationSpan().withShadow(
                color,
                radius,
                dx,
                dy
            )
        }
    }

    /**
     * 查找或新建当前片段对应的 [TextDecorationSpan] 并就地更新。
     * 就地修改（不 remove+set）：RecyclerView 复用时不重复创建对象。
     */
    private fun applyOrMergeDecoration(update: (TextDecorationSpan?) -> TextDecorationSpan): SpanBuilder =
        applyEach { s, e ->
            val existing = ssb.getSpans(s, e, TextDecorationSpan::class.java)
                .firstOrNull { ssb.getSpanStart(it) == s && ssb.getSpanEnd(it) == e }
            val newSpan = update(existing)
            if (existing == null) ssb.setSpan(newSpan, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /** 将当前片段文字设为粗体。 */
    fun bold(): SpanBuilder = applyStyle(Typeface.BOLD)

    /** 将当前片段文字设为斜体。 */
    fun italic(): SpanBuilder = applyStyle(Typeface.ITALIC)

    /** 将当前片段文字设为粗斜体。 */
    fun boldItalic(): SpanBuilder = applyStyle(Typeface.BOLD_ITALIC)

    private fun applyStyle(style: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(StyleSpan(style), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 给当前片段文字添加下划线。 */
    fun underline(): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(UnderlineSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** 给当前片段文字添加删除线。 */
    fun strikethrough(): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(StrikethroughSpan(), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 设置当前片段文字的绝对字号。
     *
     * @param sizePx 字号大小 px
     */
    fun sizePx(@Px sizePx: Int): SpanBuilder = applyEach { s, e ->
        ssb.setSpan(AbsoluteSizeSpan(sizePx), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 对当前片段文字做居中省略截断，超出 [maxChars] 时保留首尾、中间替换为 [ellipsis]。
     *
     * @param maxChars 最大保留字符数（不含 ellipsis）
     * @param ellipsis 省略号字符串，默认 "..."
     */
    fun maxLengthMiddle(maxChars: Int, ellipsis: String = "..."): SpanBuilder {
        if (maxChars <= 0) return this
        val sorted = segments.sortedByDescending { it.first }
        val updated = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { (s, e) ->
            val segLen = e - s
            if (segLen <= maxChars) {
                updated.add(s to e); return@forEach
            }
            val headLen = (maxChars + 1) / 2
            val tailLen = maxChars - headLen
            val cutStart = s + headLen
            val cutEnd = e - tailLen
            if (cutStart >= cutEnd) {
                updated.add(s to e); return@forEach
            }
            val newE = s + headLen + ellipsis.length + tailLen
            replaceAndRespan(s, e, cutStart, cutEnd, ellipsis, newE)
            shiftUpdated(updated, fromExclusive = e, by = newE - e)
            updated.add(s to newE)
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    /**
     * 对当前片段文字做末尾省略截断，超出 [maxChars] 时末尾替换为 [ellipsis]。
     *
     * @param maxChars 最大保留字符数（不含 ellipsis）
     * @param ellipsis 省略号字符串，默认 "..."
     */
    fun maxLength(maxChars: Int, ellipsis: String = "..."): SpanBuilder {
        if (maxChars <= 0) return this
        val sorted = segments.sortedByDescending { it.first }
        val updated = mutableListOf<Pair<Int, Int>>()
        sorted.forEach { (s, e) ->
            val segLen = e - s
            if (segLen <= maxChars) {
                updated.add(s to e); return@forEach
            }
            val cutStart = s + maxChars
            val newE = cutStart + ellipsis.length
            replaceAndRespan(s, e, cutStart, e, ellipsis, newE)
            shiftUpdated(updated, fromExclusive = e, by = newE - e)
            updated.add(s to newE)
        }
        segments = updated.sortedBy { it.first }
        return this
    }

    /**
     * maxLength / maxLengthMiddle 共性逻辑：
     * 1. 收集覆盖范围 [s,e) 的 span；
     * 2. 替换 [cutStart, cutEnd) 为 ellipsis；
     * 3. 将收集到的 span 重新绑定到 [s, newE)。
     */
    private fun replaceAndRespan(
        s: Int,
        e: Int,
        cutStart: Int,
        cutEnd: Int,
        ellipsis: String,
        newE: Int
    ) {
        val covering = ssb.getSpans(s, e, Any::class.java).mapNotNull { sp ->
            val ss = ssb.getSpanStart(sp);
            val se = ssb.getSpanEnd(sp)
            if (se >= e) Triple(sp, ss, ssb.getSpanFlags(sp)) else null
        }
        ssb.replace(cutStart, cutEnd, ellipsis)
        covering.forEach { (sp, ss, flags) ->
            ssb.removeSpan(sp)
            ssb.setSpan(sp, ss, newE, flags)
        }
    }

    /**
     * 给当前图片片段添加纯色边框。必须在 [image] 之后调用。
     *
     * @param color        边框颜色
     * @param borderWidth  边框宽度 px
     * @param cornerRadius 边框圆角半径 px，0 = 直角
     */
    fun imageBorder(
        @ColorInt color: Int, @Px borderWidth: Float, @Px cornerRadius: Float = 0f,
    ): SpanBuilder = applyImageBorder(ImageBorderConfig(borderWidth, color, cornerRadius))

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
        @ColorInt startColor: Int, @ColorInt endColor: Int,
        @Px borderWidth: Float, @Px cornerRadius: Float = 0f, vertical: Boolean = false,
    ): SpanBuilder = applyImageBorder(
        ImageBorderConfig(
            borderWidth,
            startColor,
            cornerRadius,
            intArrayOf(startColor, endColor),
            vertical
        )
    )

    /**
     * 给当前图片片段添加渐变边框（多色）。必须在 [image] 之后调用。
     *
     * @param colors       渐变颜色数组，长度 ≥ 2
     * @param borderWidth  边框宽度 px
     * @param cornerRadius 边框圆角半径 px，0 = 直角
     * @param vertical     true=自上而下；false=自左到右（默认）
     */
    fun imageBorderGradient(
        colors: IntArray,
        @Px borderWidth: Float,
        @Px cornerRadius: Float = 0f,
        vertical: Boolean = false,
    ): SpanBuilder {
        require(colors.size >= 2) { "imageBorderGradient needs at least 2 colors" }
        return applyImageBorder(
            ImageBorderConfig(
                borderWidth,
                colors[0],
                cornerRadius,
                colors,
                vertical
            )
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
            val wrapped = makeBorderedDrawable(orig, config)
            wrapped.setBounds(orig.bounds)
            ssb.removeSpan(span)
            ssb.setSpan(CenterAlignImageSpan(wrapped), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 从 [ImageBorderConfig] 创建 [BorderedImageDrawable]。 */
    private fun makeBorderedDrawable(
        inner: Drawable,
        config: ImageBorderConfig
    ): BorderedImageDrawable =
        BorderedImageDrawable(
            inner, config.borderWidth, config.borderColor, config.cornerRadius,
            config.gradientColors, config.gradientVertical
        )

    /**
     * 给当前图片或文字片段添加外边距。
     * - 图片片段：通过 [InsetDrawable] 扩大显示区域；
     * - 文字片段：left/right 插入空白占位字符，top/bottom 通过基线偏移实现垂直位移。
     *
     * @param left   左边距 px，默认 0
     * @param top    上边距 px，默认 0
     * @param right  右边距 px，默认 0
     * @param bottom 下边距 px，默认 0
     */
    fun marginPx(
        @Px left: Int = 0,
        @Px top: Int = 0,
        @Px right: Int = 0,
        @Px bottom: Int = 0
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
                val curS = s;
                var curE = e
                if (right > 0) {
                    ssb.insert(curE, " ")
                    ssb.setSpan(
                        BlankWidthSpan(right),
                        curE,
                        curE + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    shiftUpdated(updated, fromExclusive = curE, by = 1); curE += 1
                }
                if (left > 0) {
                    ssb.insert(curS, " ")
                    ssb.setSpan(
                        BlankWidthSpan(left),
                        curS,
                        curS + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    shiftUpdated(updated, fromExclusive = curS, by = 1); curE += 1
                }
                val shiftDown = top - bottom
                if (shiftDown != 0) {
                    ssb.setSpan(
                        VerticalShiftSpan(shiftDown),
                        curS,
                        curE,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
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
     * 对整段中的文字部分（跳过图片）统一施加垂直偏移，实现文字与图片视觉居中微调。
     *
     * @param top    上方偏移 px，正值使文字向下移，默认 0
     * @param bottom 下方偏移 px，正值使文字向上移，默认 0
     */
    fun textVerticalMarginPx(@Px top: Int = 0, @Px bottom: Int = 0): SpanBuilder {
        val shiftDown = top - bottom
        if (shiftDown == 0 || ssb.isEmpty()) return this
        val len = ssb.length
        val imageSpans = ssb.getSpans(0, len, CenterAlignImageSpan::class.java)
        imageSpans.sortWith(Comparator { a, b -> ssb.getSpanStart(a) - ssb.getSpanStart(b) })
        var cursor = 0;
        var placedAny = false
        for (span in imageSpans) {
            val s = ssb.getSpanStart(span);
            val e = ssb.getSpanEnd(span)
            if (cursor < s) {
                ssb.setSpan(
                    VerticalShiftSpan(shiftDown),
                    cursor,
                    s,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
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

    /**
     * 给当前文字片段附加一个用户自定义 Span（使用全局工厂）。
     * 需先通过 [setTextSpanFactory] 注册工厂，工厂接收 (start, end, fullText)，返回任意 Span 对象。
     */
    fun customTextSpan(): SpanBuilder = applyEach { s, e ->
        val span = textSpanFactory?.invoke(s, e, ssb) ?: return@applyEach
        ssb.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * 给当前文字片段附加一个用户自定义 Span（内联版本，无需预先注册）。
     *
     * @param factory 接收 (start, end, fullText)，返回任意 Span 对象
     */
    fun customTextSpan(factory: (start: Int, end: Int, text: CharSequence) -> Any): SpanBuilder =
        applyEach { s, e ->
            ssb.setSpan(factory(s, e, ssb), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

    /**
     * 对当前图片片段执行用户自定义 Drawable 变换（使用全局变换器）。
     * 需先通过 [setImageTransformer] 注册变换器。
     */
    fun customImageTransform(): SpanBuilder = applyEach { s, e ->
        val transformer = imageTransformer ?: return@applyEach
        applyImageTransform(s, e, transformer)
    }

    /**
     * 对当前图片片段执行用户自定义 Drawable 变换（内联版本，无需预先注册）。
     * 新 Drawable 的 bounds 会自动设置为原图的 bounds。
     *
     * @param transformer 接收 (drawable, width, height)，返回变换后的新 Drawable
     */
    fun customImageTransform(transformer: (drawable: Drawable, width: Int, height: Int) -> Drawable): SpanBuilder =
        applyEach { s, e -> applyImageTransform(s, e, transformer) }

    private fun applyImageTransform(s: Int, e: Int, transformer: (Drawable, Int, Int) -> Drawable) {
        ssb.getSpans(s, e, CenterAlignImageSpan::class.java).forEach { span ->
            if (pendingImageLoads.any { it.placeholder === span }) {
                pendingImageTransforms[span] = transformer
                return@forEach
            }
            val orig = span.drawable;
            val b = orig.bounds
            val w = b.width().coerceAtLeast(1);
            val h = b.height().coerceAtLeast(1)
            val transformed = transformer(orig, w, h)
            transformed.bounds = b
            ssb.removeSpan(span)
            ssb.setSpan(CenterAlignImageSpan(transformed), s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * 给当前片段添加点击事件。调用后 [into] 会自动为 TextView 设置 LinkMovementMethod。
     *
     * @param underline     点击区域是否显示下划线，默认 false
     * @param overrideColor 点击区域文字颜色，null = 保持原色
     * @param listener      点击回调，参数为被点击的 View
     */
    fun onClick(
        underline: Boolean = false, @ColorInt overrideColor: Int? = null, listener: (View) -> Unit,
    ): SpanBuilder {
        hasClickable = true
        val holder = ClickListenerHolder(listener)
        clickHolders.add(holder)
        return applyEach { s, e ->
            ssb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    holder.listener?.invoke(widget)
                }

                override fun updateDrawState(ds: TextPaint) {
                    overrideColor?.let { ds.color = it }
                    ds.isUnderlineText = underline
                }
            }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private class ClickListenerHolder(var listener: ((View) -> Unit)?)

    private inline fun applyEach(block: (Int, Int) -> Unit): SpanBuilder {
        segments.forEach { (s, e) -> if (s < e) block(s, e) }
        return this
    }

    /**
     * 返回构建好的 [CharSequence]（SpannableStringBuilder）。
     *
     * **注意 1**：含网络图片时图片尚未加载，建议改用 [into]。
     *
     * **注意 2**：[onClick] 添加的 [android.text.style.ClickableSpan] 必须配合 TextView 的
     * [LinkMovementMethod] 才能响应点击。[into] 会自动设置 LinkMovementMethod，
     * 而直接用 `textView.text = build()` 时**不会**设置，导致 onClick 不生效。
     *
     * 如果用了 [onClick] 又必须用 build 模式，请这样做：
     * ```
     * tv.text = SpanBuilder.with(ctx).append("xxx").onClick { ... }.build()
     * tv.movementMethod = LinkMovementMethod.getInstance()  // 手动设置
     * ```
     *
     * 或者使用 [buildAndAttach]，它会自动挂上 movementMethod 并返回 CharSequence。
     */
    fun build(): CharSequence = ssb

    /**
     * 拿到当前 SpanBuilder 持有的字符动画 driver(若调过 [charAnimation])。
     *
     * **使用场景**:走 [build] 不走 [into] 的业务方,自己 `textView.text = build()` 后,
     * 必须手动 `getCharAnimDriver()?.start(textView)`,并在 view detach / RecyclerView
     * onViewRecycled 时 `stop()`,否则 driver 不会被自动清理 → Choreographer 持续空跑、
     * WeakReference 持有过期 TextView。
     *
     * 走 [into] / [buildAndAttach] 的场景由 SpanBuilder 内部托管,无需调用此方法。
     */
    fun getCharAnimDriver(): CharAnimationDriver? = charAnimDriver

    /**
     * 构建 CharSequence 并把点击/发光/图片加载等所需的运行时配置都挂到 [textView] 上，
     * 但**不**直接给 textView 赋值 text，由调用方自行决定何时设置（例如插入到富文本中）。
     *
     * 与 [build] 的区别：
     * - 自动设置 [LinkMovementMethod]，[onClick] 立即生效
     * - 有 ClickableSpan 时自动把 highlightColor 设为透明，消除点击闪烁
     * - 自动应用 [glow] / [shadow] 所需的 LAYER_TYPE_SOFTWARE
     * - 自动应用 [marginPx] / [textVerticalMarginPx] 累积的额外行高
     * - 自动触发已注册的网络图片异步加载，加载完成后回调 [onUpdate]
     *
     * 与 [into] 的区别：
     * - [into] 直接给 `textView.text` 赋值；本方法把 CharSequence 返回给调用方使用
     * - 适用场景：把构建好的内容拼接到外部 SpannableStringBuilder、或同一 TextView 多次拼接
     *
     * @param textView 用于挂载 movementMethod / 软件渲染层 / 异步图片加载的目标 TextView
     * @param onUpdate 网络图片加载完成时的回调（在主线程），参数为更新后的 CharSequence。
     *                 如果用了 URL 图片但不传此回调，加载完成后调用方拿到的 CharSequence 不会自动刷新
     * @return 构建好的 CharSequence（SpannableStringBuilder）
     */
    fun buildAndAttach(
        textView: TextView,
        onUpdate: ((CharSequence) -> Unit)? = null,
    ): CharSequence {
        val tvRef = prepareTextView(textView)
        if (pendingImageLoads.isEmpty()) return ssb
        dispatchPendingImageLoads(textView, tvRef) { onUpdate?.invoke(ssb) }
        return ssb
    }

    /**
     * 将构建好的富文本设置到 [textView]，并处理以下逻辑：
     * - 自动设置 LinkMovementMethod（支持点击事件）；
     * - 有 [glow] / [shadow] 效果时自动设置 LAYER_TYPE_SOFTWARE；
     * - 有待加载的网络图片时，图片加载完成后自动刷新 textView.text；
     * - 若同一 TextView 被重复调用 [into]，通过 tag 标记确保旧任务的回调不会污染新内容。
     *
     * @param textView 目标 TextView
     */
    fun into(textView: TextView) {
        val tvRef = prepareTextView(textView)
        if (pendingImageLoads.isEmpty()) {
            textView.text = ssb
            return
        }
        var initialTextSet = false
        dispatchPendingImageLoads(textView, tvRef) {
            if (initialTextSet) {
                textView.text = ""
                textView.text = ssb
            }
        }
        initialTextSet = true
        textView.text = ssb
    }

    private fun prepareTextView(textView: TextView): WeakReference<TextView> {
        textView.movementMethod = LinkMovementMethod.getInstance()

        @Suppress("UNCHECKED_CAST")
        val previousHolders =
            textView.getTag(R.id.span_builder_click_holders) as? MutableList<ClickListenerHolder>
        previousHolders?.forEach { it.listener = null }
        textView.setTag(
            R.id.span_builder_click_holders,
            clickHolders.ifEmpty { null },
        )

        val highlightTag = R.id.span_builder_highlight_set
        val prevHadClickable = textView.getTag(highlightTag) == true
        if (hasClickable) {
            textView.highlightColor = Color.TRANSPARENT
            textView.setTag(highlightTag, true)
        } else if (prevHadClickable) {
            textView.highlightColor = 0x6633B5E5
            textView.setTag(highlightTag, false)
        }

        applyExtraVerticalPadding(textView)

        val layerTag = R.id.span_builder_software_layer_set
        val prevHadSoftware = textView.getTag(layerTag) == true
        if (needsSoftwareLayer && !textView.isInEditMode) {
            textView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            textView.setTag(layerTag, true)
        } else if (prevHadSoftware && !textView.isInEditMode) {
            textView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            textView.setTag(layerTag, false)
        }

        val tvRef = WeakReference(textView)
        animatables.forEach { wireAnimatable(it, tvRef) }
        attachAnimationLifecycle(textView)
        charAnimDriver?.let { driver ->
            textView.post { driver.start(textView) }
        }
        return tvRef
    }

    /**
     * [into] / [buildAndAttach] 共用的网络图片加载循环。
     */
    private fun dispatchPendingImageLoads(
        textView: TextView,
        tvRef: WeakReference<TextView>,
        onResolved: () -> Unit,
    ) {
        val tagKey = R.id.span_builder_load_token
        textView.setTag(tagKey, ssb)
        pendingImageLoads.forEach { load ->
            val loader = load.loader ?: requireLoader(LoaderType.Image) ?: return@forEach
            loader.load(context, load.url, load.width, load.height, load.circle) { resource ->
                if (textView.getTag(tagKey) !== ssb) return@load
                val curStart = ssb.getSpanStart(load.placeholder)
                val curEnd = ssb.getSpanEnd(load.placeholder)
                if (curStart !in 0 until curEnd || curEnd > ssb.length) return@load
                resource.setBounds(0, 0, load.width, load.height)
                ssb.removeSpan(load.placeholder)
                val borderConfig = pendingImageBorders.remove(load.placeholder)
                val transformer = pendingImageTransforms.remove(load.placeholder)
                var finalDrawable: Drawable = if (borderConfig != null) {
                    makeBorderedDrawable(resource, borderConfig).also {
                        it.setBounds(0, 0, load.width, load.height)
                    }
                } else resource
                if (transformer != null) {
                    finalDrawable = transformer(finalDrawable, load.width, load.height)
                    finalDrawable.setBounds(0, 0, load.width, load.height)
                }
                ssb.setSpan(
                    CenterAlignImageSpan(finalDrawable), curStart, curEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                registerAsyncAnimatable(resource, finalDrawable, tvRef)
                onResolved()
            }
        }
    }

    /**
     * 异步加载完成后,如果 drawable 是 Animatable(GifDrawable / WebpDrawable / AnimatedImageDrawable 等),
     */
    private fun registerAsyncAnimatable(
        resource: Drawable,
        finalDrawable: Drawable,
        tvRef: WeakReference<TextView>,
    ) {
        val tv = tvRef.get()
        if (tv != null) {
            (resource as? HostAware)?.bindHost(tv)
        }
        val animatable = (finalDrawable as? Animatable)
            ?: (resource as? Animatable)
            ?: return
        wireAnimatable(animatable, tvRef)
        if (!animatables.contains(animatable)) animatables.add(animatable)
    }

    /**
     * 把 [Animatable] 的帧刷新转发到 TextView。
     */
    private fun wireAnimatable(animatable: Animatable, tvRef: WeakReference<TextView>) {
        val drawable = animatable as? Drawable ?: return
        drawable.callback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {
                tvRef.get()?.invalidate()
            }

            override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) {
                tvRef.get()?.postDelayed(what, time - SystemClock.uptimeMillis())
            }

            override fun unscheduleDrawable(who: Drawable, what: Runnable) {
                tvRef.get()?.removeCallbacks(what)
            }
        }
        if (!animatable.isRunning) animatable.start()
    }

    /**
     * 给 TextView 挂 attach/detach 监听:detach 时统一 stop 所有动图、attach 时 restart。
     */
    private fun attachAnimationLifecycle(textView: TextView) {
        val listenerTag = R.id.span_builder_anim_listener
        val animListTag = R.id.span_builder_anim_list
        val charDriverTag = R.id.span_builder_char_driver

        @Suppress("UNCHECKED_CAST")
        val previousList = textView.getTag(animListTag) as? MutableList<Animatable>
        previousList?.forEach { prev ->
            if (prev.isRunning) prev.stop()
            (prev as? Releasable)?.release()
            (prev as? RoundMaskDrawable)?.release()
        }

        (textView.getTag(charDriverTag) as? CharAnimationDriver)?.stop()

        val previous = textView.getTag(listenerTag) as? View.OnAttachStateChangeListener
        if (previous != null) textView.removeOnAttachStateChangeListener(previous)

        val animListRef = animatables
        val driverRef = charAnimDriver
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                animListRef.forEach { if (!it.isRunning) it.start() }
                driverRef?.start(textView)
            }

            override fun onViewDetachedFromWindow(v: View) {
                animListRef.forEach { if (it.isRunning) it.stop() }
                driverRef?.pause()
            }
        }
        textView.addOnAttachStateChangeListener(listener)
        textView.setTag(listenerTag, listener)
        textView.setTag(animListTag, animListRef)
        textView.setTag(charDriverTag, charAnimDriver)
    }

    private fun transparentPlaceholder(w: Int, h: Int): Drawable =
        Color.TRANSPARENT.toDrawable().apply { setBounds(0, 0, w, h) }

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
}
