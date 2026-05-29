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
     * - API 34+：图片/视频/ALL 附带 READ_MEDIA_VISUAL_USER_SELECTED
     *   （用户可能只授予"选择部分照片"）
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
            MediaType.ALL -> {
                list += Manifest.permission.READ_MEDIA_IMAGES
                list += Manifest.permission.READ_MEDIA_VIDEO
                list += Manifest.permission.READ_MEDIA_AUDIO
            }
        }
        // API 34+：图片/视频相关类型，附带"部分授权"权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            type != MediaType.AUDIO
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
     * 是否可继续使用（包括"部分授权"）：
     * - 任一媒体读取权限 granted → 可继续
     * - API 34+：仅 VISUAL_USER_SELECTED granted 也视为可用（用户选择了部分照片）
     */
    fun anyUsable(ctx: Context, perms: Array<String>): Boolean =
        perms.any {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

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
