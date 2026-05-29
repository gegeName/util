package com.chat.picker.loader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 轻量异步图片加载（OOM 友好）：
 * - 缩略图 RGB_565 采样 + LruCache（缩略图专用，约可用内存 1/8）
 * - 视频首帧用 MediaMetadataRetriever，full bitmap 用完立即 recycle
 * - 大图（预览页）不进缓存，避免单张 8~16MB 占满缓存挤掉缩略图
 * - 所有 decode 路径 catch OOM 并自动二次重试（提高 inSampleSize）
 */
object ImageLoader {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
    )

    private val cache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        object : LruCache<String, Bitmap>(maxKb / 8) {
            override fun sizeOf(key: String, value: Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
    }

    private val tagKey = "imageloader_token".hashCode()
    private val seq = AtomicInteger()

    fun load(
        view: ImageView,
        uri: Uri,
        isVideo: Boolean,
        targetWidth: Int = 300,
        targetHeight: Int = 300,
    ) {
        val key = "$uri@${targetWidth}x$targetHeight"
        val token = seq.incrementAndGet()
        view.setTag(tagKey, token)

        cache.get(key)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)

        val ctx = view.context.applicationContext
        pool.execute {
            val bmp = try {
                if (isVideo) decodeVideoFrame(ctx, uri, targetWidth, targetHeight)
                else decodeImage(ctx, uri, targetWidth, targetHeight)
            } catch (oom: OutOfMemoryError) {
                trimOnOom()
                null
            } catch (_: Throwable) {
                null
            } ?: return@execute
            cache.put(key, bmp)
            main.post {
                if (view.getTag(tagKey) == token) view.setImageBitmap(bmp)
            }
        }
    }

    /** 同步加载原图（用于预览页大图，已在工作线程调用）；不进缓存 */
    fun decodeOriginalSync(ctx: Context, uri: Uri, maxSize: Int): Bitmap? = try {
        decodeImage(ctx, uri, maxSize, maxSize)
    } catch (oom: OutOfMemoryError) {
        trimOnOom()
        // 降级再试一次：直接半采样
        try {
            decodeImage(ctx, uri, maxSize / 2, maxSize / 2)
        } catch (_: Throwable) {
            null
        }
    } catch (_: Throwable) {
        null
    }

    private fun decodeImage(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(ctx, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = calcSample(bounds.outWidth, bounds.outHeight, w, h)
        // OOM 二次兜底
        repeat(3) {
            try {
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                return openStream(ctx, uri).use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (_: OutOfMemoryError) {
                trimOnOom()
                sample *= 2
            }
        }
        return null
    }

    private fun decodeVideoFrame(ctx: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val full = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            try {
                val sample = calcSample(full.width, full.height, w, h)
                if (sample <= 1) {
                    full
                } else {
                    val scaled = Bitmap.createScaledBitmap(
                        full, full.width / sample, full.height / sample, false
                    )
                    if (scaled !== full) full.recycle()
                    scaled
                }
            } catch (oom: OutOfMemoryError) {
                trimOnOom()
                full.recycle()
                null
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun trimOnOom() {
        try {
            cache.trimToSize(cache.maxSize() / 2)
        } catch (_: Throwable) { /* ignore */ }
        System.gc()
    }

    private fun openStream(ctx: Context, uri: Uri): InputStream =
        ctx.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("openInputStream null: $uri")

    private fun calcSample(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
        if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
        var sample = 1
        var hw = srcW / 2
        var hh = srcH / 2
        while (hw / sample >= reqW && hh / sample >= reqH) sample *= 2
        return sample
    }

    fun clear() = cache.evictAll()
}
