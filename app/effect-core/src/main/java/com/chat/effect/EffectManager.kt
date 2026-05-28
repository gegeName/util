package com.chat.effect


import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import com.chat.effect.EffectManager.attach
import com.chat.effect.EffectManager.detach
import com.common.utils.effect.EffectChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 特效播放框架入口。线程安全。
 * 队列语义：
 * - [EffectPriority.NORMAL] 入队尾，FIFO 播放
 * - [EffectPriority.HIGH] 入队首，当前播完后立即播（不打断当前）
 * **多通道并发**：同 stage 上每个 [EffectChannel] 各自一条独立队列与播放槽,跨通道并发,
 * 通道内仍串行 + 优先级排队。不传 channel 的入队走 [EffectChannel.DEFAULT],等同于单通道时代行为。
 */
object EffectManager {

    private const val TAG = "EffectManager"

    private var factory: IEffectPlayerFactory? = null
    private var stage: EffectStageView? = null
    private val lock = Any()

    private val channelStates = ConcurrentHashMap<EffectChannel, ChannelState>()

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
    fun init(context: Context,factory: IEffectPlayerFactory) {
        EffectIO.init(context)
        this.factory = factory
    }

    /**
     * 开启自动 stage 模式。开启后业务方无需在布局里放 [EffectStageView]，
     * 也无需手动调 [attach] / [detach]：
     * @param app Application 实例
     * @param filter 可选过滤器。返回 false 的 Activity 不会播放特效
     */
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

