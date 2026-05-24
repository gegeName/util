package com.lhj.effect

/**
 * 特效模块内日志开关。默认关闭，开启后才会输出到 logcat；
 * inline + lambda 形参保证关闭时字符串模板零拼接、零分配。
 *
 * 开启示例：
 * ```
 * EffectLog.enabled = BuildConfig.DEBUG
 * ```
 */
object EffectLog {

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
