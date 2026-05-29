package com.chat.picker.camera

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaType
import java.io.File

/**
 * 拍照工具。
 *
 * 兼容策略：所有 API 都用 **FileProvider + cacheDir** 作为相机 EXTRA_OUTPUT
 * （部分国产 ROM/相机 app 对 MediaStore content uri 写入支持不完善）。
 * 拍完异步注册到 MediaStore，下次进 picker 即可查到该文件。
 */
internal object CameraHelper {

    private const val AUTHORITY_SUFFIX = ".chat.picker.fileprovider"
    private const val DIR_NAME = "picker_camera"
    private const val SUBFOLDER = "Camera"

    /** 一次拍照请求的上下文 */
    class Pending(
        /** 给相机 app 写入的 FileProvider uri */
        val uri: Uri,
        /** 相机写入的真实文件路径（cacheDir 下） */
        val filePath: String,
        val onSuccess: () -> Unit,
        val onFail: () -> Unit,
    )

    fun prepare(ctx: Context): Pending {
        val app = ctx.applicationContext
        val dir = File(app.cacheDir, DIR_NAME).apply { mkdirs() }
        val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}$AUTHORITY_SUFFIX", file)
        return Pending(
            uri = uri,
            filePath = file.absolutePath,
            onSuccess = {
                // 拍照成功 → 注册到系统媒体库；不阻塞 UI
                Thread { registerToMediaStore(app, file) }.start()
            },
            onFail = { runCatching { file.delete() } },
        )
    }

    /** 把拍照得到的结果构造成 MediaEntity，用于插入 picker 列表 */
    fun makeEntity(filePath: String, uri: Uri): MediaEntity {
        val now = System.currentTimeMillis()
        val file = File(filePath)
        val size = if (file.exists()) file.length() else 0L
        return MediaEntity(
            id = -now,
            uri = uri,
            filePath = filePath,
            displayName = file.name,
            mimeType = "image/jpeg",
            sizeBytes = size,
            durationMs = 0,
            dateAddedSec = now / 1000,
            width = 0,
            height = 0,
            mediaType = MediaType.IMAGE,
        )
    }

    /** CAMERA 权限是否已授予 */
    fun hasCameraPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 独立拍照入口。框架自动申请 CAMERA 权限。
     */
    fun take(
        activity: ComponentActivity,
        onResult: (success: Boolean, filePath: String?, uri: Uri?) -> Unit,
    ) {
        if (hasCameraPermission(activity)) {
            doLaunchCamera(activity, onResult); return
        }
        val permLauncher = activity.activityResultRegistry.register(
            "picker_camera_perm_${System.currentTimeMillis()}",
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) doLaunchCamera(activity, onResult)
            else onResult(false, null, null)
        }
        permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun doLaunchCamera(
        activity: ComponentActivity,
        onResult: (success: Boolean, filePath: String?, uri: Uri?) -> Unit,
    ) {
        val pending = prepare(activity)
        val launcher = activity.activityResultRegistry.register(
            "picker_camera_${System.currentTimeMillis()}",
            ActivityResultContracts.TakePicture(),
        ) { success ->
            val file = File(pending.filePath)
            val exists = file.exists()
            val len = if (exists) file.length() else 0L
            com.chat.picker.util.PickerLog.d(
                "TakePicture result success=$success exists=$exists size=$len path=${pending.filePath}"
            )
            val ok = success && exists && len > 0
            if (ok) {
                pending.onSuccess()
                onResult(true, pending.filePath, pending.uri)
            } else {
                pending.onFail()
                onResult(false, null, null)
            }
        }
        launcher.launch(pending.uri)
    }

    // ===== MediaStore 入库 =====

    /**
     * 把 cache 中的图片 copy 到系统媒体库，让下次 picker 查询时可见。
     * 此方法在工作线程调用；失败静默。
     */
    private fun registerToMediaStore(ctx: Context, file: File) {
        if (!file.exists() || file.length() <= 0) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                copyToMediaStoreQ(ctx, file)
            } else {
                copyToPicturesLegacy(ctx, file)
            }
        } catch (_: Throwable) { /* 注册失败不影响主流程 */ }
    }

    private fun copyToMediaStoreQ(ctx: Context, src: File) {
        val cr = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, src.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + SUBFOLDER,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            cr.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            val finish = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            cr.update(uri, finish, null, null)
        } catch (e: Throwable) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
    }

    private fun copyToPicturesLegacy(ctx: Context, src: File) {
        // API < 29：复制到公共 Pictures 目录 + scanFile
        val picturesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            SUBFOLDER,
        ).apply { if (!exists()) mkdirs() }
        val dst = File(picturesDir, src.name)
        src.inputStream().use { input ->
            dst.outputStream().use { input.copyTo(it) }
        }
        MediaScannerConnection.scanFile(
            ctx, arrayOf(dst.absolutePath), arrayOf("image/jpeg"), null,
        )
    }
}
