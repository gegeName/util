package com.hifylive.myapplication

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
import com.simple.mylibrary.utils.SpanBuilder
import kotlin.math.min

/**
 * SpanBuilder 全方法演示。
 *
 * 每个 demo 对应 activity_span.xml 里一个 TextView,
 * 标题与方法的对应关系直接看 XML 的 <!-- N. xxx --> 注释。
 */
class SpanActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_span)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        demo1AppendStyles()
        demo2AppendLine()
        demo3ImageRes()
        demo4ImageDrawable()
        demo5ImageBitmap()
        demo6ImageUrl()
        demo7Find()
        demo8FindAll()
        demo9FindRegex()
        demo10Range()
        demo11All()
        demo12ReplaceRes()
        demo13ReplaceMulti()
        demo14ReplaceDrawable()
        demo15ReplaceUrl()
        demo16MaxLength()
        demo17MarginText()
        demo18MarginImage()
        demo19TextVerticalMargin()
        demo20OnClickSimple()
        demo21OnClickStyled()
        demo22Build()
        demo23Combo()
        demo24GradientHorizontal()
        demo25GradientVertical()
        demo26MaxLengthMiddle()
        demo27AppendLineEmpty()
        demo28Stroke()
        demo29Glow()
        demo30DecorationCombo()
        demo31ImageBorderSolid()
        demo32ImageBorderGradient()
        demo33ImageBorderUrl()
        demo34CustomTextSpan()
        demo35CustomImageTransform()
    }

    // ============== 小工具 ==============

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private fun Int.sp(): Int = (this * resources.displayMetrics.scaledDensity).toInt()

    private fun tv(id: Int): TextView = findViewById(id)

    // 公网可访问的小尺寸图,Glide 会缓存,后续启动几乎无开销
    private val sampleAvatarUrl = "https://avatars.githubusercontent.com/u/1?s=96"

    // ============== Demos ==============

    /** 1. append + 多种文字样式 */
    private fun demo1AppendStyles() {
        SpanBuilder.with(this)
            .append("红色").color(Color.RED)
            .append(" 加粗").bold()
            .append(" 斜体").italic()
            .append(" 粗斜").boldItalic()
            .append(" 下划线").underline()
            .append(" 删除线").strikethrough()
            .into(tv(R.id.tv_demo_append_styles))
    }

    /** 2. appendLine + sizePx + backgroundColor */
    private fun demo2AppendLine() {
        SpanBuilder.with(this)
            .appendLine("第一行 大号字").sizePx(22.sp())
            .appendLine("第二行 黄色背景").backgroundColor(0xFFFFEB3B.toInt())
            .append("第三行 默认样式")
            .into(tv(R.id.tv_demo_append_line))
    }

    /** 3. image(resId, w, h) 本地资源 */
    private fun demo3ImageRes() {
        SpanBuilder.with(this)
            .append("Launcher 图标 ")
            .image(R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
            .append(" 嵌在文字中间")
            .into(tv(R.id.tv_demo_image_res))
    }

    /** 4. image(drawable, w, h) 直接传 Drawable 对象 */
    private fun demo4ImageDrawable() {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground) ?: return
        SpanBuilder.with(this)
            .append("Drawable 对象 → ")
            .image(drawable, 36.dp(), 36.dp())
            .append(" ← 显示")
            .into(tv(R.id.tv_demo_image_drawable))
    }

    /** 5. image(bitmap, w, h) 传入 Bitmap */
    private fun demo5ImageBitmap() {
        // R.mipmap.ic_launcher 在 Android Studio 模板里是 <adaptive-icon> XML drawable,
        // 不是栅格图,BitmapFactory.decodeResource 会返回 null。
        // 用 Drawable.toBitmap(w, h) 把它(以及 vector / adaptive-icon)栅格化成 Bitmap。
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground) ?: return
        val bmp = drawable.toBitmap(width = 64.dp(), height = 64.dp())
        SpanBuilder.with(this)
            .append("Bitmap 直接进 Span ")
            .image(bmp, 32.dp(), 32.dp())
            .append(" 完成")
            .into(tv(R.id.tv_demo_image_bitmap))
    }

    /** 6. image(url) URL 图,Glide 异步加载 */
    private fun demo6ImageUrl() {
        SpanBuilder.with(this)
            .append("加载中: ")
            .image(sampleAvatarUrl, 40.dp(), 40.dp(), circle = true)
            .append(" ← 圆形头像")
            .into(tv(R.id.tv_demo_image_url))
    }

    /** 7. setText + find + color + bold */
    private fun demo7Find() {
        SpanBuilder.with(this)
            .setText("张三 刚刚向 LiveRoom 发送了一份礼物")
            .find("张三").color(Color.RED).bold()
            .find("LiveRoom").color(0xFF00BCD4.toInt()).italic()
            .into(tv(R.id.tv_demo_find))
    }

    /** 8. setText + findAll(ignoreCase) */
    private fun demo8FindAll() {
        SpanBuilder.with(this)
            .setText("Foo foo FOO bar Foo baz foo")
            .findAll("foo", ignoreCase = true).color(0xFFE91E63.toInt()).underline()
            .into(tv(R.id.tv_demo_find_all))
    }

    /** 9. setText + findRegex */
    private fun demo9FindRegex() {
        SpanBuilder.with(this)
            .setText("数量 x10、x99、x200 全部按正则上色加粗")
            .findRegex(Regex("x\\d+")).color(0xFFFF9800.toInt()).bold()
            .into(tv(R.id.tv_demo_find_regex))
    }

    /** 10. setText + range */
    private fun demo10Range() {
        SpanBuilder.with(this)
            .setText("0123456789ABCDEF")
            .range(2, 6).backgroundColor(0xFFFFEB3B.toInt())
            .range(10, 14).backgroundColor(0xFFB2DFDB.toInt())
            .into(tv(R.id.tv_demo_range))
    }

    /** 11. setText + all() 整段统一上色 */
    private fun demo11All() {
        SpanBuilder.with(this)
            .setText("整段统一加粗并改色")
            .all().bold().color(0xFF3F51B5.toInt())
            .into(tv(R.id.tv_demo_all))
    }

    /** 12. replaceWithImage(placeholder, resId, w, h) */
    private fun demo12ReplaceRes() {
        SpanBuilder.with(this)
            .setText("收到礼物 [gift] x10")
            .replaceWithImage("[gift]", R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
            .find("x10").color(0xFFFFC107.toInt()).bold()
            .into(tv(R.id.tv_demo_replace_res))
    }

    /** 13. 多 placeholder + 后续 find:验证 segments 位移正确 */
    private fun demo13ReplaceMulti() {
        SpanBuilder.with(this)
            .setText("起点 [icon] 中间 [icon] 结尾 [icon] 完")
            .replaceWithImage("[icon]", R.drawable.ic_launcher_foreground, 22.dp(), 22.dp())
            .find("完").color(Color.RED).bold()
            .into(tv(R.id.tv_demo_replace_multi))
    }

    /** 14. replaceWithImage(placeholder, drawable, w, h) */
    private fun demo14ReplaceDrawable() {
        val drawable = ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground) ?: return
        SpanBuilder.with(this)
            .setText("一张 {img} 一张 {img}")
            .replaceWithImage("{img}", drawable, 26.dp(), 26.dp())
            .into(tv(R.id.tv_demo_replace_drawable))
    }

    /** 15. replaceWithImage(placeholder, url, w, h, circle) */
    private fun demo15ReplaceUrl() {
        SpanBuilder.with(this)
            .setText("玩家 {avatar} 与 {avatar} 在房间相遇")
            .replaceWithImage("{avatar}", sampleAvatarUrl, 36.dp(), 36.dp(), circle = true)
            .find("相遇").color(0xFF9C27B0.toInt()).bold()
            .into(tv(R.id.tv_demo_replace_url))
    }

    /** 16. maxLength(maxChars, ellipsis):截断 + 样式延展 */
    private fun demo16MaxLength() {
        SpanBuilder.with(this)
            .setText("这是一段会被截断的很长的文本超出 12 个字会变成省略号")
            .all().color(0xFF607D8B.toInt()).bold()
            .all().maxLength(12, ellipsis = "…")
            .into(tv(R.id.tv_demo_max_length))
    }

    /** 17. marginPx 文字片段:左右距离 + 上下平移 */
    private fun demo17MarginText() {
        SpanBuilder.with(this)
            .append("左")
            .append("中间被撑开").backgroundColor(0xFFFFEB3B.toInt())
            .marginPx(left = 16.dp(), right = 16.dp(), top = 2.dp())
            .append("右")
            .into(tv(R.id.tv_demo_margin_text))
    }

    /** 18. marginPx 图片片段:InsetDrawable 四方向间距 */
    private fun demo18MarginImage() {
        SpanBuilder.with(this)
            .append("图1")
            .image(R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
            .marginPx(left = 8.dp(), top = 4.dp(), right = 8.dp(), bottom = 4.dp())
            .append("图2")
            .image(R.drawable.ic_launcher_foreground, 28.dp(), 28.dp())
            .marginPx(left = 4.dp(), right = 4.dp())
            .append("结束")
            .into(tv(R.id.tv_demo_margin_image))
    }

    /** 19. textVerticalMarginPx:整行所有文字段统一上下平移 */
    private fun demo19TextVerticalMargin() {
        SpanBuilder.with(this)
            .append("文字 ")
            .image(R.drawable.ic_launcher_foreground, 36.dp(), 36.dp())
            .append(" 与图片视觉居中")
            .textVerticalMarginPx(bottom = 4.dp())
            .into(tv(R.id.tv_demo_text_vertical_margin))
    }

    /** 20. onClick 默认参数:不加下划线、保留 color 颜色 */
    private fun demo20OnClickSimple() {
        SpanBuilder.with(this)
            .append("点 ")
            .append("这里").color(0xFF2196F3.toInt()).bold()
            .onClick { Toast.makeText(this, "Demo 20 clicked", Toast.LENGTH_SHORT).show() }
            .append(" 查看 Toast")
            .into(tv(R.id.tv_demo_click_simple))
    }

    /** 21. onClick(underline = true, overrideColor) */
    private fun demo21OnClickStyled() {
        SpanBuilder.with(this)
            .setText("访问 [link] 了解更多")
            .find("[link]").color(0xFF9C27B0.toInt())
            .onClick(underline = true, overrideColor = 0xFF9C27B0.toInt()) {
                Toast.makeText(this, "Demo 21 link tapped", Toast.LENGTH_SHORT).show()
            }
            .into(tv(R.id.tv_demo_click_styled))
    }

    /** 22. build() 直接拿 CharSequence,自己赋值给 TextView */
    private fun demo22Build() {
        val cs = SpanBuilder.with(this)
            .append("通过 build() 直接拿 ").color(0xFF009688.toInt())
            .append("CharSequence").bold().underline()
            .build()
        tv(R.id.tv_demo_build).text = cs
    }

    /** 23. 综合:服务端整段文案 + URL avatar + 多种 find/replace 链式 */
    private fun demo23Combo() {
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
            .into(tv(R.id.tv_demo_combo))
    }

    /** 24. gradientColor 横向二色 + 多色 */
    private fun demo24GradientHorizontal() {
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
            .into(tv(R.id.tv_demo_gradient_horizontal))
    }

    /** 25. gradientColor vertical = true 纵向渐变 */
    private fun demo25GradientVertical() {
        SpanBuilder.with(this)
            .append("纵向渐变文字").bold().sizePx(28.sp())
            .gradientColor(0xFF2196F3.toInt(), 0xFF311B92.toInt(), vertical = true)
            .into(tv(R.id.tv_demo_gradient_vertical))
    }

    /** 26. maxLengthMiddle 中间截断 */
    private fun demo26MaxLengthMiddle() {
        SpanBuilder.with(this)
            .setText("0123456789ABCDEFGHIJ")
            .all().color(0xFF607D8B.toInt()).bold()
            .all().maxLengthMiddle(8, ellipsis = "…")
            .into(tv(R.id.tv_demo_max_length_middle))
    }

    /** 27. appendLine() 空参重载,仅插入换行 */
    private fun demo27AppendLineEmpty() {
        SpanBuilder.with(this)
            .append("第一行 默认样式")
            .appendLine()
            .append("第二行 红色加粗").color(Color.RED).bold()
            .appendLine()
            .append("第三行 蓝色斜体").color(0xFF1976D2.toInt()).italic()
            .into(tv(R.id.tv_demo_append_line_empty))
    }

    /** 28. stroke 文字描边 */
    private fun demo28Stroke() {
        SpanBuilder.with(this)
            .append("普通文字  ")
            .append("白色描边").color(Color.BLACK).bold().sizePx(22.sp())
            .stroke(Color.WHITE, 4f)
            .append("  ")
            .append("彩色描边").color(Color.WHITE).bold().sizePx(22.sp())
            .stroke(0xFFE91E63.toInt(), 3f)
            .into(tv(R.id.tv_demo_stroke))
    }

    /** 29. glow 文字发光 */
    private fun demo29Glow() {
        SpanBuilder.with(this)
            .append("蓝色发光").color(0xFF42A5F5.toInt()).bold().sizePx(22.sp())
            .glow(0xFF1565C0.toInt(), 18f)
            .append("  ")
            .append("金色发光").color(0xFFFFC107.toInt()).bold().sizePx(22.sp())
            .glow(0xFFFF6F00.toInt(), 14f)
            .into(tv(R.id.tv_demo_glow))
    }

    /** 30. 渐变 + 描边 + 发光 三合一 */
    private fun demo30DecorationCombo() {
        SpanBuilder.with(this)
            .append("渐变+描边+发光").bold().sizePx(26.sp())
            .gradientColor(0xFFFF1744.toInt(), 0xFFFF9100.toInt())
            .stroke(Color.WHITE, 3f)
            .glow(0xFFFF1744.toInt(), 16f)
            .into(tv(R.id.tv_demo_decoration_combo))
    }

    /** 31. imageBorder 本地图 + 纯色边框 */
    private fun demo31ImageBorderSolid() {
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
            .into(tv(R.id.tv_demo_image_border_solid))
    }

    /** 32. imageBorderGradient 本地图 + 渐变边框 */
    private fun demo32ImageBorderGradient() {
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
            .into(tv(R.id.tv_demo_image_border_gradient))
    }

    /** 33. imageBorder URL 图异步加载 + 边框 */
    private fun demo33ImageBorderUrl() {
        SpanBuilder.with(this)
            .append("URL 纯色边框 ")
            .image(sampleAvatarUrl, 44.dp(), 44.dp(), circle = true)
            .imageBorder(Color.RED, 3f, 22f.dp())
            .append("  URL 渐变边框 ")
            .image(sampleAvatarUrl, 44.dp(), 44.dp(), circle = true)
            .imageBorderGradient(0xFFFF1744.toInt(), 0xFFFF9100.toInt(), 4f, 22f.dp())
            .into(tv(R.id.tv_demo_image_border_url))
    }

    private fun Float.dp(): Float = this * resources.displayMetrics.density

    /** 34. shadow：文字阴影，使用 SpanBuilder 内置方法 */
    private fun demo34CustomTextSpan() {
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
            .into(tv(R.id.tv_demo_custom_text_span))
    }

    /** 35. customImageTransform 内联版：给图片加圆角遮罩 */
    private fun demo35CustomImageTransform() {
        SpanBuilder.with(this)
            .append("原图 ")
            .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
            .append("  圆角变换 ")
            .image(R.drawable.ic_launcher_foreground, 44.dp(), 44.dp())
            .customImageTransform { drawable, w, h ->
                roundCornerDrawable(drawable, w, h, 16f.dp())
            }
            .append("  URL 全圆 ")
            .image(sampleAvatarUrl, 44.dp(), 44.dp())
            .customImageTransform { drawable, w, h ->
                roundCornerDrawable(drawable, w, h, (w / 2).toFloat())
            }
            .onClick {
                Toast.makeText(this@SpanActivity, "toast", Toast.LENGTH_SHORT).show()
            }
            .into(tv(R.id.tv_demo_custom_image_transform))
    }

    /**
     * 把 drawable 裁剪成圆角 Bitmap，用 PorterDuff SRC_IN 方式确保反锯齿圆角正确。
     * clipPath 方式在硬件加速 Canvas 上对反锯齿支持不稳定，此方法更可靠。
     */
    private fun roundCornerDrawable(
        drawable: Drawable,
        w: Int,
        h: Int,
        radius: Float,
        isCircle: Boolean = false
    ): Drawable {

        // 先把 Drawable 转 Bitmap
        val srcBitmap = createBitmap(w, h)
        val srcCanvas = Canvas(srcBitmap)

        drawable.setBounds(0, 0, w, h)
        drawable.draw(srcCanvas)

        // 输出 Bitmap
        val outBitmap = createBitmap(w, h)
        val canvas = Canvas(outBitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 关键：BitmapShader
        val shader = BitmapShader(
            srcBitmap,
            Shader.TileMode.CLAMP,
            Shader.TileMode.CLAMP
        )

        paint.shader = shader

        if (isCircle) {
            val radiusCircle = min(w, h) / 2f
            canvas.drawCircle(
                w / 2f,
                h / 2f,
                radiusCircle,
                paint
            )
        } else {
            val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
            canvas.drawRoundRect(
                rect,
                radius,
                radius,
                paint
            )
        }

        return outBitmap.toDrawable(resources)
    }

}

