package com.chat.picker.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.chat.picker.compress.IImageCompressor
import com.chat.picker.compress.IVideoCompressor
import com.chat.picker.data.MediaRepository
import com.chat.picker.loader.DefaultImageEngine
import com.chat.picker.loader.IImageEngine
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import com.chat.picker.ui.MediaPickerActivity
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 入口。用法：
 *   MediaSelector.with(activity)
 *     .type(MediaType.IMAGE)
 *     .maxCount(9)
 *     .grid(true)
 *     .start { result -> ... }
 *
 * 初始化预查询：
 *   MediaSelector.preload(context, MediaType.IMAGE, MediaType.VIDEO)
 */
class MediaSelector private constructor(private val activity: ComponentActivity) {

    private val cfg = SelectionConfig()

    fun type(type: MediaType) = apply {
        cfg.filter = MediaFilter.Builder(type).build()
    }

    fun filter(filter: MediaFilter) = apply { cfg.filter = filter }

    fun maxCount(n: Int) = apply { cfg.maxCount = n.coerceAtLeast(1) }
    fun grid(enable: Boolean) = apply { cfg.startInGrid = enable }
    fun spanCount(n: Int) = apply { cfg.gridSpanCount = n.coerceAtLeast(2) }
    fun multiSelect(enable: Boolean) = apply { cfg.enableMultiSelect = enable }

    /** 单次覆盖：仅本次调用使用该 engine，不影响全局 */
    fun imageEngine(engine: IImageEngine) = apply { activeEngine = engine }

    /** 单次覆盖：仅本次使用该图片压缩器 */
    fun imageCompressor(c: IImageCompressor) = apply { activeImageCompressor = c }

    /** 单次覆盖：仅本次使用该视频压缩器 */
    fun videoCompressor(c: IVideoCompressor) = apply { activeVideoCompressor = c }

    /** 启用系统 Photo Picker（API 33+，零权限）。AUDIO 类型会回退到本框架 */
    fun useSystemPicker(enable: Boolean) = apply { cfg.useSystemPhotoPicker = enable }

    /** 列表首位显示"相机入口"item，点击后调起系统相机拍照、拍完插入到第二位并自动选中 */
    fun showCameraEntry(enable: Boolean) = apply { cfg.showCameraEntry = enable }

    /** 传入已选过的项；打开 picker 时自动复选（按 id+mediaType 匹配） */
    fun preSelected(list: List<MediaEntity>) = apply { cfg.preSelected = list }

