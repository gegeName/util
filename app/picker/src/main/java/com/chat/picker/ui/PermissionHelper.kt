package com.chat.picker.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.chat.picker.model.MediaType

internal object PermissionHelper {

    /**
     * 返回需要申请的权限列表。
     * - API ≤ 32：READ_EXTERNAL_STORAGE
     * - API 33：按类型申请细分权限
     * - API 34+：图片/IMAGE_VIDEO/ALL 附带 READ_MEDIA_VISUAL_USER_SELECTED
     *   （视频单类型/音频不走"部分访问"，附带反而会让用户误以为部分授权也算"给了视频权限"）
     */
    fun requiredPermissions(type: MediaType): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val list = mutableListOf<String>()
        when (type) {
            MediaType.IMAGE -> list += Manifest.permission.READ_MEDIA_IMAGES
            MediaType.VIDEO -> list += Manifest.permission.READ_MEDIA_VIDEO
            MediaType.AUDIO -> list += Manifest.permission.READ_MEDIA_AUDIO
            MediaType.IMAGE_VIDEO -> {
                list += Manifest.permission.READ_MEDIA_IMAGES
                list += Manifest.permission.READ_MEDIA_VIDEO
            }
            MediaType.ALL -> {
                list += Manifest.permission.READ_MEDIA_IMAGES
                list += Manifest.permission.READ_MEDIA_VIDEO
                list += Manifest.permission.READ_MEDIA_AUDIO
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (type == MediaType.IMAGE || type == MediaType.IMAGE_VIDEO || type == MediaType.ALL)
        ) {
            list += "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        }
        return list.toTypedArray()
    }

    /** 完全授权（全部 granted） */
    fun allGranted(ctx: Context, perms: Array<String>): Boolean =
        perms.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * 是否可继续使用：
     * - 该 type 需要的"主权限"任一 granted → 可继续
     * - API 34+ 图片相关 type（IMAGE / IMAGE_VIDEO / ALL）才允许"仅 VISUAL_USER_SELECTED granted"算可用
     *   （视频单类型/音频不依赖该权限拿数据，只有它 granted 时查 MediaStore 仍是空集）
     */
    fun anyUsable(ctx: Context, type: MediaType): Boolean {
        val mainPerms = mainPermissions(type)
        if (mainPerms.any { granted(ctx, it) }) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            (type == MediaType.IMAGE || type == MediaType.IMAGE_VIDEO || type == MediaType.ALL)
        ) {
            return granted(ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
        }
        return false
    }

    private fun mainPermissions(type: MediaType): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return when (type) {
            MediaType.IMAGE -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            MediaType.VIDEO -> listOf(Manifest.permission.READ_MEDIA_VIDEO)
            MediaType.AUDIO -> listOf(Manifest.permission.READ_MEDIA_AUDIO)
            MediaType.IMAGE_VIDEO -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            MediaType.ALL -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
            )
        }
    }

    private fun granted(ctx: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    /**
     * 是否处于"部分授权"状态（仅 API 34+ 才会出现）。
     * 此时应在 UI 上提示用户"管理可访问的照片"。
     */
    fun isPartialAccess(ctx: Context, type: MediaType): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        if (type == MediaType.AUDIO) return false
        val visualSelected = ContextCompat.checkSelfPermission(
            ctx, "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ) == PackageManager.PERMISSION_GRANTED
        val imagesFull = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_MEDIA_IMAGES
        ) == PackageManager.PERMISSION_GRANTED
        val videoFull = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED
        // 仅"部分授权"granted，完整权限未 granted
        return visualSelected && !imagesFull && !videoFull
    }
}
