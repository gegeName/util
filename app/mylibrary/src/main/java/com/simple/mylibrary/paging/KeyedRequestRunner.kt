package com.simple.mylibrary.paging

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 按 key 调度协程任务，配合 [RequestPolicy] 实现节流 / 取消旧的 / 串行。
 *
 * 给 [PagingController] 内部使用，业务侧不直接接触。
 */
internal class KeyedRequestRunner(private val scope: CoroutineScope) {

    private val lock = Any()
    private val jobs = mutableMapOf<Any, Job>()
    private val lastTs = mutableMapOf<Any, Long>()
    private val mutexes = mutableMapOf<Any, Mutex>()

    fun run(key: Any, policy: RequestPolicy, block: suspend () -> Unit): Job? {
        return when (policy) {
            RequestPolicy.None -> launchAndTrack(key, block)

            is RequestPolicy.Throttle -> {
                val now = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    val last = lastTs[key] ?: 0L
                    if (now - last < policy.intervalMs) return null
                    lastTs[key] = now
                }
                launchAndTrack(key, block)
            }

            RequestPolicy.Latest -> {
                synchronized(lock) { jobs[key]?.cancel() }
                launchAndTrack(key, block)
            }

            RequestPolicy.Serial -> {
                val mutex = synchronized(lock) { mutexes.getOrPut(key) { Mutex() } }
                launchAndTrack(key) { mutex.withLock { block() } }
            }
        }
    }

    private fun launchAndTrack(key: Any, block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } finally {
                synchronized(lock) { if (jobs[key] === job) jobs.remove(key) }
            }
        }
        synchronized(lock) { jobs[key] = job }
        return job
    }

    fun cancel(key: Any) {
        synchronized(lock) { jobs.remove(key)?.cancel() }
    }

    fun cancelAll() {
        synchronized(lock) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
    }
}