    /**
     * 绑定全屏 stage（手动模式）。所有通道共用此 stage,各通道的 Player view 并存其中。
     */
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
                doClearAllLocked(keepPersistent = true)
            }
            this.stage = stage
            if (manual) manualPinnedStage = stage
            channelStates.values.forEach { state ->
                if (!state.isPlaying && state.hasQueued()) {
                    state.isPlaying = true
                    scheduleConsumeLocked(state)
                }
            }
        }
    }

    /**
     * 解绑 stage。各通道中断当前播放、丢弃非 persistent,**persistent 资源在所属通道队首保留**。
     */
    fun detach(stage: EffectStageView) {
        synchronized(lock) {
            if (this.stage !== stage) return
            doClearAllLocked(keepPersistent = true)
            this.stage = null
            if (manualPinnedStage === stage) manualPinnedStage = null
        }
    }

    /**
     * 入队一条资源,资源类型由 url 后缀自动推断。
     *
     * @param url 资源远端地址
     * @param priority [EffectPriority.NORMAL] 入队尾，[EffectPriority.HIGH] 插到队首
     * @param tag 业务标记，仅用于日志
     * @param channel 播放通道,默认 [EffectChannel.DEFAULT]。同 stage 上不同通道并发播放
     * @param persistent 必播标记
     * @param extras 透传给播放器的额外参数
     */
    @JvmOverloads
    fun enqueue(
        url: String,
        priority: EffectPriority = EffectPriority.NORMAL,
        tag: String? = null,
        channel: EffectChannel = EffectChannel.DEFAULT,
        persistent: Boolean = false,
        extras: Map<String, Any>? = null,
    ) {
        val type = EffectType.fromUrl(url)
        if (type == null) {
            EffectLog.e(TAG) { "enqueue: cannot infer type from url, drop tag=$tag url=$url" }
            return
        }
        enqueue(EffectResource(url, type, priority, tag, channel, persistent, extras))
    }

    /**
     * 入队一条已构造好的资源。
     */
    fun enqueue(resource: EffectResource) {
        if (resource.type.needsDownload && resource.url.isBlank()) {
            EffectLog.e(
                TAG
            ) { "enqueue: type=${resource.type.key} needs download but url is blank, drop tag=${resource.tag}" }
            return
        }
        val state = stateOf(resource.channel)
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
                EffectPriority.HIGH -> state.highQueue.addLast(resource)
                EffectPriority.NORMAL -> state.normalQueue.addLast(resource)
            }
            if (!state.isPlaying && stage != null) {
                state.isPlaying = true
                scheduleConsumeLocked(state)
            }
        }
    }

    /**
     * 预下载单条 url
     */
    fun preload(url: String) {
        val type = EffectType.fromUrl(url)
        if (type == null) {
            EffectLog.e(TAG) { "preload: cannot infer type from url, skip url=$url" }
            return
        }
        EffectDownloader.preload(EffectResource(url = url, type = type))
    }

    /** 批量预热下载 */
    fun preload(urls: List<String>) {
        urls.forEach { preload(it) }
    }

    /**
     * 清空**所有通道**的队列 + 中断当前播放,包括 persistent。
     */
    fun clear() {
        val hadQueued: Boolean
        synchronized(lock) {
            hadQueued = channelStates.values.any { it.isPlaying || it.hasQueued() }
            doClearAllLocked(keepPersistent = false)
        }
        if (hadQueued) {
            scope.launch {
                channelStates.keys.forEach { ch -> dispatchChannelFinished(ch) }
                dispatchQueueFinished()
            }
        }
    }

    /**
     * 清空**指定通道**的队列 + 中断该通道当前播放,包括 persistent。其他通道不受影响。
     */
    fun clear(channel: EffectChannel) {
        val state = channelStates[channel] ?: return
        val hadQueued: Boolean
        synchronized(lock) {
            hadQueued = state.isPlaying || state.hasQueued()
            doClearLocked(state, keepPersistent = false)
        }
        if (hadQueued) {
            scope.launch {
                dispatchChannelFinished(channel)
                if (allChannelsIdle()) dispatchQueueFinished()
            }
        }
    }

    /**
     * 注册播放事件监听器，回调在 Main 线程。重复注册同一实例会被忽略。
     */
    fun addPlaybackListener(listener: EffectPlaybackListener) {
        listeners.addIfAbsent(listener)
    }

    /** 注销监听器。 */
    fun removePlaybackListener(listener: EffectPlaybackListener) {
        listeners.remove(listener)
    }

    private fun stateOf(channel: EffectChannel): ChannelState =
        channelStates.getOrPut(channel) { ChannelState(channel) }

    private fun allChannelsIdle(): Boolean =
        channelStates.values.all { !it.isPlaying && !it.hasQueued() }

    private fun doClearAllLocked(keepPersistent: Boolean) {
        channelStates.values.forEach { doClearLocked(it, keepPersistent) }
    }

    /**
     * @param keepPersistent true:仅丢非 persistent,正在播的 persistent 资源重新插回队首;
     *                       false:全清,包括 persistent
     */
    private fun doClearLocked(state: ChannelState, keepPersistent: Boolean) {
        if (keepPersistent) {
            state.highQueue.removeAll { !it.persistent }
            state.normalQueue.removeAll { !it.persistent }
            val playing = state.currentResource
            if (playing != null && playing.persistent) {
                when (playing.priority) {
                    EffectPriority.HIGH -> state.highQueue.addFirst(playing)
                    EffectPriority.NORMAL -> state.normalQueue.addFirst(playing)
                }
            }
        } else {
            state.highQueue.clear()
            state.normalQueue.clear()
        }
        state.currentJob?.cancel()
        state.currentJob = null
        state.currentPlayer?.release()
        state.currentPlayer = null
        state.currentResource = null
        state.isPlaying = false
    }

    private fun scheduleConsumeLocked(state: ChannelState) {
        state.currentJob = scope.launch { consume(state) }
    }

    private suspend fun consume(state: ChannelState) {
        while (true) {
            val next: EffectResource?
            val currentStage: EffectStageView?
            val currentFactory: IEffectPlayerFactory?
            synchronized(lock) {
                next = when {
                    state.highQueue.isNotEmpty() -> state.highQueue.removeFirst()
                    state.normalQueue.isNotEmpty() -> state.normalQueue.removeFirst()
                    else -> null
                }
                currentStage = stage
                currentFactory = factory
                state.currentResource = next
                if (next == null) {
                    state.isPlaying = false
                    state.currentJob = null
                }
            }
            if (next == null) {
                dispatchChannelFinished(state.channel)
                if (allChannelsIdle()) dispatchQueueFinished()
                return
            }
            if (currentStage == null || currentFactory == null) {
                synchronized(lock) {
                    if (next.persistent) requeueFirstLocked(state, next)
                    state.currentResource = null
                    state.isPlaying = false
                    state.currentJob = null
                }
                if (!next.persistent) dispatchError(next, "no stage/factory")
                dispatchChannelFinished(state.channel)
                if (allChannelsIdle()) dispatchQueueFinished()
                return
            }

            // type.needsDownload = false 时为纯代码动画(无远端资源),跳过下载,
            // 直接给 Player 一个空 localPath,Player 从 resource.extras 取参数
            val localPath = if (!next.type.needsDownload) {
                ""
            } else {
                val path = EffectDownloader.fetch(next)
                if (path == null) {
                    EffectLog.e(TAG) { "fetch failed, skip tag=${next.tag} url=${next.url}" }
                    synchronized(lock) { state.currentResource = null }
                    dispatchError(next, "fetch failed")
                    continue
                }
                path
            }

            val stillStage = synchronized(lock) { stage }
            if (stillStage !== currentStage) {
                synchronized(lock) { state.currentResource = null }
                if (!next.persistent) dispatchError(next, "stage detached")
                continue
            }

            val player = currentFactory.create(next.type, next.channel)
            synchronized(lock) { state.currentPlayer = player }
            val done = CompletableDeferred<PlayResult>()
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
                    if (state.currentPlayer === player) state.currentPlayer = null
                    state.currentResource = null
                }
            }
        }
    }

    private fun requeueFirstLocked(state: ChannelState, res: EffectResource) {
        when (res.priority) {
            EffectPriority.HIGH -> state.highQueue.addFirst(res)
            EffectPriority.NORMAL -> state.normalQueue.addFirst(res)
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

    private fun dispatchChannelFinished(channel: EffectChannel) {
        listeners.forEach { runCatching { it.onChannelFinished(channel) } }
    }

    private fun dispatchQueueFinished() {
        listeners.forEach { runCatching { it.onQueueFinished() } }
    }

    private class ChannelState(val channel: EffectChannel) {
        val highQueue = ArrayDeque<EffectResource>()
        val normalQueue = ArrayDeque<EffectResource>()

        @Volatile
        var isPlaying = false

        @Volatile
        var currentPlayer: IEffectPlayer? = null

        @Volatile
        var currentResource: EffectResource? = null

        @Volatile
        var currentJob: Job? = null

        fun hasQueued(): Boolean = highQueue.isNotEmpty() || normalQueue.isNotEmpty()
    }
}
