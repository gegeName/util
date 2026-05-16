package com.hifylive.myapplication

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.simple.mylibrary.utils.SpanBuilder

/**
 * SpanBuilder 在 RecyclerView 中的复用测试。
 *
 * 重点验证两件事:
 *
 * 1) lineSpacingExtra 幂等: marginPx / textVerticalMarginPx 会撑高行高,
 *    SpanBuilder 内部用 R.id.span_builder_added_line_spacing tag 做幂等,
 *    反复 bind 不应让 lineSpacingExtra 无限增长。
 *
 * 2) URL 图片异步加载防错位: SpanBuilder 内部用 R.id.span_builder_load_token tag
 *    校验 ViewHolder 是否还绑定原 item,避免 Glide 回调写到复用后 holder 上。
 *
 * 列表里覆盖了 SpanBuilder 的常见用法,每行类型不同,反复滚动时不能串样式、不能堆叠行高。
 */
class SpanRecyclerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_span_recycler)

        val rv = findViewById<RecyclerView>(R.id.rv_span)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = SpanAdapter(this, buildSampleData())
    }

    private fun buildSampleData(): List<SpanItem> {
        val out = mutableListOf<SpanItem>()
        repeat(90) { i ->
            out += when (i % 15) {
                0 -> SpanItem.Plain(i, "用户 $i 发送了一条普通消息")
                1 -> SpanItem.HighlightAt(i, "@主播$i 你好,欢迎来到直播间~")
                2 -> SpanItem.Gift(i, "用户 $i 送出 [gift] x${(i + 1) * 10}")
                3 -> SpanItem.RegexNumber(i, "用户 $i 收到 +${i * 5}金币 x${i + 2}经验")
                4 -> SpanItem.LongTextEllipsis(
                    i,
                    "这是第 $i 条非常非常非常长的内容,需要被截断显示,验证 maxLength 在复用时是否仍然是同一个截断结果",
                )

                5 -> SpanItem.GradientTitle(i, "渐变标题 $i")
                6 -> SpanItem.AvatarUrl(
                    i,
                    "https://avatars.githubusercontent.com/u/${i + 1}?s=96",
                    "URL 头像 #$i 异步加载,滚动时不能错位",
                )

                7 -> SpanItem.WithTextVerticalMargin(
                    i,
                    "第 $i 条 textVerticalMarginPx 行高扩张,反复 bind 不应继续累加",
                )

                8 -> SpanItem.GradientStrokeGlow(i, "渐变+描边+发光 #$i", "普通文字跟在后面")
                9 -> SpanItem.StrokeOnly(i, "仅描边 #$i", "其余文字正常")
                10 -> SpanItem.ImageBorderSolid(i)
                11 -> SpanItem.ImageBorderGradient(i)
                12 -> SpanItem.ImageBorderUrl(
                    i,
                    "https://avatars.githubusercontent.com/u/${i + 1}?s=96",
                )

                13 -> SpanItem.CustomTextSpan(i, "自定义Span阴影 #$i 文字效果")
                else -> SpanItem.CustomImageTransform(i)
            }
        }
        return out
    }
}

sealed class SpanItem(val index: Int) {
    class Plain(idx: Int, val text: String) : SpanItem(idx)
    class HighlightAt(idx: Int, val text: String) : SpanItem(idx)
    class Gift(idx: Int, val text: String) : SpanItem(idx)
    class RegexNumber(idx: Int, val text: String) : SpanItem(idx)
    class LongTextEllipsis(idx: Int, val text: String) : SpanItem(idx)
    class GradientTitle(idx: Int, val text: String) : SpanItem(idx)
    class AvatarUrl(idx: Int, val url: String, val text: String) : SpanItem(idx)
    class WithTextVerticalMargin(idx: Int, val text: String) : SpanItem(idx)

    /** 渐变 + 描边 + 发光 三合一，验证 TextDecorationSpan 复用时不产生额外对象 */
    class GradientStrokeGlow(idx: Int, val decorated: String, val plain: String) : SpanItem(idx)

