package com.chat.spanutil.span

import android.widget.TextView

/**
 * 标记 Drawable 需要在异步加载完成时绑定宿主 [TextView]，用于自驱 invalidate。
 * SpanBuilder 通过该接口避免对具体动效类型的强依赖。
 */
fun interface HostAware {
    /**
     * 绑定宿主 TextView，回调内部应保存 WeakReference 并按需启动渲染。
     *
     * @param textView 当前承载该 Drawable 的 TextView
     */
    fun bindHost(textView: TextView)
}