    fun start(listener: OnPickResultListener) {
        if (shouldUseSystemPicker()) {
            startWithSystemPicker(listener)
            return
        }
        pendingListener = listener
        pendingConfig = cfg
        val launcher = activity.activityResultRegistry.register(
            "media_picker_${System.currentTimeMillis()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data

                @Suppress("DEPRECATION")
                val list = data?.getParcelableArrayListExtra<MediaEntity>(EXTRA_RESULT)
                    ?: arrayListOf()
                listener.onResult(list)
            } else {
                listener.onResult(emptyList())
            }
            pendingListener = null
            pendingConfig = null
        }
        launcher.launch(Intent(activity, MediaPickerActivity::class.java))
    }

    private fun shouldUseSystemPicker(): Boolean =
        cfg.useSystemPhotoPicker &&
                cfg.filter.type != MediaType.AUDIO &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun startWithSystemPicker(listener: OnPickResultListener) {
        val maxItems = cfg.maxCount.coerceAtMost(MAX_SYSTEM_PICKER_ITEMS)
        val contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems)
        val launcher = activity.activityResultRegistry.register(
            "media_picker_sys_${System.currentTimeMillis()}",
            contract,
        ) { uris: List<Uri> ->
            val app = activity.applicationContext
            sysPickerPool.execute {
                val list = uris.mapNotNull { systemUriToEntity(app, it) }
                activity.runOnUiThread { listener.onResult(list) }
            }
        }
        val mediaType = when (cfg.filter.type) {
            MediaType.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
            MediaType.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
        launcher.launch(PickVisualMediaRequest(mediaType))
    }

    private fun systemUriToEntity(ctx: Context, uri: Uri): MediaEntity? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        )
        return runCatching {
            ctx.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                    ?: return null
                val mediaType = when {
                    mime.startsWith("image/") -> MediaType.IMAGE
                    mime.startsWith("video/") -> MediaType.VIDEO
                    else -> MediaType.ALL
                }
                MediaEntity(
                    id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                    uri = uri,
                    filePath = c.optString(@Suppress("DEPRECATION") MediaStore.MediaColumns.DATA),
                    displayName = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                        ?: "",
                    mimeType = mime,
                    sizeBytes = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                    durationMs = c.optLong(MediaStore.MediaColumns.DURATION),
                    dateAddedSec = c.optLong(MediaStore.MediaColumns.DATE_ADDED),
                    width = c.optInt(MediaStore.MediaColumns.WIDTH),
                    height = c.optInt(MediaStore.MediaColumns.HEIGHT),
                    mediaType = mediaType,
                )
            }
        }.getOrNull()
    }

    private fun android.database.Cursor.optLong(col: String): Long {
        val idx = getColumnIndex(col); if (idx < 0) return 0
        return getLong(idx)
    }

    private fun android.database.Cursor.optInt(col: String): Int {
        val idx = getColumnIndex(col); if (idx < 0) return 0
        return getInt(idx)
    }

    private fun android.database.Cursor.optString(col: String): String? {
        val idx = getColumnIndex(col); if (idx < 0) return null
        return getString(idx)
    }

    companion object {
        const val EXTRA_RESULT = "picker_result"
        private const val MAX_SYSTEM_PICKER_ITEMS = 100
        private val sysPickerPool = Executors.newSingleThreadExecutor()

        @Volatile
        var pendingConfig: SelectionConfig? = null
            private set

        @Volatile
        private var pendingListener: OnPickResultListener? = null

        /** 全局默认 engine：可在 Application 中一次性设置 */
        @Volatile
        private var globalEngine: IImageEngine? = null

        /** 本次链式调用临时覆盖的 engine；start() 后生效，picker 关闭后清空 */
        @Volatile
        private var activeEngine: IImageEngine? = null

        /** 全局图片压缩器 */
        @Volatile
        private var globalImageCompressor: IImageCompressor? = null

        /** 全局视频压缩器 */
        @Volatile
        private var globalVideoCompressor: IVideoCompressor? = null

        /** 本次链式调用临时覆盖的图片压缩器 */
        @Volatile
        private var activeImageCompressor: IImageCompressor? = null

        /** 本次链式调用临时覆盖的视频压缩器 */
        @Volatile
        private var activeVideoCompressor: IVideoCompressor? = null

        private val preloadCache = ConcurrentHashMap<MediaType, List<MediaEntity>>()

        fun with(activity: ComponentActivity) = MediaSelector(activity)

        /**
         * 独立拍照入口：不进 picker UI，直接调系统相机拍一张并返回路径/uri。
         * @param listener onResult(success, filePath, uri)
         */
        @JvmStatic
        fun takePhoto(activity: ComponentActivity, listener: OnPhotoTakenListener) {
            com.chat.picker.camera.CameraHelper.take(activity) { ok, path, uri ->
                if (ok) invalidateCache() // 下次 picker 立即能查到
                listener.onResult(ok, path, uri)
            }
        }

        /** 全局设置图片加载引擎；传 null 恢复内置默认 */
        fun setImageEngine(engine: IImageEngine?) {
            globalEngine = engine
        }

        /** 内部使用：优先级 activeEngine > globalEngine > DefaultImageEngine */
        internal fun imageEngine(): IImageEngine =
            activeEngine ?: globalEngine ?: DefaultImageEngine

        internal fun clearActiveEngine() {
            activeEngine = null
        }

        /** 全局设置图片压缩器；传 null 则不压缩图片 */
        fun setImageCompressor(c: IImageCompressor?) {
            globalImageCompressor = c
        }

        /** 全局设置视频压缩器；传 null 则不压缩视频 */
        fun setVideoCompressor(c: IVideoCompressor?) {
            globalVideoCompressor = c
        }

        internal fun imageCompressor(): IImageCompressor? =
            activeImageCompressor ?: globalImageCompressor

        internal fun videoCompressor(): IVideoCompressor? =
            activeVideoCompressor ?: globalVideoCompressor

        internal fun clearActiveCompressors() {
            activeImageCompressor = null
            activeVideoCompressor = null
        }

        /**
         * 后台预查询：可在权限已就绪后调用，命中后列表页直接展示。
         * 注意：调用前必须已获得相应类型的读取权限，否则 ContentResolver 返回空，
         * 这里会丢弃空结果以避免后续命中"伪空缓存"。
         */
        const val PAGE_SIZE = 50

        fun preload(context: Context, vararg types: MediaType) {
            val app = context.applicationContext
            types.forEach { t ->
                // 只预查首页：与列表页分页节奏一致，避免拉全量内存压力
                MediaRepository.queryAsync(
                    app, MediaFilter.of(t), offset = 0, limit = PAGE_SIZE
                ) { list ->
                    if (list.isNotEmpty()) preloadCache[t] = list
                }
            }
        }

        /** 仅在缓存非空时视为命中 */
        fun cached(type: MediaType): List<MediaEntity>? =
            preloadCache[type]?.takeIf { it.isNotEmpty() }

        /** 懒预热：picker 真实查询完后回写，下次同类型直接秒开 */
        internal fun putCache(type: MediaType, list: List<MediaEntity>) {
            if (list.isNotEmpty()) preloadCache[type] = list
        }

        fun invalidateCache() = preloadCache.clear()
    }
}

typealias Picker = MediaSelector
