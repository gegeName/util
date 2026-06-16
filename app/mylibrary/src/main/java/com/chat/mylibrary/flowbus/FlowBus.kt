package com.chat.mylibrary.flowbus

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.chat.mylibrary.base.BaseViewModel
import com.chat.mylibrary.utils.SLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


/**
 * FlowBus消息总线
 */
object FlowBus {
    private const val TAG = "FlowBus"
    private val busMap = mutableMapOf<String, EventBus<*>>()
    private val busStickMap = mutableMapOf<String, StickEventBus<*>>()

    @Synchronized
    fun <T> with(key: String): EventBus<T> {
        var eventBus = busMap[key]
        if (eventBus == null) {
            eventBus = EventBus<T>(key)
            busMap[key] = eventBus
        }
        @Suppress("UNCHECKED_CAST")
        return eventBus as EventBus<T>
    }

    @Synchronized
    fun <T> withStick(key: String): StickEventBus<T> {
        var eventBus = busStickMap[key]
        if (eventBus == null) {
            eventBus = StickEventBus<T>(key)
            busStickMap[key] = eventBus
        }
        @Suppress("UNCHECKED_CAST")
        return eventBus as StickEventBus<T>
    }

    @Synchronized
    internal fun removeBus(key: String) {
        busMap.remove(key)
    }

    @Synchronized
    internal fun removeStickBus(key: String) {
        busStickMap.remove(key)
    }

    /** 主动清空指定 key 的粘性事件缓存与已消费记录（如用户登出时调用）。 */
    @Synchronized
    fun clearStick(key: String) {
        busStickMap.remove(key)?.dispose()
    }

    open class EventBus<T>(private val key: String) : DefaultLifecycleObserver {

        private val _events: MutableSharedFlow<T> by lazy {
            MutableSharedFlow(0, 1, BufferOverflow.DROP_OLDEST)
        }

        val events = _events.asSharedFlow()

        private val observers: MutableSet<LifecycleOwner> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        fun register(lifecycleOwner: LifecycleOwner, action: (t: T) -> Unit) {
            lifecycleOwner.lifecycle.addObserver(this)
            observers.add(lifecycleOwner)
            lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                events.collect {
                    try {
                        action(it)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        SLog.e(TAG) { "FlowBus - Error:$e" }
                    }
                }
            }
        }

        fun unRegister(lifecycleOwner: LifecycleOwner) {
            lifecycleOwner.lifecycle.removeObserver(this)
            observers.remove(lifecycleOwner)
        }

        fun post(event: T) {
            _events.tryEmit(event)
        }

        internal fun dispose() {
            val snapshot = synchronized(observers) {
                val list = observers.toList()
                observers.clear()
                list
            }
            snapshot.forEach { it.lifecycle.removeObserver(this) }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            SLog.d(TAG) { "FlowBus - 自动onDestroy" }
            observers.remove(owner)
            if (_events.subscriptionCount.value <= 0) {
                dispose()
                removeBus(key)
            }
        }
    }

    class StickEventBus<T>(private val key: String) : DefaultLifecycleObserver {

        private data class Envelope<E>(val id: Long, val value: E)

        private val _events =
            MutableSharedFlow<Envelope<T>>(1, 1, BufferOverflow.DROP_OLDEST)

        private val idGen = AtomicLong()
        private val consumed = ConcurrentHashMap.newKeySet<String>()

        private val observers: MutableSet<LifecycleOwner> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        fun register(
            lifecycleOwner: LifecycleOwner,
            tag: String = "",
            action: (t: T) -> Unit,
        ) {
            lifecycleOwner.lifecycle.addObserver(this)
            observers.add(lifecycleOwner)
            val subKey = lifecycleOwner.javaClass.name + "#" + tag
            lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                _events.collect { env ->
                    val token = "$subKey#${env.id}"
                    if (consumed.add(token)) {
                        try {
                            action(env.value)
                        } catch (e: Exception) {
                            e.printStackTrace()
                            SLog.e(TAG) { "FlowBus - Error:$e" }
                        }
                    }
                }
            }
        }

        fun unRegister(lifecycleOwner: LifecycleOwner) {
            lifecycleOwner.lifecycle.removeObserver(this)
            observers.remove(lifecycleOwner)
        }


        fun post(event: T) {
            emitInternal(event)
        }

        private fun emitInternal(event: T) {
            val id = idGen.incrementAndGet()
            consumed.clear()
            _events.tryEmit(Envelope(id, event))
        }

        internal fun dispose() {
            consumed.clear()
            _events.resetReplayCache()
            val snapshot = synchronized(observers) {
                val list = observers.toList()
                observers.clear()
                list
            }
            snapshot.forEach { it.lifecycle.removeObserver(this) }
        }

        override fun onDestroy(owner: LifecycleOwner) {
            SLog.d(TAG) { "FlowBus - 自动onDestroy (stick)" }
            observers.remove(owner)
            if (_events.subscriptionCount.value <= 0) {
                dispose()
                removeStickBus(key)
            }
        }
    }

}

fun <T> Fragment.flowBusStickPost(key: String, event: T) {
    FlowBus.withStick<T>(key).post(event)
}

fun <T> AppCompatActivity.flowBusStickPost(key: String, event: T) {
    FlowBus.withStick<T>(key).post(event)
}

fun <T> BaseViewModel.flowBusStickPost(key: String, event: T) {
    FlowBus.withStick<T>(key).post(event)
}

fun <T> Fragment.flowBusPost(key: String, event: T) {
    FlowBus.with<T>(key).post(event)
}

fun <T> AppCompatActivity.flowBusPost(key: String, event: T) {
    FlowBus.with<T>(key).post(event)
}

fun <T> BaseViewModel.flowBusPost(key: String, event: T) {
    FlowBus.with<T>(key).post(event)
}

fun <T> Fragment.flowBusRegister(key: String, action: (T) -> Unit) {
    FlowBus.with<T>(key).register(this) {
        action.invoke(it)
    }
}

fun <T> AppCompatActivity.flowBusRegister(key: String, action: (T) -> Unit) {
    FlowBus.with<T>(key).register(this) {
        action.invoke(it)
    }
}

fun <T> Fragment.flowBusStickRegister(key: String, tag: String = "", action: (T) -> Unit) {
    FlowBus.withStick<T>(key).register(this, tag) {
        action.invoke(it)
    }
}

fun <T> AppCompatActivity.flowBusStickRegister(
    key: String,
    tag: String = "",
    action: (T) -> Unit,
) {
    FlowBus.withStick<T>(key).register(this, tag) {
        action.invoke(it)
    }
}
