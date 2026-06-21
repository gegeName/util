package com.chat.uifoundation.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * 全局 Toast 工具：自动注入 ApplicationContext（见 [ToastInitProvider]），调用方无需手动 init。
 *
 * 行为：
 * - 任意线程可调，非主线程会自动 post 到主线程
 * - 同一时刻只显示最近一条，新调用会取消上一条，避免排队
 * - 文案为空 / null 直接忽略
 */
object ToastUtils {

    @SuppressLint("StaticFieldLeak")
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentToast: Toast? = null

    internal fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun showShort(text: CharSequence?) = show(text, Toast.LENGTH_SHORT)

    fun showShort(@StringRes resId: Int) =
        show(appContext?.getText(resId), Toast.LENGTH_SHORT)

    fun showLong(text: CharSequence?) = show(text, Toast.LENGTH_LONG)

    fun showLong(@StringRes resId: Int) =
        show(appContext?.getText(resId), Toast.LENGTH_LONG)

    private fun show(text: CharSequence?, duration: Int) {
        val ctx = appContext ?: return
        val msg = text?.toString().orEmpty()
        if (msg.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            display(ctx, msg, duration)
        } else {
            mainHandler.post { display(ctx, msg, duration) }
        }
    }

    private fun display(context: Context, msg: String, duration: Int) {
        currentToast?.cancel()
        currentToast = Toast.makeText(context, msg, duration).also { it.show() }
    }
}
