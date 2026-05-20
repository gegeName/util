package com.lhj.spanutil.span

/**
 * 标识一个对象持有需要主动释放的资源(WebView / Bitmap 等)。
 *
 * SpanBuilder 的 [com.lhj.spanutil.SpanBuilder.attachAnimationLifecycle]
 * 在 RecyclerView 复用 / TextView 重新装载时,会对前一批 animatables 统一调用
 * [release],避免 WebView、Bitmap、Choreographer 回调泄漏。
 */
interface Releasable {
    fun release()
}
