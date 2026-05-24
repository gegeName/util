package com.chat.svgaspan

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.chat.spanutil.SpanLog
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import java.net.URL

/**
 * SVGA Entity 共享缓存。同 URL 在多个 TextView 间复用，避免重复解码。
 */
object SvgaCache {

    private const val TAG = "SvgaCache"

    private fun estimateBytes(entity: SVGAVideoEntity): Int {
        val w = entity.videoSize.width.toInt().coerceAtLeast(1)
        val h = entity.videoSize.height.toInt().coerceAtLeast(1)
        val frames = entity.frames.coerceAtLeast(1)
        val bytesPerFrame = w * h * 4
        val estimated = bytesPerFrame.toLong() * frames * 2
        return estimated.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            .coerceAtLeast(1024 * 1024)
    }

    private val cache: LruCache<String, SVGAVideoEntity> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt() / 8
        object : LruCache<String, SVGAVideoEntity>(maxKb) {
            override fun sizeOf(key: String, value: SVGAVideoEntity): Int =
                estimateBytes(value) / 1024
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 加载 SVGA Entity，命中缓存直接 [onReady]，未命中时走 SVGAParser 解析（assets / http）。
     *
     * @param context 用于初始化 [SVGAParser]
     * @param key     URL 或 assets 文件名，作为缓存键
     * @param onReady 解析成功后在主线程回调
     * @param onError 解析失败时在主线程回调（可空）
     */
    fun load(
        context: Context,
        key: String,
        onReady: (SVGAVideoEntity) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        cache.get(key)?.let {
            SpanLog.i(TAG) { "cache hit: $key" }
            mainHandler.post { onReady(it) }
            return
        }
        SpanLog.i(TAG) { "cache miss, start parse: $key" }
        val parser = SVGAParser.shareParser().apply { init(context) }
        val callback = object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                SpanLog.i(TAG) {
                    "parse complete: $key  frames=${videoItem.frames} fps=${videoItem.FPS} " +
                            "size=${videoItem.videoSize.width}x${videoItem.videoSize.height}"
                }
                cache.put(key, videoItem)
                mainHandler.post { onReady(videoItem) }
            }

            override fun onError() {
                SpanLog.w(TAG) { "parse error: $key" }
                onError?.let { mainHandler.post(it) }
            }
        }

        when {
            key.startsWith("http://", true) || key.startsWith("https://", true) -> {
                runCatching {
                    parser.decodeFromURL(URL(key), callback)
                }.onFailure {
                    SpanLog.w(TAG, it) { "decodeFromURL threw: $key" }
                    onError?.let { cb -> mainHandler.post(cb) }
                }
            }

            else -> {
                runCatching { parser.decodeFromAssets(key, callback) }
                    .onFailure {
                        SpanLog.w(TAG, it) { "decodeFromAssets failed: $key" }
                        onError?.let { cb -> mainHandler.post(cb) }
                    }
            }
        }
    }

    /**
     * 清空全部缓存，业务方可在低内存回调里调用。
     */
    fun trimMemory() = cache.evictAll()

    /**
     * 温和驱逐到当前容量一半，保留最近常用 entity。
     */
    fun trimMemoryHalf() {
        cache.trimToSize(cache.maxSize() / 2)
    }
}
