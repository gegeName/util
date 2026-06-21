package com.chat.uifoundation.tablayout
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.LayoutRes

/**
 * Tab item View 生成策略。
 * 业务侧可继承自定义；内置三种实现已覆盖 90% 场景。
 */
interface ITabProvider {
    fun create(context: Context, position: Int, title: CharSequence): View

    /**
     * 让 Animator 拿到「真正承载文字」的 TextView。
     * 自定义复杂 View 的情况，重写返回内部那个 TextView 给 Animator 用。
     * 返回 null 表示 tab 整体被 Animator 操作（如缩放、位移）。
     */
    fun findTitleView(tab: View): TextView? = tab as? TextView
}

/** 纯文字 Tab —— 默认实现 */
class TextTabProvider(
    private val paddingH: Int = 32,
    private val paddingV: Int = 16,
) : ITabProvider {
    override fun create(context: Context, position: Int, title: CharSequence): View =
        TextView(context).apply {
            text = title
            gravity = Gravity.CENTER
            setPadding(paddingH, paddingV, paddingH, paddingV)
            includeFontPadding = false
        }
}

/** 图文 Tab：上 icon 下文字 */
class IconTextTabProvider(
    private val icons: List<Int>,
    private val iconSizePx: Int = 64,
    private val gapPx: Int = 8,
    private val paddingH: Int = 32,
    private val paddingV: Int = 12,
) : ITabProvider {
    override fun create(context: Context, position: Int, title: CharSequence): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(paddingH, paddingV, paddingH, paddingV)

            addView(ImageView(context).apply {
                setImageResource(icons[position])
                layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
            })
            addView(TextView(context).apply {
                text = title
                gravity = Gravity.CENTER
                includeFontPadding = false
                setPadding(0, gapPx, 0, 0)
            })
        }

    override fun findTitleView(tab: View): TextView? =
        (tab as? LinearLayout)?.getChildAt(1) as? TextView
}

/**
 * 自定义 XML Tab
 * - xml 里需要给文字 TextView 设一个固定 id（默认 R.id.tvTabTitle）
 * - bindExtra 用于子 View 的额外赋值（角标、icon 等）
 */
class XmlTabProvider(
    @LayoutRes private val layoutId: Int,
    @IdRes private val titleViewId: Int,
    private val bindExtra: ((tab: View, position: Int) -> Unit)? = null,
) : ITabProvider {
    override fun create(context: Context, position: Int, title: CharSequence): View =
        LayoutInflater.from(context).inflate(layoutId, null, false).apply {
            findViewById<TextView>(titleViewId)?.text = title
            bindExtra?.invoke(this, position)
        }

    override fun findTitleView(tab: View): TextView? = tab.findViewById(titleViewId)
}
