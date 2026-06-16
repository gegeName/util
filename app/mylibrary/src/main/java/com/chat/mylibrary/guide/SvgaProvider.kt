package com.chat.mylibrary.guide

import android.content.Context
import android.view.View

/**
 * SVGA 渲染解耦接口。mylibrary 不强依赖 SVGA 库，由业务侧实现并通过
 * [GuideView.setSvgaProvider] 注入。
 */
interface SvgaProvider {

    /**
     * @param source   SVGA 资源标识（assets 文件名 / URL / 本地路径，由实现方解析）。
     * @param loops    循环次数，0 表示无限循环。
     * @param onFinished 播放结束回调（loops>0 时触发）。
     */
    fun create(
        context: Context,
        source: String,
        loops: Int = 0,
        onFinished: (() -> Unit)? = null,
    ): View

    /** 引导切换/关闭时调用，用于停止动画、释放资源。 */
    fun release(view: View)
}
