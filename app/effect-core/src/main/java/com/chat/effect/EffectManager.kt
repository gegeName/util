package com.chat.effect


import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.chat.effect.EffectManager.attach
import com.chat.effect.EffectManager.clear
import com.chat.effect.EffectManager.detach
import com.chat.effect.EffectManager.enqueue
import com.chat.effect.EffectManager.init
import com.chat.effect.EffectManager.preload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 特效播放框架入口。线程安全。
 * 生命周期：
 * - 应用启动时调一次 [init] 注入 [IEffectPlayerFactory]
 * - Activity onCreate 调 [attach]，onDestroy 调 [detach]
 * - 业务调 [enqueue] / [preload] / [clear]
 *
 * 队列语义：
 * - [EffectPriority.NORMAL] 入队尾，FIFO 播放
 * - [EffectPriority.HIGH] 入队首，当前播完后立即播（不打断当前）
 * 全局只有一个播放槽：同一时刻最多一条资源在播。
 */
object EffectManager {

    private const val TAG = "EffectManager"

    private var factory: IEffectPlayerFactory? = null
    private var stage: EffectStageView? = null
    private val highQueue = ArrayDeque<EffectResource>()
    private val normalQueue = ArrayDeque<EffectResource>()
    private val lock = Any()

    @Volatile
    private var isPlaying = false

    @Volatile
    private var currentPlayer: IEffectPlayer? = null

    @Volatile
    private var currentResource: EffectResource? = null

    @Volatile
    private var currentJob: Job? = null

    private val listeners = CopyOnWriteArrayList<EffectPlaybackListener>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var autoStageEnabled = false
    private var autoStageFilter: ((Activity) -> Boolean)? = null
    private val activityStages = WeakHashMap<Activity, EffectStageView>()

    @Volatile
    private var manualPinnedStage: EffectStageView? = null

    /**
     * 注入工厂。应用启动时调用一次；重复调用会覆盖。
     */
    fun init(context: Context, factory: IEffectPlayerFactory) {
        EffectIO.init(context)
        this.factory = factory
    }

    @JvmOverloads
    fun enableAutoStage(app: Application, filter: ((Activity) -> Boolean)? = null) {
        if (autoStageEnabled) return
        autoStageEnabled = true
        autoStageFilter = filter
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}

