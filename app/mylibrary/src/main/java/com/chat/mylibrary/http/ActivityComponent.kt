package com.chat.mylibrary.http

import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope

/**
 * Activity 组件基类：把 Activity 里某一块 UI + 业务从 Activity 中拆出来，
 * 让 Activity 只剩"组装 component"这一件事。业务逻辑放 ViewModel，UI 逻辑放 Component。
 */
abstract class ActivityComponent<A : AppCompatActivity, B : ViewDataBinding>(
    protected val activity: A,
    protected val binding: B
) : DefaultLifecycleObserver {

    init {
        activity.lifecycle.addObserver(this)
    }

    protected val scope: LifecycleCoroutineScope
        get() = activity.lifecycleScope
}
