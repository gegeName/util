package com.chat.myapplication

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chat.mylibrary.guide.*

/**
 * 引导图 Demo —— 覆盖 GuideView 全部 API：
 *  Builder: step / maskColor / dismissOnTouchOutside / oncePerKey / onDismiss / show
 *  GuideStep: highlight(多次/各形状/clickThrough/onHighlightClick) / contentImage / contentSvga /
 *             contentView / contentSize / contentAnchor / contentPosition / contentOffset /
 *             contentAnim / onContentClick / autoNextAfter
 *  HighlightShape: Rect / RoundRect / Circle / Oval
 *  Position: Center / Above / Below / LeftOf / RightOf / Custom
 *  GuideAnim: Fade / Scale / FadeScale / Custom
 *  GuideController: currentIndex / totalCount / next / prev / dismiss
 *  静态: setSvgaProvider / hasShown / clearKey / clearAllKeys
 */
class GuideDemoActivity : AppCompatActivity() {

    private lateinit var btnA: Button
    private lateinit var btnB: Button
    private lateinit var btnC: Button
    private lateinit var btnD: Button
    private lateinit var iconBadge: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildRoot())
        // 注入一个 mock SVGA provider —— demo 不依赖真实 SVGA 库
        GuideView.setSvgaProvider(MockSvgaProvider())
    }

    private fun buildRoot(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(0xFFF6F6F6.toInt()) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(48), dp(16), dp(24))
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 顶部一排可被高亮的"按钮"
        val anchorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        btnA = makeAnchor("A", 0xFF4CAF50.toInt())
        btnB = makeAnchor("B", 0xFFFF9800.toInt())
        btnC = makeAnchor("C", 0xFF03A9F4.toInt())
        btnD = makeAnchor("D", 0xFFE91E63.toInt())
        anchorRow.addView(btnA, anchorLp())
        anchorRow.addView(btnB, anchorLp())
        anchorRow.addView(btnC, anchorLp())
        anchorRow.addView(btnD, anchorLp())
        root.addView(anchorRow)

        // 右侧一个小圆图标 —— 验证非 Button 类型 View 高亮
        iconBadge = ImageView(this).apply {
            setImageDrawable(circleDrawable(0xFFE53935.toInt()))
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.END
                topMargin = dp(12)
            }
        }
        root.addView(iconBadge)

        // 场景触发按钮
        root.addView(scenarioButton("1. 全屏图引导（Center + FadeScale + autoNext 2s）") { scenario1() })
        root.addView(scenarioButton("2. 多按钮高亮 / 各形状 / Below+Above+LeftOf / Custom anim") { scenario2() })
        root.addView(scenarioButton("3. SVGA 引导（通过 SvgaProvider 解耦）") { scenario3() })
        root.addView(scenarioButton("4. clickThrough 点穿 + highlight onClick + Position.Custom") { scenario4() })
        root.addView(scenarioButton("5. oncePerKey + Controller next/prev/dismiss + maskColor") { scenario5() })
        root.addView(scenarioButton("5b. dismissOnTouchOutside(true) 点空白整组关闭") { scenario5b() })
        root.addView(scenarioButton("6. clearKey(once_demo) —— 重置场景 5 的一次性记录") {
            GuideView.clearKey(this, "once_demo")
            toast("已清除, hasShown=${GuideView.hasShown(this, "once_demo")}")
        })
        root.addView(scenarioButton("7. clearAllKeys —— 清除全部引导记录") {
            GuideView.clearAllKeys(this)
            toast("已清除全部 key")
        })
        return scroll
    }

    // ---------- scenarios ----------

    /** 1. 全屏图：image / contentSize / Position.Center / FadeScale / autoNext */
    private fun scenario1() {
        GuideView.with(this)
            .step {
                contentImage(R.mipmap.ic_launcher)
                contentSize(dp(180), dp(180))
                contentPosition(Position.Center)
                contentAnim(GuideAnim.FadeScale)
                autoNextAfter(2000L)
            }
            .onDismiss { toast("场景 1 dismiss") }
            .show()
    }

    /** 2. 三步：多按钮高亮 / 全部形状 / 多种 Position / 自定义动画 */
    private fun scenario2() {
        GuideView.with(this)
            .step {
                highlight(btnA, HighlightShape.RoundRect(24f, paddingPx = dp(4)))
                highlight(btnB, HighlightShape.Circle(paddingPx = dp(4)))   // 同一步两个高亮
                contentImage(R.mipmap.ic_launcher)
                contentSize(dp(96), dp(96))
                contentPosition(Position.Below)
                contentOffset(dy = dp(12))
                contentAnim(GuideAnim.Fade)
            }
            .step {
                highlight(btnC, HighlightShape.Oval(paddingPx = dp(2)))
                contentView(makeTip("点 C 看下一步", 0xFF03A9F4.toInt()))
                contentPosition(Position.Above)
                contentOffset(dy = -dp(8))
                contentAnim(GuideAnim.Scale)
            }
            .step {
                highlight(btnD, HighlightShape.Rect(paddingPx = dp(2)))
                highlight(iconBadge, HighlightShape.Circle(paddingPx = dp(2)))
                contentView(makeTip("最后一步", 0xFFE91E63.toInt()))
                contentAnchor(btnD)
                contentPosition(Position.LeftOf)
                contentOffset(dx = -dp(8))
                contentAnim(
                    GuideAnim.Custom(
                        enter = { v ->
                            ObjectAnimator.ofFloat(v, View.TRANSLATION_X, dp(60).toFloat(), 0f)
                                .setDuration(280)
                        },
                        exit = { v ->
                            ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, -dp(60).toFloat())
                                .setDuration(200)
                        },
                    ),
                )
            }
            .onDismiss { toast("场景 2 走完") }
            .show()
    }

    /** 3. SVGA：实际渲染走 MockSvgaProvider */
    private fun scenario3() {
        GuideView.with(this)
            .step {
                highlight(btnB, HighlightShape.RoundRect(20f, paddingPx = dp(4)))
                contentSvga("guide_demo.svga", loops = 0)
                contentSize(dp(200), dp(80))
                contentPosition(Position.Below)
                contentOffset(dy = dp(16))
            }
            .show()
    }

    /** 4. clickThrough 点穿、highlight onClick 回调、Position.Custom、RightOf */
    private fun scenario4() {
        // 临时挂一个不一样的回调到 C，证明"点穿"真的把事件给了底层 View
        btnC.setOnClickListener { toast("C 真正点击触发（点穿生效）") }
        GuideView.with(this)
            .step {
                highlight(btnC, HighlightShape.RoundRect(20f, paddingPx = dp(4)), clickThrough = true)
                highlight(
                    btnD,
                    HighlightShape.RoundRect(20f, paddingPx = dp(4)),
                    onHighlightClick = { toast("D 高亮被点（非点穿，由 onHighlightClick 接管）") },
                )
                contentView(makeTip("C 可点穿 / D 走 onClick", 0xFF333333.toInt()))
                contentAnchor(btnC)
                contentPosition(
                    Position.Custom { anchorRect, _, _ ->
                        Point(anchorRect.left, anchorRect.bottom + dp(20))
                    },
                )
            }
            .show()
    }

    /** 5b. 点空白即整组关闭：第二步永远不会被看到，证明 dismissOnTouchOutside(true) 生效 */
    private fun scenario5b() {
        GuideView.with(this)
            .dismissOnTouchOutside(true)
            .step {
                highlight(btnA, HighlightShape.RoundRect(20f, paddingPx = dp(4)))
                contentView(makeTip("点空白马上关闭（不会进下一步）", 0xFF607D8B.toInt()))
                contentPosition(Position.Below)
                contentOffset(dy = dp(16))
            }
            .step {
                highlight(btnB, HighlightShape.Circle(paddingPx = dp(4)))
                contentView(makeTip("我不会被看到", 0xFF000000.toInt()))
            }
            .onDismiss { toast("整组 dismiss（验证 dismissOnTouchOutside 生效）") }
            .show()
    }

    /** 5. oncePerKey / maskColor / Controller.next+prev+dismiss / 默认点空白 advance */
    private fun scenario5() {
        val ctrl = GuideView.with(this)
            .maskColor(0xCC90EE90.toInt())   // 浅绿色 + 80% alpha
            // 不设 dismissOnTouchOutside → 默认行为：点空白 = advance(next)
            .oncePerKey("once_demo")
            .step {
                highlight(btnA, HighlightShape.Circle(paddingPx = dp(6)))
                contentView(makeTip("step 1/3 → 点提示 next", 0xFF4CAF50.toInt()))
                contentPosition(Position.Below)
                contentOffset(dy = dp(26))
                onContentClick { c -> c.next() }
            }
            .step {
                highlight(btnB, HighlightShape.Circle(paddingPx = dp(6)))
                contentView(makeTip("step 2/3 → 点提示 prev(回 step1) / 点空白 next(进 step3)", 0xFFFF9800.toInt()))
                contentPosition(Position.Below)
                contentOffset(dy = dp(26))
                onContentClick { c -> c.prev() }   // 验证 prev：回 step1 再 next 即可走完循环
            }
            .step {
                highlight(btnD, HighlightShape.Circle(paddingPx = dp(6)))
                contentView(makeTip("step 3/3 → 点提示 dismiss", 0xFFE91E63.toInt()))
                contentPosition(Position.Below)
                contentOffset(dy = dp(26))
                onContentClick { c -> c.dismiss() }
            }
            .onDismiss { toast("once_demo 完成（再次点击不会弹，需点按钮 6 清除）") }
            .show()
        if (ctrl == null) {
            toast("已展示过，点 6 清除后重试")
        } else {
            toast("controller: step ${ctrl.currentIndex + 1}/${ctrl.totalCount}")
        }
    }

    // ---------- helpers ----------

    private fun makeAnchor(text: String, color: Int): Button = Button(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }
        setOnClickListener { toast("$text onClick") }
    }

    private fun anchorLp() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(4); marginEnd = dp(4)
    }

    private fun scenarioButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }

    private fun makeTip(text: String, bg: Int): View = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(8).toFloat()
        }
    }

    private fun circleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int) = (resources.displayMetrics.density * v).toInt()

    /**
     * 用一个会"呼吸"的 TextView 假装是 SVGAImageView，避免 demo 强依赖 SVGA 库。
     * 真实场景里把这里换成 SVGAImageView + SVGAParser 即可。
     */
    private class MockSvgaProvider : SvgaProvider {
        override fun create(
            context: Context,
            source: String,
            loops: Int,
            onFinished: (() -> Unit)?,
        ): View = TextView(context).apply {
            text = "▶ SVGA: $source  loops=$loops"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(30, 30, 30, 30)
            background = GradientDrawable().apply {
                setColor(0xFF673AB7.toInt())
                cornerRadius = 16f
                setStroke(2, Color.WHITE)
            }
            tag = ObjectAnimator.ofFloat(this, View.ALPHA, 0.5f, 1f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                start()
            }
        }

        override fun release(view: View) {
            (view.tag as? ObjectAnimator)?.cancel()
        }
    }
}