            override fun onActivityResumed(activity: Activity) {
                if (manualPinnedStage != null) return
                if (autoStageFilter?.invoke(activity) == false) return
                val stage = ensureStageFor(activity) ?: return
                attachInternal(stage, manual = false)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

            override fun onActivityDestroyed(activity: Activity) {
                val stage = activityStages.remove(activity) ?: return
                if (manualPinnedStage !== stage) detach(stage)
                (stage.parent as? ViewGroup)?.removeView(stage)
            }
        })
    }

    private fun ensureStageFor(activity: Activity): EffectStageView? {
        activityStages[activity]?.let { return it }
        val content = activity.findViewById<FrameLayout>(android.R.id.content) ?: return null
        val stage = EffectStageView(activity).also {
            content.addView(
                it,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        activityStages[activity] = stage
        return stage
    }

    fun attach(stage: EffectStageView) {
        attachInternal(stage, manual = true)
    }

    private fun attachInternal(stage: EffectStageView, manual: Boolean) {
        synchronized(lock) {
            if (this.stage === stage) {
                if (manual) manualPinnedStage = stage
                return
            }
            if (this.stage != null) {
                doClearLocked(keepPersistent = true)
            }
            this.stage = stage
            if (manual) manualPinnedStage = stage
            if (!isPlaying && (highQueue.isNotEmpty() || normalQueue.isNotEmpty())) {
                isPlaying = true
                scheduleConsumeLocked()
            }
        }
    }

    fun detach(stage: EffectStageView) {
        synchronized(lock) {
            if (this.stage !== stage) return
            doClearLocked(keepPersistent = true)
            this.stage = null
            if (manualPinnedStage === stage) manualPinnedStage = null
        }
    }

    @JvmOverloads
    fun enqueue(
        url: String,
        priority: EffectPriority = EffectPriority.NORMAL,
        tag: String? = null,
        persistent: Boolean = false,
        extras: Map<String, Any>? = null,
    ) {
        val type = EffectType.fromUrl(url)
        if (type == null) {
            EffectLog.e(TAG) { "enqueue: cannot infer type from url, drop tag=$tag url=$url" }
            return
        }
        enqueue(EffectResource(url, type, priority, tag, persistent, extras))
    }

    fun enqueue(resource: EffectResource) {
        if (resource.url.isBlank()) {
            EffectLog.e(TAG) { "enqueue: blank url, ignore tag=${resource.tag}" }
            return
        }
        synchronized(lock) {
            if (factory == null) {
                EffectLog.e(TAG) { "enqueue: factory not init, drop tag=${resource.tag}" }
                return
            }
            if (stage == null && !resource.persistent) {
                EffectLog.e(TAG) { "enqueue: no stage, drop non-persistent tag=${resource.tag}" }
                return
            }
            when (resource.priority) {
                EffectPriority.HIGH -> highQueue.addLast(resource)
                EffectPriority.NORMAL -> normalQueue.addLast(resource)
            }
            if (!isPlaying && stage != null) {
                isPlaying = true
                scheduleConsumeLocked()
            }
        }
    }

    fun preload(url: String) {
        val type = EffectType.fromUrl(url)
        if (type == null) {
            EffectLog.e(TAG) { "preload: cannot infer type from url, skip url=$url" }
            return
        }
        EffectDownloader.preload(EffectResource(url = url, type = type))
    }

    fun preload(urls: List<String>) {
        urls.forEach { preload(it) }
    }

    fun clear() {
        val needFinishedCallback: Boolean
        synchronized(lock) {
            needFinishedCallback = isPlaying || highQueue.isNotEmpty() || normalQueue.isNotEmpty()
            doClearLocked(keepPersistent = false)
        }
        if (needFinishedCallback) {
            scope.launch { listeners.forEach { runCatching { it.onQueueFinished() } } }
        }
    }

    fun addPlaybackListener(listener: EffectPlaybackListener) {
        listeners.addIfAbsent(listener)
    }

    fun removePlaybackListener(listener: EffectPlaybackListener) {
        listeners.remove(listener)
    }

    private fun doClearLocked(keepPersistent: Boolean) {
        if (keepPersistent) {
            highQueue.removeAll { !it.persistent }
            normalQueue.removeAll { !it.persistent }
            val playing = currentResource
            if (playing != null && playing.persistent) {
                when (playing.priority) {
                    EffectPriority.HIGH -> highQueue.addFirst(playing)
                    EffectPriority.NORMAL -> normalQueue.addFirst(playing)
                }
            }
        } else {
            highQueue.clear()
            normalQueue.clear()
        }
        currentJob?.cancel()
        currentJob = null
        currentPlayer?.release()
        currentPlayer = null
        currentResource = null
        isPlaying = false
    }

    private fun scheduleConsumeLocked() {
        currentJob = scope.launch { consume() }
    }

    private suspend fun consume() {
        while (true) {
            val next: EffectResource?
            val currentStage: EffectStageView?
            val currentFactory: IEffectPlayerFactory?
            synchronized(lock) {
                next = when {
                    highQueue.isNotEmpty() -> highQueue.removeFirst()
                    normalQueue.isNotEmpty() -> normalQueue.removeFirst()
                    else -> null
                }
                currentStage = stage
                currentFactory = factory
                currentResource = next
                if (next == null) {
                    isPlaying = false
                    currentJob = null
                }
            }
            if (next == null) {
                dispatchQueueFinished()
                return
            }
            if (currentStage == null || currentFactory == null) {
                synchronized(lock) {
                    if (next.persistent) requeueFirstLocked(next)
                    currentResource = null
                    isPlaying = false
                    currentJob = null
                }
                if (!next.persistent) dispatchError(next, "no stage/factory")
                dispatchQueueFinished()
                return
            }

            val localPath = EffectDownloader.fetch(next)
            if (localPath == null) {
                EffectLog.e(TAG) { "fetch failed, skip tag=${next.tag} url=${next.url}" }
                synchronized(lock) { currentResource = null }
                dispatchError(next, "fetch failed")
                continue
            }

            val stillStage = synchronized(lock) { stage }
            if (stillStage !== currentStage) {
                synchronized(lock) { currentResource = null }
                if (!next.persistent) dispatchError(next, "stage detached")
                continue
            }

            val player = currentFactory.create(next.type)
            synchronized(lock) { currentPlayer = player }
            val done = kotlinx.coroutines.CompletableDeferred<PlayResult>()
            try {
                player.attach(currentStage)
                dispatchStart(next)
                player.play(localPath, next, object : PlayCallback {
                    override fun onComplete() {
                        done.complete(PlayResult.Ok)
                    }

                    override fun onError(reason: String) {
                        EffectLog.e(TAG) { "play onError tag=${next.tag} reason=$reason" }
                        done.complete(PlayResult.Err(reason))
                    }
                })
                when (val result = done.await()) {
                    PlayResult.Ok -> dispatchComplete(next)
                    is PlayResult.Err -> dispatchError(next, result.reason)
                }
            } catch (e: Exception) {
                EffectLog.e(TAG, e) { "play threw tag=${next.tag}" }
                dispatchError(next, e.message ?: e.javaClass.simpleName)
            } finally {
                player.release()
                synchronized(lock) {
                    if (currentPlayer === player) currentPlayer = null
                    currentResource = null
                }
            }
        }
    }

    private fun requeueFirstLocked(res: EffectResource) {
        when (res.priority) {
            EffectPriority.HIGH -> highQueue.addFirst(res)
            EffectPriority.NORMAL -> normalQueue.addFirst(res)
        }
    }

    private sealed class PlayResult {
        object Ok : PlayResult()
        data class Err(val reason: String) : PlayResult()
    }

    private fun dispatchStart(res: EffectResource) {
        listeners.forEach { runCatching { it.onStart(res) } }
    }

    private fun dispatchComplete(res: EffectResource) {
        listeners.forEach { runCatching { it.onComplete(res) } }
    }

    private fun dispatchError(res: EffectResource, reason: String) {
        listeners.forEach { runCatching { it.onError(res, reason) } }
    }

    private fun dispatchQueueFinished() {
        listeners.forEach { runCatching { it.onQueueFinished() } }
    }
}
