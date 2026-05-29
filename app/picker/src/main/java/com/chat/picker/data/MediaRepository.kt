package com.chat.picker.data

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import com.chat.picker.util.PickerLog
import java.util.concurrent.Executors

object MediaRepository {

    private val ioExecutor = Executors.newSingleThreadExecutor()

    fun queryAsync(
        context: Context,
        filter: MediaFilter,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
        callback: (List<MediaEntity>) -> Unit,
    ) {
        ioExecutor.execute {
            val list = runCatching { query(context, filter, offset, limit) }
                .getOrDefault(emptyList())
            callback(list)
        }
    }

    fun query(
        context: Context,
        filter: MediaFilter,
        offset: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ): List<MediaEntity> {
        val uri: Uri = filter.type.contentUri()
        val projection = projectionFor(filter.type)
        val (selection, args) = buildSelection(filter)
        val cr = context.contentResolver

        val cursor = openCursor(cr, uri, projection, selection, args, offset, limit)
        PickerLog.d(
            "query type=${filter.type} uri=$uri sel=$selection " +
                "args=${args?.toList()} offset=$offset limit=$limit count=${cursor?.count}"
        )
        cursor ?: return emptyList()

        val list = mutableListOf<MediaEntity>()
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val dataIdx = c.optionalIndex(@Suppress("DEPRECATION") MediaStore.MediaColumns.DATA)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val durIdx = c.optionalIndex(MediaStore.MediaColumns.DURATION)
            val wIdx = c.optionalIndex(MediaStore.MediaColumns.WIDTH)
            val hIdx = c.optionalIndex(MediaStore.MediaColumns.HEIGHT)
            val albumIdx = c.optionalIndex(MediaStore.Audio.AudioColumns.ALBUM_ID)

            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val rawMime = c.getString(mimeIdx)
                val mime = rawMime ?: when (filter.type) {
                    MediaType.IMAGE -> "image/*"
                    MediaType.VIDEO -> "video/*"
                    MediaType.AUDIO -> "audio/*"
                    MediaType.IMAGE_VIDEO, MediaType.ALL -> continue
                }
                val resolvedType = resolveType(filter.type, mime)
                val itemUri = MediaType.itemUri(resolvedType, id)
                list += MediaEntity(
                    id = id,
                    uri = itemUri,
                    filePath = if (dataIdx >= 0) c.getString(dataIdx) else null,
                    displayName = c.getString(nameIdx) ?: "",
                    mimeType = mime,
                    sizeBytes = c.getLong(sizeIdx),
                    durationMs = if (durIdx >= 0) c.getLong(durIdx) else 0L,
                    dateAddedSec = c.getLong(dateIdx),
                    width = if (wIdx >= 0) c.getInt(wIdx) else 0,
                    height = if (hIdx >= 0) c.getInt(hIdx) else 0,
                    mediaType = resolvedType,
                    albumId = if (albumIdx >= 0 && resolvedType == MediaType.AUDIO) c.getLong(albumIdx) else 0L,
                )
            }
        }
        return list
    }

    private fun openCursor(
        cr: ContentResolver,
        uri: Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        offset: Int,
        limit: Int,
    ): Cursor? {
        val baseSort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        val paged = limit != Int.MAX_VALUE
        return if (paged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val queryArgs = Bundle().apply {
                putStringArray(
                    ContentResolver.QUERY_ARG_SORT_COLUMNS,
                    arrayOf(MediaStore.MediaColumns.DATE_ADDED),
                )
                putInt(
                    ContentResolver.QUERY_ARG_SORT_DIRECTION,
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING,
                )
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                args?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
            }
            cr.query(uri, projection, queryArgs, null)
        } else {
            val sort = if (paged) "$baseSort LIMIT $offset,$limit" else baseSort
            cr.query(uri, projection, selection, args, sort)
        }
    }

    private fun projectionFor(type: MediaType): Array<String> {
        val base = mutableListOf(
            MediaStore.MediaColumns._ID,
            @Suppress("DEPRECATION") MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        if (type == MediaType.VIDEO || type == MediaType.AUDIO ||
            type == MediaType.IMAGE_VIDEO || type == MediaType.ALL
        ) {
            base += MediaStore.MediaColumns.DURATION
        }
        if (type == MediaType.IMAGE || type == MediaType.VIDEO ||
            type == MediaType.IMAGE_VIDEO || type == MediaType.ALL
        ) {
            base += MediaStore.MediaColumns.WIDTH
            base += MediaStore.MediaColumns.HEIGHT
        }
        if (type == MediaType.AUDIO || type == MediaType.ALL) {
            base += MediaStore.Audio.AudioColumns.ALBUM_ID
        }
        return base.toTypedArray()
    }

    private fun buildSelection(filter: MediaFilter): Pair<String?, Array<String>?> {
        val parts = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (filter.type == MediaType.ALL) {
            val col = MediaStore.Files.FileColumns.MEDIA_TYPE
            parts += "($col=? OR $col=? OR $col=?)"
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO.toString()
        } else if (filter.type == MediaType.IMAGE_VIDEO) {
            val col = MediaStore.Files.FileColumns.MEDIA_TYPE
            parts += "($col=? OR $col=?)"
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString()
            args += MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        }

        if (filter.mimeTypes.isNotEmpty()) {
            val placeholders = filter.mimeTypes.joinToString(",") { "?" }
            parts += "${MediaStore.MediaColumns.MIME_TYPE} IN ($placeholders)"
            args += filter.mimeTypes
        }

        if (filter.minSizeBytes > 0) {
            parts += "${MediaStore.MediaColumns.SIZE} >= ?"
            args += filter.minSizeBytes.toString()
        }

        if (filter.maxDurationMs != Long.MAX_VALUE && filter.type != MediaType.IMAGE) {
            parts += "${MediaStore.MediaColumns.DURATION} <= ?"
            args += filter.maxDurationMs.toString()
        }

        filter.extraSelection?.let {
            parts += "($it)"
            filter.extraArgs?.let { ea -> args += ea }
        }

        return if (parts.isEmpty()) null to null
        else parts.joinToString(" AND ") to args.toTypedArray()
    }

    private fun resolveType(declared: MediaType, mime: String): MediaType {
        if (declared != MediaType.ALL && declared != MediaType.IMAGE_VIDEO) return declared
        return when {
            mime.startsWith("image/") -> MediaType.IMAGE
            mime.startsWith("video/") -> MediaType.VIDEO
            mime.startsWith("audio/") -> MediaType.AUDIO
            else -> declared
        }
    }

    private fun Cursor.optionalIndex(name: String): Int = try {
        getColumnIndex(name)
    } catch (_: Throwable) {
        -1
    }
}
