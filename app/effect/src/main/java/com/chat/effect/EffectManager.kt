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
     *
     * @param context 任意 Context，内部取 [Context.getApplicationContext]，供下载缓存路径 / SVGA 解析器使用
     * @param factory 业务侧实现的播放器工厂
     */
    fun init(context: Context, factory: IEffectPlayerFactory) {
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
     * 绑定全屏 stage（手动模式）
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

    /**
     * 解绑 stage。中断当前播放,丢弃非 persistent 队列,**persistent 资源保留**
     */
    fun detach(stage: EffectStageView) {
        synchronized(lock) {
            if (this.stage !== stage) return
            doClearLocked(keepPersistent = true)
            this.stage = null
            if (manualPinnedStage === stage) manualPinnedStage = null
        }
    }

    /**
     * 入队一条资源并触发播放调度
     *
     * @param url 资源远端地址
     * @param priority [EffectPriority.NORMAL] 入队尾，[EffectPriority.HIGH] 插到队首
     * @param tag 业务标记，仅用于日志
     * @param persistent 必播标记。true 时即使当前没有 stage 也可入队等待，stage 切换时也不会被丢弃。
     *   详见 [EffectResource.persistent]。
     * @param extras 透传给播放器的额外参数，由调用方与自定义 Player 约定
     */
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

    /**
     * 入队一条已构造好的资源。给类型推断不出来（签名 URL / 非标准后缀）的场景兜底。
     */
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

    /**
     * 批量预热下载url
     */
    fun preload(urls: List<String>) {
        urls.forEach { preload(it) }
    }

    /**
     * 清空队列 + 中断当前播放，**包括 persistent 必播资源**。stage 仍保持绑定。
     *
     * 不会触发 [EffectPlaybackListener.onComplete] / [EffectPlaybackListener.onError]，但会触发 [EffectPlaybackListener.onQueueFinished]。
     */
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

    /**
     * @param keepPersistent true:仅丢非 persistent 资源,正在播的 persistent 资源会被重新插回队首;
     *                       false:全清,包括 persistent
     */
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
