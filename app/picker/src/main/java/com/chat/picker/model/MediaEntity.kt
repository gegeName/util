package com.chat.picker.model

import android.net.Uri
import android.os.Parcel
import android.os.Parcelable

data class MediaEntity(
    val id: Long,
    val uri: Uri,
    /**
     * 真实文件路径（来自 MediaStore.MediaColumns.DATA）。
     * - API ≤ 28：可直接读取
     * - API 29+：scoped storage 下该列仍能取到，但用户进程可能无权直接 file IO；
     *   优先使用 [uri] + ContentResolver。某些第三方压缩库仍需 file path，本字段保留方便对接
     * - 系统 Photo Picker 返回的 Uri 拿不到该字段，会为 null
     */
    val filePath: String?,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val mediaType: MediaType,
) : Parcelable {

    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isAudio: Boolean get() = mimeType.startsWith("audio/")

    constructor(parcel: Parcel) : this(
        parcel.readLong(),
        Uri.parse(parcel.readString().orEmpty()),
        parcel.readString(),
        parcel.readString().orEmpty(),
        parcel.readString().orEmpty(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readLong(),
        parcel.readInt(),
        parcel.readInt(),
        MediaType.values()[parcel.readInt()],
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(id)
        dest.writeString(uri.toString())
        dest.writeString(filePath)
        dest.writeString(displayName)
        dest.writeString(mimeType)
        dest.writeLong(sizeBytes)
        dest.writeLong(durationMs)
        dest.writeLong(dateAddedSec)
        dest.writeInt(width)
        dest.writeInt(height)
        dest.writeInt(mediaType.ordinal)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MediaEntity> {
        override fun createFromParcel(parcel: Parcel) = MediaEntity(parcel)
        override fun newArray(size: Int): Array<MediaEntity?> = arrayOfNulls(size)
    }
}
