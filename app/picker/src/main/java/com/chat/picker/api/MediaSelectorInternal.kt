package com.chat.picker.api

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
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
import com.chat.picker.preview.IOtherPreviewProvider
import com.chat.picker.ui.MediaPickerActivity
import com.chat.picker.ui.PermissionHelper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 内部状态与系统 Photo Picker 的 cursor 解析、预加载缓存等收敛于此，
 * 仅供同包 [MediaSelector] / [MediaPickerActivity] 等内部访问。
 */
internal object MediaSelectorInternal {

    const val MAX_SYSTEM_PICKER_ITEMS = 100
    val sysPickerPool = Executors.newSingleThreadExecutor()

    @Volatile
    var pendingConfig: SelectionConfig? = null

    @Volatile
    var pendingListener: OnPickResultListener? = null

    @Volatile
    var globalEngine: IImageEngine? = null

    @Volatile
    var activeEngine: IImageEngine? = null

    @Volatile
    var globalImageCompressor: IImageCompressor? = null

    @Volatile
    var globalVideoCompressor: IVideoCompressor? = null

    @Volatile
    var activeImageCompressor: IImageCompressor? = null

    @Volatile
    var activeVideoCompressor: IVideoCompressor? = null

    @Volatile
    var globalOtherPreviewProvider: IOtherPreviewProvider? = null

    val preloadCache = ConcurrentHashMap<MediaType, List<MediaEntity>>()

    fun preload(context: Context, types: Array<out MediaType>, pageSize: Int) {
        val app = context.applicationContext
        types.forEach { t ->
            if (!PermissionHelper.anyUsable(app, t)) return@forEach
            MediaRepository.queryAsync(
                app, MediaFilter.of(t), offset = 0, limit = pageSize
            ) { list ->
                if (list.isNotEmpty()) preloadCache[t] = list
            }
        }
    }

    fun cached(type: MediaType): List<MediaEntity>? =
        preloadCache[type]?.takeIf { it.isNotEmpty() }

    fun putCache(type: MediaType, list: List<MediaEntity>) {
        if (list.isNotEmpty()) preloadCache[type] = list
    }

    fun invalidateCache() = preloadCache.clear()

    fun launchSystemPicker(
        activity: ComponentActivity,
        cfg: SelectionConfig,
        listener: OnPickResultListener,
    ) {
        val maxItems = cfg.maxCount.coerceAtMost(MAX_SYSTEM_PICKER_ITEMS)
        val contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems)
        val mediaType = when (cfg.filter.type) {
            MediaType.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
            MediaType.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
        val fallbackType = cfg.filter.type
        val launcher = activity.activityResultRegistry.register(
            "media_picker_sys_${System.currentTimeMillis()}",
            contract,
        ) { uris: List<Uri> ->
            val app = activity.applicationContext
            sysPickerPool.execute {
                val list = uris.mapNotNull { systemUriToEntity(app, it, fallbackType) }
                activity.runOnUiThread { listener.onResult(list) }
            }
        }
        launcher.launch(PickVisualMediaRequest(mediaType))
    }

    fun launchInternalPicker(
        activity: ComponentActivity,
        cfg: SelectionConfig,
        listener: OnPickResultListener,
    ) {
        pendingListener = listener
        pendingConfig = cfg
        val launcher = activity.activityResultRegistry.register(
            "media_picker_${System.currentTimeMillis()}",
            ActivityResultContracts.StartActivityForResult(),
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                @Suppress("DEPRECATION")
                val list = result.data?.getParcelableArrayListExtra<MediaEntity>(MediaSelector.EXTRA_RESULT)
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

    private fun systemUriToEntity(ctx: Context, uri: Uri, fallbackType: MediaType): MediaEntity? {
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
                val rawMime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                val mime = rawMime ?: when (fallbackType) {
                    MediaType.IMAGE -> "image/*"
                    MediaType.VIDEO -> "video/*"
                    MediaType.AUDIO -> "audio/*"
                    MediaType.IMAGE_VIDEO, MediaType.ALL -> return null
                }
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

    private fun Cursor.optLong(col: String): Long {
        val idx = getColumnIndex(col); if (idx < 0) return 0
        return getLong(idx)
    }

    private fun Cursor.optInt(col: String): Int {
        val idx = getColumnIndex(col); if (idx < 0) return 0
        return getInt(idx)
    }

    private fun Cursor.optString(col: String): String? {
        val idx = getColumnIndex(col); if (idx < 0) return null
        return getString(idx)
    }
}