    /** 仅描边，验证单独使用描边时复用正常 */
    class StrokeOnly(idx: Int, val decorated: String, val plain: String) : SpanItem(idx)

    /** 本地图 + 纯色边框，验证 BorderedImageDrawable 静态 Paint 复用无抖动 */
    class ImageBorderSolid(idx: Int) : SpanItem(idx)

    /** 本地图 + 渐变边框 */
    class ImageBorderGradient(idx: Int) : SpanItem(idx)

    /** URL 图 + 边框，验证异步加载完成后边框正确附加 */
    class ImageBorderUrl(idx: Int, val url: String) : SpanItem(idx)

    /** customTextSpan 内联：文字阴影，验证每次 bind 生成新 CharacterStyle 不抖动 */
    class CustomTextSpan(idx: Int, val text: String) : SpanItem(idx)

    /** customImageTransform 内联：圆角裁剪，验证变换后 bounds 正确同步 */
    class CustomImageTransform(idx: Int) : SpanItem(idx)
}

private class SpanAdapter(
    private val activity: AppCompatActivity,
    private val data: List<SpanItem>,
) : RecyclerView.Adapter<SpanAdapter.VH>() {

    class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val tvIndex: TextView = itemView.findViewById(R.id.tv_index)
        val tvContent: TextView = itemView.findViewById(R.id.tv_span_content)
    }

    private val density = activity.resources.displayMetrics.density
    private val scaledDensity = activity.resources.displayMetrics.scaledDensity
    private fun Int.dp(): Int = (this * density).toInt()
    private fun Float.dp(): Float = this * density
    private fun Int.sp(): Int = (this * scaledDensity).toInt()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_span_recycler, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.tvIndex.text = "row=$position  type=${item.javaClass.simpleName}"
        val tv = holder.tvContent
        when (item) {
            is SpanItem.Plain -> {
                SpanBuilder.with(activity)
                    .append(item.text)
                    .into(tv)
            }

            is SpanItem.HighlightAt -> {
                SpanBuilder.with(activity)
                    .setText(item.text)
                    .findRegex(Regex("@\\S+")).color(0xFF1976D2.toInt()).bold()
                    .into(tv)
            }

            is SpanItem.Gift -> {
                SpanBuilder.with(activity)
                    .setText(item.text)
                    .replaceWithImage(
                        "[gift]", R.drawable.ic_launcher_foreground,
                        24.dp(), 24.dp()
                    )
                    .findRegex(Regex("x\\d+")).color(0xFFFF9800.toInt()).bold()
                    .into(tv)
            }

            is SpanItem.RegexNumber -> {
                SpanBuilder.with(activity)
                    .setText(item.text)
                    .findRegex(Regex("\\+\\d+")).color(0xFF4CAF50.toInt()).bold()
                    .findRegex(Regex("x\\d+")).color(0xFFE91E63.toInt()).bold()
                    .into(tv)
            }

            is SpanItem.LongTextEllipsis -> {
                SpanBuilder.with(activity)
                    .setText(item.text)
                    .all().color(0xFF607D8B.toInt())
                    .all().maxLength(20, ellipsis = "…")
                    .into(tv)
            }

            is SpanItem.GradientTitle -> {
                SpanBuilder.with(activity)
                    .append(item.text)
                    .gradientColor(0xFFFFC107.toInt(), 0xFFFF1744.toInt())
                    .bold()
                    .sizePx(18.sp())
                    .into(tv)
            }

            is SpanItem.AvatarUrl -> {
                SpanBuilder.with(activity)
                    .image(item.url, 28.dp(), 28.dp(), circle = true)
                    .append(" ")
                    .append(item.text).color(Color.DKGRAY)
                    .into(tv)
            }

            is SpanItem.WithTextVerticalMargin -> {
                SpanBuilder.with(activity)
                    .append(item.text).color(0xFF673AB7.toInt())
                    .textVerticalMarginPx(top = 6.dp(), bottom = 6.dp())
                    .into(tv)
            }

            is SpanItem.GradientStrokeGlow -> {
                // 三合一：渐变 + 描边 + 发光，反复 bind 时 TextDecorationSpan 就地复用，不产生中间对象
                SpanBuilder.with(activity)
                    .append(item.decorated).bold().sizePx(20.sp())
                    .gradientColor(0xFFFF1744.toInt(), 0xFFFF9100.toInt())
                    .stroke(0xFFFFFFFF.toInt(), 3f)
                    .glow(0xFFFF1744.toInt(), 14f)
                    .append("  ${item.plain}").color(0xFF212121.toInt())
                    .into(tv)
            }

            is SpanItem.StrokeOnly -> {
                // 单独描边，验证没有渐变/发光时 TextDecorationSpan 同样正常
                SpanBuilder.with(activity)
                    .append(item.decorated).color(0xFF1565C0.toInt()).bold().sizePx(20.sp())
                    .stroke(0xFF1565C0.toInt(), 4f)
                    .append("  ${item.plain}").color(0xFF212121.toInt())
                    .into(tv)
            }

            is SpanItem.ImageBorderSolid -> {
                // 纯色边框：验证静态 Paint 复用，反复 bind 不产生新 Paint/RectF 对象
                SpanBuilder.with(activity)
                    .append("纯色 ")
                    .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
                    .imageBorder(0xFFE91E63.toInt(), 3f, 8f.dp())
                    .append(" 圆角 ")
                    .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
                    .imageBorder(0xFF1976D2.toInt(), 3f, 18f.dp())
                    .append(" #${item.index}")
                    .into(tv)
            }

            is SpanItem.ImageBorderGradient -> {
                // 渐变边框：w/h 不变时 shader 缓存复用，不重建 LinearGradient
                SpanBuilder.with(activity)
                    .append("渐变边框 ")
                    .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
                    .imageBorderGradient(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 3f, 18f.dp())
                    .append(" 纵向 ")
                    .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
                    .imageBorderGradient(
                        0xFF6200EE.toInt(),
                        0xFF03DAC5.toInt(),
                        3f,
                        18f.dp(),
                        vertical = true
                    )
                    .append(" #${item.index}")
                    .into(tv)
            }

            is SpanItem.ImageBorderUrl -> {
                // URL 图 + 边框：验证 Glide 回调后边框正确包装，滚动时不错位
                SpanBuilder.with(activity)
                    .append("URL+边框 ")
                    .image(item.url, 36.dp(), 36.dp(), circle = true)
                    .imageBorder(Color.WHITE, 3f, 18f.dp())
                    .append(" 渐变 ")
                    .image(item.url, 36.dp(), 36.dp(), circle = true)
                    .imageBorderGradient(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 3f, 18f.dp())
                    .append(" #${item.index}")
                    .into(tv)
            }

            is SpanItem.CustomTextSpan -> {
                SpanBuilder.with(activity)
                    .append("阴影效果: ")
                    .append(item.text).color(0xFF1565C0.toInt()).bold().sizePx(17.sp())
                    .shadow(0x992196F3.toInt(), 6f, 3f, 3f)
                    .into(tv)
            }

            is SpanItem.CustomImageTransform -> {
                SpanBuilder.with(activity)
                    .append("原图 ")
                    .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
                    .append("  圆角 ")
                    .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
                    .customImageTransform { drawable, w, h ->
                        roundCorner(drawable, w, h, 12f.dp())
                    }
                    .append(" #${item.index}")
                    .into(tv)
            }
        }
    }

    /** PorterDuff SRC_IN 圆角裁剪，比 clipPath 反锯齿更可靠 */
    private fun roundCorner(drawable: Drawable, w: Int, h: Int, radius: Float): Drawable {
        val bmp = createBitmap(w, h)
        val canvas = android.graphics.Canvas(bmp)
        val rect = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
        canvas.saveLayer(rect, null)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        val maskPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        canvas.drawRoundRect(rect, radius, radius, maskPaint)
        canvas.restore()
        return bmp.toDrawable(activity.resources)
    }
}
