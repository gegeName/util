package com.lhj.spanutil.span

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import com.opensource.svgaplayer.SVGAParser
import com.opensource.svgaplayer.SVGAVideoEntity
import java.net.URL

/**
 * SVGA Entity 共享缓存。
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

    fun load(
        context: Context,
        key: String,
        onReady: (SVGAVideoEntity) -> Unit,
        onError: (() -> Unit)? = null,
    ) {
        cache.get(key)?.let {
            Log.i(TAG, "cache hit: $key")
            mainHandler.post { onReady(it) }
            return
        }
        Log.i(TAG, "cache miss, start parse: $key")
        val parser = SVGAParser.shareParser().apply { init(context) }
        val callback = object : SVGAParser.ParseCompletion {
            override fun onComplete(videoItem: SVGAVideoEntity) {
                Log.i(
                    TAG,
                    "parse complete: $key  frames=${videoItem.frames} fps=${videoItem.FPS} " +
                            "size=${videoItem.videoSize.width}x${videoItem.videoSize.height}"
                )
                cache.put(key, videoItem)
                mainHandler.post { onReady(videoItem) }
            }

            override fun onError() {
                Log.w(TAG, "parse error: $key")
                onError?.let { mainHandler.post(it) }
            }
        }

        when {
            key.startsWith("http://", true) || key.startsWith("https://", true) -> {
                runCatching {
                    parser.decodeFromURL(URL(key), callback)
                }.onFailure {
                    Log.w(TAG, "decodeFromURL threw: $key", it)
                    onError?.let { cb -> mainHandler.post(cb) }
                }
            }

            else -> {
                runCatching { parser.decodeFromAssets(key, callback) }
                    .onFailure {
                        Log.w(TAG, "decodeFromAssets failed: $key", it)
                        onError?.let { cb -> mainHandler.post(cb) }
                    }
            }
        }
    }

    /** 全部驱逐,业务方在低内存回调里调。 */
    fun trimMemory() = cache.evictAll()

    /** 温和驱逐到当前容量的一半,保留最近常用 entity。适合普通内存压力。 */
    fun trimMemoryHalf() {
        cache.trimToSize(cache.maxSize() / 2)
    }
}
