package com.lhj.effect

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * 特效模块内部使用的小工具。
 *
 * 业务方不需要直接调用——由 [EffectManager.init] / [EffectDownloader] 内部使用。
 */
internal object EffectIO {

    @Volatile
    private var appContext: Context? = null

    /**
     * 由 [EffectManager.init] 调用一次，注入 ApplicationContext，作为缓存目录与 SVGAParser 的 Context 来源。
     *
     * @param context 任意 Context，内部取 [Context.getApplicationContext]
     */
    fun init(context: Context) {
        appContext = context.applicationContext ?: context
    }

    /**
     * 取 ApplicationContext。未 init 时返回 null（调用方应当容错或提示业务方先调 init）。
     */
    fun appContext(): Context? = appContext

    /**
     * 拿到特效缓存目录（自动创建）。优先 [Context.getExternalCacheDir]，回退到 [Context.getCacheDir]。
     */
    fun effectCacheDir(): File? {
        val ctx = appContext ?: return null
        val base = ctx.externalCacheDir ?: ctx.cacheDir
        val dir = File(base, "effect")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 计算字符串的 MD5 十六进制摘要（小写）。失败返回原串的 hashCode 字符串作为兜底。
     *
     * @param input 输入字符串
     */
    fun md5(input: String): String = runCatching {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                if (v < 0x10) append('0')
                append(v.toString(16))
            }
        }
    }.getOrElse { input.hashCode().toString() }
}
