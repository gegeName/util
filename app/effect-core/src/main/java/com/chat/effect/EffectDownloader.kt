package com.chat.effect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 特效资源下载器。
 */
internal object EffectDownloader {

    private const val TAG = "EffectDownloader"
    private const val MAX_CONCURRENT = 3
    private const val MAX_ATTEMPTS = 2

    private val semaphore = Semaphore(MAX_CONCURRENT)
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun fetch(resource: EffectResource): String? {
        val url = resource.url
        if (url.isBlank()) return null

        val cacheFile = cacheFileOf(url, resource.type)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            return cacheFile.absolutePath
        }

        val deferred = inFlight.getOrPut(url) {
            scope.async {
                try {
                    semaphore.withPermit { doDownload(url, cacheFile) }
                } finally {
                    inFlight.remove(url)
                }
            }
        }
        return deferred.await()
    }

    fun preload(resource: EffectResource) {
        if (resource.url.isBlank()) return
        val cacheFile = cacheFileOf(resource.url, resource.type)
        if (cacheFile.exists() && cacheFile.length() > 0) return
        if (inFlight.containsKey(resource.url)) return

        val deferred = scope.async {
            try {
                semaphore.withPermit { doDownload(resource.url, cacheFile) }
            } finally {
                inFlight.remove(resource.url)
            }
        }
        inFlight[resource.url] = deferred
    }

    private suspend fun doDownload(url: String, cacheFile: File): String? = withContext(Dispatchers.IO) {
        val tempFile = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        repeat(MAX_ATTEMPTS) { idx ->
            val attempt = idx + 1
            val startByte = if (tempFile.exists()) tempFile.length() else 0L
            try {
                val request = Request.Builder()
                    .url(url)
                    .apply { if (startByte > 0) header("Range", "bytes=$startByte-") }
                    .build()
                DownloadClient.okHttpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> writeBody(response, tempFile, append = false)
                        206 -> writeBody(response, tempFile, append = true)
                        416 -> {
                            EffectLog.e(TAG) {
                                "range not satisfiable, drop tmp attempt=$attempt url=$url localSize=$startByte"
                            }
                            tempFile.delete()
                            return@use
                        }
                        else -> {
                            EffectLog.e(TAG) {
                                "http ${response.code} attempt=$attempt url=$url offset=$startByte"
                            }
                            return@use
                        }
                    }
                    if (tempFile.renameTo(cacheFile)) {
                        return@withContext cacheFile.absolutePath
                    }
                    EffectLog.e(TAG) { "rename failed attempt=$attempt url=$url" }
                    tempFile.delete()
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                EffectLog.e(TAG, e) {
                    "download failed attempt=$attempt url=$url offset=$startByte"
                }
            }
        }
        EffectLog.e(TAG) {
            "download give up after $MAX_ATTEMPTS attempts url=$url tmpSize=${if (tempFile.exists()) tempFile.length() else 0L}"
        }
        null
    }

    private fun writeBody(response: okhttp3.Response, tempFile: File, append: Boolean) {
        FileOutputStream(tempFile, append).use { out ->
            response.body.byteStream().use { input -> input.copyTo(out) }
        }
    }

    private fun cacheFileOf(url: String, type: EffectType): File {
        val name = "${EffectIO.md5(url)}.${type.key}"
        val dir = EffectIO.effectCacheDir()
            ?: error("EffectManager.init(context) 未调用,无法获取缓存目录")
        return File(dir, name)
    }
}
