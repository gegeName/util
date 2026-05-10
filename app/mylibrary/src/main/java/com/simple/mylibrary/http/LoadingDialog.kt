package com.simple.mylibrary.http

import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import com.simple.mylibrary.R
import com.simple.mylibrary.http.LoadingDialog.dismissLoading
import com.simple.mylibrary.http.LoadingDialog.showLoadingDialog
import java.util.WeakHashMap

/**
 * 每个 Activity 最多一个 loading dialog，重复调用 [showLoadingDialog] 会先 dismiss 旧的再显示新的。
 * [dismissLoading] 安全幂等，dialog 已关闭时调用不抛异常。
 *
 * 线程契约：本对象内部用 [WeakHashMap] 维护 Activity → Dialog 映射，本身**非线程安全**；
 * 同时 [Dialog.show] / [Dialog.dismiss] 必须在主线程调用，否则 WindowManager 会抛异常。
 * 因此 [showLoadingDialog] / [dismissLoading] 都标注 [MainThread]，从子线程调用时会自动 post
 * 到主线程兜底，避免误用导致崩溃。
 */
object LoadingDialog {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dialogs = WeakHashMap<AppCompatActivity, Dialog>()

    @MainThread
    fun showLoadingDialog(
        activity: AppCompatActivity,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null,
    ) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showLoadingDialog(activity, cancelable, onCancel) }
            return
        }
        dismissLoading(activity)
        if (activity.isDestroyed || activity.isFinishing) return

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_loading)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            setCancelable(cancelable)
            if (cancelable && onCancel != null) {
                setOnCancelListener { onCancel() }
            }
        }
        dialogs[activity] = dialog
        dialog.show()
    }

    @MainThread
    fun dismissLoading(activity: AppCompatActivity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { dismissLoading(activity) }
            return
        }
        dialogs.remove(activity)?.runCatching { dismiss() }
    }
}
