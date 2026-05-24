package com.chat.spanutil.span

/**
 * 标识一个对象持有需要主动释放的资源(WebView / Bitmap 等)。
 */
interface Releasable {
    fun release()
}
