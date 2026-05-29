package com.chat.picker.loader

import android.content.res.Resources
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.util.concurrent.Executors

/** 默认引擎：复用内置 ImageLoader（LruCache + Bitmap 采样 + MediaMetadataRetriever 视频首帧） */
internal object DefaultImageEngine : IImageEngine {

    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val originalToken = "default_engine_original_token".hashCode()

    /** 预览大图最大边：屏幕长边 + 一点裕量；至多 2048 防止极端设备 */
    private val maxOriginalSize: Int by lazy {
        val dm = Resources.getSystem().displayMetrics
        val longSide = maxOf(dm.widthPixels, dm.heightPixels)
        // 上限 2048 是经验值：8MP 屏 + RGB_565 单张 ≈ 8MB，多张可控
        minOf(longSide, 2048).coerceAtLeast(720)
    }

    override fun loadThumbnail(view: ImageView, uri: Uri, isVideo: Boolean) {
        ImageLoader.load(view, uri, isVideo, 360, 360)
    }

    override fun loadOriginal(view: ImageView, uri: Uri, isVideo: Boolean) {
        val current = (view.getTag(originalToken) as? Int ?: 0) + 1
        view.setTag(originalToken, current)
        view.setImageDrawable(null)
        val ctx = view.context.applicationContext
        val target = maxOriginalSize
        pool.execute {
            val bmp: Bitmap? = ImageLoader.decodeOriginalSync(ctx, uri, target)
            main.post {
                if (view.getTag(originalToken) == current && bmp != null) {
                    view.setImageBitmap(bmp)
                }
            }
        }
    }
}
