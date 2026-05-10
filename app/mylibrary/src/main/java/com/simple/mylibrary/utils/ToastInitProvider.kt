package com.simple.mylibrary.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * 仅用于在 App 进程启动时自动把 ApplicationContext 注入 [ToastUtils]，
 * 让调用方无需手动 init。所有 CRUD 接口都返回空 / 0。
 */
internal class ToastInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ToastUtils.init(it) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
