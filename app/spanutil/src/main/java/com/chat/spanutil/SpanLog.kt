package com.chat.spanutil

/**
 * 库内日志工具：默认关闭，开销为零（inline + lambda 形参，关闭时连字符串都不会拼）。
 *
 * 业务方在 Application 里调 `SpanLog.enabled = BuildConfig.DEBUG` 即可开启。
 */
object SpanLog {

    @JvmField
    var enabled: Boolean = false

    inline fun d(tag: String, msg: () -> String) {
        if (enabled) android.util.Log.d(tag, msg())
    }

    inline fun i(tag: String, msg: () -> String) {
        if (enabled) android.util.Log.i(tag, msg())
    }

    inline fun w(tag: String, throwable: Throwable? = null, msg: () -> String) {
        if (enabled) {
            if (throwable != null) android.util.Log.w(tag, msg(), throwable)
            else android.util.Log.w(tag, msg())
        }
    }

    inline fun e(tag: String, throwable: Throwable? = null, msg: () -> String) {
        if (enabled) {
            if (throwable != null) android.util.Log.e(tag, msg(), throwable)
            else android.util.Log.e(tag, msg())
        }
    }
}
