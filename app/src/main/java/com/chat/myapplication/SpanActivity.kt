package com.chat.myapplication

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chat.glidespan.GlideSpanGifLoader
import com.chat.glidespan.GlideSpanImageLoader
import com.chat.pagingutil.BasePagingAdapter
import com.chat.pagingutil.pagingFlowOf
import com.chat.spanutil.SpanBuilder
import com.chat.spanutil.span.CharAnim
import com.chat.spanutil.span.CharAnims
import com.chat.spanutil.span.EmojiRegistry
import com.chat.spanutil.span.RepeatConfig
import com.chat.svgaspan.SvgaSpanLoader
import com.chat.svgspan.DefaultSvgLoader
import com.hifylive.myapplication.databinding.ItemSpanDemoBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * SpanBuilder 全方法演示（RecyclerView + Paging）。
 *
 * 41 个 demo 抽成 [SpanDemoItem]，由 [BasePagingAdapter] 单 ViewType 复用展示。
 * 滚出屏幕的 ViewHolder 自动 detach，SpanBuilder 内置的 attach/detach 监听暂停
 * 所有 Animatable / charAnimation，避免无意义重绘。
 */
class SpanActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlideSpanImageLoader.install()
        GlideSpanGifLoader.install()
        DefaultSvgLoader.install()
        SvgaSpanLoader.install()
        enableEdgeToEdge()
        setContentView(R.layout.activity_span)
        val rv = findViewById<RecyclerView>(R.id.rv_span)
        ViewCompat.setOnApplyWindowInsetsListener(rv) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        EmojiRegistry.registerAll(
            mapOf(
                ":smile:" to R.drawable.ic_launcher_foreground,
                "[heart]" to R.drawable.ic_launcher_foreground,
                ":star:" to R.drawable.ic_launcher_foreground,
            )
        )

        val adapter = SpanDemoAdapter()
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        val items = buildDemoList()
        val flow = pagingFlowOf<SpanDemoItem>(
            scope = lifecycleScope,
            pageSize = items.size.coerceAtLeast(1),
            initialLoadSize = items.size.coerceAtLeast(1),
        ) { _, _ -> items to false }
        lifecycleScope.launch {
            flow.collectLatest { adapter.submitData(it) }
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Float.dp(): Float = this * resources.displayMetrics.density
    private fun Int.sp(): Int = (this * resources.displayMetrics.scaledDensity).toInt()

    private val sampleAvatarUrl = "https://avatars.githubusercontent.com/u/1?s=96"

    private inner class SpanDemoAdapter :
        BasePagingAdapter<SpanDemoItem, ItemSpanDemoBinding>(DIFF, ItemSpanDemoBinding::inflate) {

        override fun onBind(binding: ItemSpanDemoBinding, item: SpanDemoItem, position: Int) {
            binding.tvTitle.text = item.title
            item.apply(this@SpanActivity, binding.tvDemo)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SpanDemoItem>() {
            override fun areItemsTheSame(old: SpanDemoItem, new: SpanDemoItem) = old.id == new.id
            override fun areContentsTheSame(old: SpanDemoItem, new: SpanDemoItem) = old.id == new.id
        }
    }

    /**
     * 把 drawable 裁剪成圆角 / 圆形 Bitmap。使用 BitmapShader 在硬件加速 Canvas 上反锯齿更可靠。
     *
     * @param drawable 源 Drawable
     * @param w        输出宽度
     * @param h        输出高度
     * @param radius   非圆形时的圆角半径
     * @param isCircle true 走完整圆形裁剪，忽略 radius
     */
    private fun roundCornerDrawable(
        drawable: Drawable,
        w: Int,
        h: Int,
        radius: Float,
        isCircle: Boolean = false
    ): Drawable {
        val srcBitmap = createBitmap(w, h)
        val srcCanvas = Canvas(srcBitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(srcCanvas)
        val outBitmap = createBitmap(w, h)
        val canvas = Canvas(outBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = BitmapShader(srcBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        if (isCircle) {
            canvas.drawCircle(w / 2f, h / 2f, min(w, h) / 2f, paint)
        } else {
            canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
        }
        return outBitmap.toDrawable(resources)
    }

    private fun buildDemoList(): List<SpanDemoItem> = listOf(
        SpanDemoItem(40, "40. charAnimation FADE") { tv ->
            SpanBuilder.with(this)
                .setText("逐字淡入循环播放的标题")
                .all().color(0xFF1976D2.toInt()).bold().sizePx(24.sp())
                .charAnimation(
                    CharAnims.Fade,
                    perCharDelayMs = 120,
                    charDurationMs = 600,
                    repeat = RepeatConfig.infiniteRestart(pauseMs = 600),
                )
                .into(tv)
        },
        SpanDemoItem(42, "40b. charAnimation RISE") { tv ->
            SpanBuilder.with(this)
                .setText("从下方升起再回去的标题")
                .all().color(0xFFE91E63.toInt()).bold().sizePx(24.sp())
                .charAnimation(
                    CharAnims.Rise,
                    perCharDelayMs = 120,
                    charDurationMs = 600,
                    repeat = RepeatConfig.infiniteReverse(pauseMs = 300),
                )
                .into(tv)
        },
        SpanDemoItem(43, "40c. charAnimation 自定义 zoom") { tv ->
            val zoomIn = CharAnim { tp, p, _, _ ->
                tp.alpha = (tp.alpha * p).toInt().coerceIn(0, 255)
                tp.textSize *= (0.4f + 0.6f * p)
            }
            SpanBuilder.with(this)
                .setText("字号放大入场,自定义 anim")
                .all().color(0xFF4CAF50.toInt()).bold().sizePx(24.sp())
                .charAnimation(
                    zoomIn,
                    perCharDelayMs = 130,
                    charDurationMs = 700,
                    repeat = RepeatConfig.INFINITE_RESTART,
                )
                .into(tv)
        },
        SpanDemoItem(1, "1. append + 多种文字样式") { tv ->
            SpanBuilder.with(this)
                .append("红色").color(Color.RED)
                .append(" 加粗").bold()
                .append(" 斜体").italic()
                .append(" 粗斜").boldItalic()
                .append(" 下划线").underline()
                .append(" 删除线").strikethrough()
                .into(tv)
        },
        SpanDemoItem(2, "2. appendLine + sizePx + backgroundColor") { tv ->
            SpanBuilder.with(this)
                .appendLine("第一行 大号字").sizePx(22.sp())
                .appendLine("第二行 黄色背景").backgroundColor(0xFFFFEB3B.toInt())
                .append("第三行 默认样式")
                .into(tv)
        },
        SpanDemoItem(3, "3. image(resId, w, h) 本地资源") { tv ->
            SpanBuilder.with(this)
                .append("Launcher 图标 ")
                .image(R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
                .append(" 嵌在文字中间")
                .into(tv)
        },
        SpanDemoItem(4, "4. image(drawable, w, h)") { tv ->
            val d = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)
                ?: return@SpanDemoItem
            SpanBuilder.with(this)
                .append("Drawable 对象 → ")
                .image(d, 36.dp(), 36.dp())
                .append(" ← 显示")
                .into(tv)
        },
        SpanDemoItem(5, "5. image(bitmap, w, h)") { tv ->
            val d = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)
                ?: return@SpanDemoItem
            val bmp = d.toBitmap(width = 64.dp(), height = 64.dp())
            SpanBuilder.with(this)
                .append("Bitmap 直接进 Span ")
                .image(bmp, 32.dp(), 32.dp())
                .append(" 完成")
                .into(tv)
        },
        SpanDemoItem(6, "6. image(url) Glide 异步") { tv ->
            SpanBuilder.with(this)
                .append("加载中: ")
                .image(sampleAvatarUrl, 40.dp(), 40.dp(), circle = true)
                .append(" ← 圆形头像")
                .into(tv)
        },
        SpanDemoItem(7, "7. setText + find + color + bold") { tv ->
            SpanBuilder.with(this)
                .setText("张三 刚刚向 LiveRoom 发送了一份礼物")
                .find("张三").color(Color.RED).bold()
                .find("LiveRoom").color(0xFF00BCD4.toInt()).italic()
                .into(tv)
        },
        SpanDemoItem(8, "8. findAll(ignoreCase)") { tv ->
            SpanBuilder.with(this)
                .setText("Foo foo FOO bar Foo baz foo")
                .findAll("foo", ignoreCase = true).color(0xFFE91E63.toInt()).underline()
                .into(tv)
        },
        SpanDemoItem(9, "9. findRegex") { tv ->
            SpanBuilder.with(this)
                .setText("数量 x10、x99、x200 全部按正则上色加粗")
                .findRegex(Regex("x\\d+")).color(0xFFFF9800.toInt()).bold()
                .into(tv)
        },
        SpanDemoItem(10, "10. range(start, end)") { tv ->
            SpanBuilder.with(this)
                .setText("0123456789ABCDEF")
                .range(2, 6).backgroundColor(0xFFFFEB3B.toInt())
                .range(10, 14).backgroundColor(0xFFB2DFDB.toInt())
                .into(tv)
        },
        SpanDemoItem(11, "11. all() 整段统一") { tv ->
            SpanBuilder.with(this)
                .setText("整段统一加粗并改色")
                .all().bold().color(0xFF3F51B5.toInt())
                .into(tv)
        },
        SpanDemoItem(12, "12. replaceWithImage(placeholder, resId)") { tv ->
            SpanBuilder.with(this)
                .setText("收到礼物 [gift] x10")
                .replaceWithImage("[gift]", R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
                .find("x10").color(0xFFFFC107.toInt()).bold()
                .into(tv)
        },
        SpanDemoItem(13, "13. replaceWithImage 多 placeholder") { tv ->
            SpanBuilder.with(this)
                .setText("起点 [icon] 中间 [icon] 结尾 [icon] 完")
                .replaceWithImage("[icon]", R.drawable.ic_launcher_foreground, 22.dp(), 22.dp())
                .find("完").color(Color.RED).bold()
                .into(tv)
        },
        SpanDemoItem(14, "14. replaceWithImage(drawable)") { tv ->
            val d = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)
                ?: return@SpanDemoItem
            SpanBuilder.with(this)
                .setText("一张 {img} 一张 {img}")
                .replaceWithImage("{img}", d, 26.dp(), 26.dp())
                .into(tv)
        },
        SpanDemoItem(15, "15. replaceWithImage(url)") { tv ->
            SpanBuilder.with(this)
                .setText("玩家 {avatar} 与 {avatar} 在房间相遇")
                .replaceWithImage("{avatar}", sampleAvatarUrl, 36.dp(), 36.dp(), circle = true)
                .find("相遇").color(0xFF9C27B0.toInt()).bold()
                .into(tv)
        },
        SpanDemoItem(16, "16. maxLength 末端截断") { tv ->
            SpanBuilder.with(this)
                .setText("这是一段会被截断的很长的文本超出 12 个字会变成省略号")
                .all().color(0xFF607D8B.toInt()).bold()
                .all().maxLength(12, ellipsis = "…")
                .into(tv)
        },
        SpanDemoItem(17, "17. marginPx 文字片段") { tv ->
            SpanBuilder.with(this)
                .append("左")
                .append("中间被撑开").backgroundColor(0xFFFFEB3B.toInt())
                .marginPx(left = 16.dp(), right = 16.dp(), top = 2.dp())
                .append("右")
                .into(tv)
        },
        SpanDemoItem(18, "18. marginPx 图片片段") { tv ->
            SpanBuilder.with(this)
                .append("图1")
                .image(R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
                .marginPx(left = 8.dp(), top = 4.dp(), right = 8.dp(), bottom = 4.dp())
                .append("图2")
                .image(R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
                .marginPx(left = 4.dp(), right = 4.dp())
                .append("结束")
                .into(tv)
        },
        SpanDemoItem(19, "19. textVerticalMarginPx") { tv ->
            SpanBuilder.with(this)
                .append("文字 ")
                .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
                .append(" 与图片视觉居中")
                .textVerticalMarginPx(bottom = 4.dp())
                .into(tv)
        },
        SpanDemoItem(20, "20. onClick 默认参数") { tv ->
            SpanBuilder.with(this)
                .append("点 ")
                .append("这里").color(0xFF2196F3.toInt()).bold()
                .onClick { Toast.makeText(this, "Demo 20 clicked", Toast.LENGTH_SHORT).show() }
                .append(" 查看 Toast")
                .into(tv)
        },
        SpanDemoItem(21, "21. onClick(underline, overrideColor)") { tv ->
            SpanBuilder.with(this)
                .setText("访问 [link] 了解更多")
                .find("[link]").color(0xFF9C27B0.toInt())
                .onClick(underline = true, overrideColor = 0xFF9C27B0.toInt()) {
                    Toast.makeText(this, "Demo 21 link tapped", Toast.LENGTH_SHORT).show()
                }
                .into(tv)
        },
        SpanDemoItem(22, "22. build() 直接拿 CharSequence") { tv ->
            tv.text = SpanBuilder.with(this)
                .append("通过 build() 直接拿 ").color(0xFF009688.toInt())
                .append("CharSequence").bold().underline()
                .build()
        },
        SpanDemoItem(23, "23. 综合：服务端文案 + URL + 多 find/replace") { tv ->
            SpanBuilder.with(this)
                .setText("[avatar] 张三 在 LiveRoom 发出 [gift] x99,获得积分 +1000")
                .replaceWithImage("[avatar]", sampleAvatarUrl, 36.dp(), 36.dp(), circle = true)
                .replaceWithImage("[gift]", R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
                .find("张三").color(Color.RED).bold()
                .onClick { Toast.makeText(this, "张三 profile", Toast.LENGTH_SHORT).show() }
                .find("LiveRoom").color(0xFF00BCD4.toInt()).italic()
                .findRegex(Regex("x\\d+")).color(0xFFFFC107.toInt()).bold()
                .findRegex(Regex("\\+\\d+")).color(0xFF4CAF50.toInt()).bold()
                .textVerticalMarginPx(bottom = 4.dp())
                .into(tv)
        },
        SpanDemoItem(24, "24. gradientColor 横向（二色 + 多色）") { tv ->
            SpanBuilder.with(this)
                .append("二色: ")
                .append("橙→红渐变").bold().sizePx(20.sp())
                .gradientColor(0xFFFFC107.toInt(), 0xFFFF1744.toInt())
                .append("  多色: ")
                .append("彩虹横向").bold().sizePx(20.sp())
                .gradientColor(
                    intArrayOf(
                        0xFFE91E63.toInt(),
                        0xFFFFC107.toInt(),
                        0xFF4CAF50.toInt(),
                        0xFF03A9F4.toInt(),
                        0xFF673AB7.toInt(),
                    ),
                    positions = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f),
                )
                .into(tv)
        },
        SpanDemoItem(25, "25. gradientColor 纵向") { tv ->
            SpanBuilder.with(this)
                .append("纵向渐变文字").bold().sizePx(28.sp())
                .gradientColor(0xFF2196F3.toInt(), 0xFF311B92.toInt(), vertical = true)
                .into(tv)
        },
        SpanDemoItem(26, "26. maxLengthMiddle 中间截断") { tv ->
            SpanBuilder.with(this)
                .setText("0123456789ABCDEFGHIJ")
                .all().color(0xFF607D8B.toInt()).bold()
                .all().maxLengthMiddle(8, ellipsis = "…")
                .into(tv)
        },
        SpanDemoItem(27, "27. appendLine() 空参") { tv ->
            SpanBuilder.with(this)
                .append("第一行 默认样式")
                .appendLine()
                .append("第二行 红色加粗").color(Color.RED).bold()
                .appendLine()
                .append("第三行 蓝色斜体").color(0xFF1976D2.toInt()).italic()
                .into(tv)
        },
        SpanDemoItem(28, "28. stroke 文字描边") { tv ->
            SpanBuilder.with(this)
                .append("普通文字  ")
                .append("白色描边").color(Color.BLACK).bold().sizePx(22.sp())
                .stroke(Color.WHITE, 4f)
                .append("  ")
                .append("彩色描边").color(Color.WHITE).bold().sizePx(22.sp())
                .stroke(0xFFE91E63.toInt(), 3f)
                .into(tv)
        },
        SpanDemoItem(29, "29. glow 文字发光") { tv ->
            SpanBuilder.with(this)
                .append("蓝色发光").color(0xFF42A5F5.toInt()).bold().sizePx(22.sp())
                .glow(0xFF1565C0.toInt(), 18f)
                .append("  ")
                .append("金色发光").color(0xFFFFC107.toInt()).bold().sizePx(22.sp())
                .glow(0xFFFF6F00.toInt(), 14f)
                .into(tv)
        },
        SpanDemoItem(30, "30. 渐变 + 描边 + 发光") { tv ->
            SpanBuilder.with(this)
                .append("渐变+描边+发光").bold().sizePx(26.sp())
                .gradientColor(0xFFFF1744.toInt(), 0xFFFF9100.toInt())
                .stroke(Color.WHITE, 3f)
                .glow(0xFFFF1744.toInt(), 16f)
                .into(tv)
        },
        SpanDemoItem(31, "31. imageBorder 纯色") { tv ->
            SpanBuilder.with(this)
                .append("白色直角边框 ")
                .image(R.drawable.ic_launcher_foreground, 40.dp(), 40.dp())
                .imageBorder(Color.WHITE, 3f)
                .append("  红色圆角边框 ")
                .image(R.drawable.ic_launcher_foreground, 40.dp(), 40.dp())
                .imageBorder(0xFFE91E63.toInt(), 4f, 8f.dp())
                .append("  蓝色大圆角 ")
                .image(R.drawable.ic_launcher_foreground, 40.dp(), 40.dp())
                .imageBorder(0xFF1976D2.toInt(), 3f, 20f.dp())
                .into(tv)
        },
        SpanDemoItem(32, "32. imageBorderGradient") { tv ->
            SpanBuilder.with(this)
                .append("横向渐变边框 ")
                .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .imageBorderGradient(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 4f, 12f.dp())
                .append("  纵向渐变边框 ")
                .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .imageBorderGradient(
                    0xFF6200EE.toInt(),
                    0xFF03DAC5.toInt(),
                    4f,
                    12f.dp(),
                    vertical = true
                )
                .append("  多色渐变边框 ")
                .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .imageBorderGradient(
                    intArrayOf(0xFFE91E63.toInt(), 0xFFFFC107.toInt(), 0xFF4CAF50.toInt()),
                    4f, 22f.dp()
                )
                .into(tv)
        },
        SpanDemoItem(33, "33. imageBorder URL 异步") { tv ->
            SpanBuilder.with(this)
                .append("URL 纯色边框 ")
                .image(sampleAvatarUrl, 44.dp(), 44.dp(), circle = true)
                .imageBorder(Color.RED, 3f, 22f.dp())
                .append("  URL 渐变边框 ")
                .image(sampleAvatarUrl, 44.dp(), 44.dp(), circle = true)
                .imageBorderGradient(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 4f, 22f.dp())
                .into(tv)
        },
        SpanDemoItem(34, "34. shadow 文字阴影") { tv ->
            SpanBuilder.with(this)
                .append("黑色阴影  ")
                .append("文字阴影").bold().sizePx(22.sp())
                .shadow(0xBB000000.toInt(), 8f, 4f, 4f)
                .append("  ")
                .append("彩色阴影").color(0xFF1565C0.toInt()).bold().sizePx(22.sp())
                .shadow(0x992196F3.toInt(), 10f, 3f, 3f)
                .append("  ")
                .append("渐变+阴影").bold().sizePx(22.sp())
                .gradientColor(0xFFFF1744.toInt(), 0xFFFF9100.toInt())
                .shadow(0x88000000.toInt(), 6f, 2f, 4f)
                .into(tv)
        },
        SpanDemoItem(35, "35. customImageTransform 圆角") { tv ->
            SpanBuilder.with(this)
                .append("原图 ")
                .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .append("  圆角变换 ")
                .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .customImageTransform { d, w, h -> roundCornerDrawable(d, w, h, 16f.dp()) }
                .append("  URL 全圆 ")
                .image(sampleAvatarUrl, 44.dp(), 44.dp())
                .customImageTransform { d, w, h -> roundCornerDrawable(d, w, h, (w / 2).toFloat()) }
                .onClick {
                    Toast.makeText(this@SpanActivity, "toast", Toast.LENGTH_SHORT).show()
                }
                .into(tv)
        },
        SpanDemoItem(36, "36. customImageTransform GIF") { tv ->
            SpanBuilder.with(this)
                .append("原图 ")
                .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .append("  圆角变换 ")
                .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .append("  URL GIF ")
                .gif(
                    "https://media0.giphy.com/media/v1.Y2lkPTc5MGI3NjExM3lwNWtqMGdocWpvdWp1Ymtrc2hmeTZnbTI2MG5naHZ6ZnZwbWY3bCZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/DzFj5QLRs7AZ2/giphy.gif",
                    44.dp(),
                    44.dp(),
                    true
                )
                .onClick {
                    Toast.makeText(this@SpanActivity, "toast", Toast.LENGTH_SHORT).show()
                }
                .into(tv)
        },
        SpanDemoItem(37, "37. gif 本地 + 网络") { tv ->
            SpanBuilder.with(this)
                .append("本地资源 ")
                .gif(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
                .append("  网络 GIF ")
                .gif(
                    "https://media0.giphy.com/media/v1.Y2lkPTc5MGI3NjExM3lwNWtqMGdocWpvdWp1Ymtrc2hmeTZnbTI2MG5naHZ6ZnZwbWY3bCZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/DzFj5QLRs7AZ2/giphy.gif",
                    44.dp(), 44.dp()
                )
                .append("  圆形网络 GIF ")
                .gif(
                    "https://media0.giphy.com/media/v1.Y2lkPTc5MGI3NjExM3lwNWtqMGdocWpvdWp1Ymtrc2hmeTZnbTI2MG5naHZ6ZnZwbWY3bCZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/DzFj5QLRs7AZ2/giphy.gif",
                    44.dp(), 44.dp(), circle = true
                )
                .into(tv)
        },
        SpanDemoItem(38, "38. svg 本地 raw + 网络") { tv ->
            SpanBuilder.with(this)
                .append("本地 raw ")
                .svg(R.raw.sample_star, 44.dp(), 44.dp())
                .append("  本地圆形 ")
                .svg(R.raw.sample_star, 44.dp(), 44.dp(), circle = true)
                .append("  动态 SVG ")
                .svg(R.raw.sample_pulse, 44.dp(), 44.dp())
                .append("  网络 SVG ")
                .svg(
                    "https://upload.wikimedia.org/wikipedia/commons/0/02/SVG_logo.svg",
                    44.dp(), 44.dp()
                )
                .into(tv)
        },
        SpanDemoItem(39, "39. emoji 注册表 + replaceEmoji") { tv ->
            SpanBuilder.with(this)
                .setText("Hi :smile: 喜欢这个 [heart] :star: 表情吗?")
                .replaceEmoji(22.dp(), 22.dp())
                .find("喜欢").color(0xFFE91E63.toInt()).bold()
                .into(tv)
        },
        SpanDemoItem(41, "41. svga 礼物特效") { tv ->
            SpanBuilder.with(this)
                .append("天使 ")
                .svga(
                    "https://cdn.jsdelivr.net/gh/svga/SVGA-Samples@master/angel.svga",
                    120.dp(), 120.dp(),
                )
                .append(" 国王 ")
                .svga(
                    "https://cdn.jsdelivr.net/gh/svga/SVGA-Samples@master/kingset.svga",
                    120.dp(), 120.dp(),
                )
                .append(" 弹跳 ")
                .svga(
                    "https://cdn.jsdelivr.net/gh/svga/SVGA-Samples@master/PinJump.svga",
                    64.dp(), 64.dp(),
                )
                .into(tv)
        },
    )
}

/**
 * 一条 demo 项的数据描述。
 *
 * @param id    item 稳定 id，用作 DiffUtil 比较
 * @param title 顶部小标题
 * @param apply 把 SpanBuilder 应用到演示 TextView 上的回调；以 [SpanActivity] 为 receiver，
 *              便于直接调用 Activity 内的 dp/sp 等私有扩展
 */
data class SpanDemoItem(
    val id: Int,
    val title: String,
    val apply: SpanActivity.(TextView) -> Unit,
)
