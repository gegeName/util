package com.hifylive.myapplication

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.simple.mylibrary.utils.SpanBuilder

class SpanActivity : AppCompatActivity() {
    private lateinit var tvContent: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_span)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        tvContent = findViewById(R.id.tvContent)

        demoAll()
    }

    private fun demoAll() {

        val builder = SpanBuilder.with(this)

        // ==========================
        // append 基础文本
        // ==========================

        builder
            .appendLine("===== append 基础文本 =====")
            .append("普通文本")
            .appendLine()

        // ==========================
        // color / bold / italic
        // ==========================

        builder
            .appendLine()
            .appendLine("===== 字体样式 =====")

            .append("红色文字 ")
            .color(Color.RED)
            .append("加粗文字 ")
            .bold()

            .append("斜体文字 ")
            .italic()

            .append("粗斜体 ")
            .boldItalic()

            .append("下划线 ")
            .underline()

            .append("删除线 ")
            .strikethrough()

            .append("背景色 ")
            .backgroundColor(Color.YELLOW)

            .append("大字号 ")
            .sizePx(60)

        // ==========================
        // onClick
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== 点击事件 =====")

            .append("点击我")
            .color(Color.BLUE)
            .underline()
            .onClick {
                Toast.makeText(this, "点击了", Toast.LENGTH_SHORT).show()
            }

        // ==========================
        // image 本地图片
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== 本地图片 =====")

            .append("前面 ")
            .image(R.mipmap.ic_launcher, 100, 100)
            .append(" 后面")

        // ==========================
        // 网络图片
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== 网络图片 =====")

            .append("头像 ")
            .image(
                "https://picsum.photos/200",
                120,
                120,
                true
            )
            .append(" 用户")

        // ==========================
        // marginPx
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== margin =====")

            .append("左右margin")
            .backgroundColor(Color.LTGRAY)
            .marginPx(left = 40, right = 40)

        // ==========================
        // maxLength
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== 最大长度 =====")

            .append("这是一段非常非常非常非常长的文本")
            .color(Color.RED)
            .maxLength(6)

        // ==========================
        // 服务端整段文案模式
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== 服务端文案模式 =====")

        builder
            .setText("张三 送给 李四 [gift] x10")

            .find("张三")
            .color(Color.RED)
            .bold()

            .find("李四")
            .color(Color.BLUE)
            .bold()

            .findRegex(Regex("x\\d+"))
            .color(Color.YELLOW)
            .bold()

            .replaceWithImage(
                "[gift]",
                R.mipmap.ic_launcher,
                80,
                80
            )

        // ==========================
        // findAll
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== findAll =====")

        builder
            .setText("hello hello hello")

            .findAll("hello")
            .color(Color.MAGENTA)
            .bold()

        // ==========================
        // range
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== range =====")

        builder
            .setText("ABCDEFGHIJKLMN")

            .range(2, 8)
            .color(Color.GREEN)
            .bold()

        // ==========================
        // textVerticalMarginPx
        // ==========================

        builder
            .appendLine()
            .appendLine()
            .appendLine("===== textVerticalMarginPx =====")

            .append("文字 ")
            .image(R.mipmap.ic_launcher, 100, 100)
            .append(" 对齐")

            .textVerticalMarginPx(top = 10)

        // ==========================
        // 最终 into
        // ==========================

        builder.into(tvContent)
    }
}