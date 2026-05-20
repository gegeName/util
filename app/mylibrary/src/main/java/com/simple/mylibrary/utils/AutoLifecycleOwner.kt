package com.simple.mylibrary.utils

import androidx.annotation.CallSuper
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 非 Android 组件（如全局单例 Manager / Repository）想拥有 [LifecycleOwner] + [ViewModelStoreOwner]
 * 能力时的统一父类，自动管理生命周期推进，免去每个子类手写一遍 `LifecycleRegistry` / `ViewModelStore`
 */
abstract class AutoLifecycleOwner : ViewModelStoreOwner, LifecycleOwner {

    private val _viewModelStore = ViewModelStore()
    final override val viewModelStore: ViewModelStore
        get() = _viewModelStore

    private val lifecycleRegistry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.CREATED
    }
    final override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /** 把生命周期推进到 [Lifecycle.State.STARTED]。DESTROYED 后调用是 no-op。 */
    @MainThread
    fun markStarted() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    /** 把生命周期推进到 [Lifecycle.State.RESUMED]。DESTROYED 后调用是 no-op。 */
    @MainThread
    fun markResumed() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * 终结本实例：
     *  1) 调用 [onRelease] 给子类释放业务资源（此时 `lifecycleScope` / VM 仍可访问，便于做最后的清理）
     *  2) 清空 [ViewModelStore]，触发其中 ViewModel 的 `onCleared`
     *  3) 把生命周期推到 [Lifecycle.State.DESTROYED]，撤销所有挂在 `lifecycleScope` 上的协程
     *
     * 幂等：重复调用第二次起即 no-op。
     */
    @MainThread
    @CallSuper
    open fun release() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        runCatching { onRelease() }
        _viewModelStore.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    /**
     * 子类清理钩子。调用时机：[release] 内部，VM clear 之前、lifecycle DESTROYED 之前。
     * 此时 `viewModelStore` / `lifecycleScope` 都仍然可用。
     */
    @MainThread
    protected open fun onRelease() {
    }

    /**
     * 便利访问：本实例的 [lifecycleScope]。直接 `this.lifecycleScope` 也能用，提供别名仅为可读。
     */
    protected val scope get() = lifecycleScope

    /**
     * `observeXxx(scope, method)` 系列的共用收敛：
     * - 调用方传入 `scope` 时挂到调用方生命周期（典型是 Activity/Fragment）
     * - 传 null 时挂到本 Manager 自身的 [lifecycleScope]，随 [release] 自动取消
     *
     * 等价于 `scope.launch { collect { method(it) } }`，但更省一层缩进。
     */
    protected fun <T> Flow<T>.collectInto(scope: CoroutineScope?, method: (T) -> Unit) {
        onEach(method).launchIn(scope ?: lifecycleScope)
    }
}
