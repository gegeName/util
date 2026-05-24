package com.chat.effect

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 特效模块内置的下载用 OkHttpClient 单例。
 *
 * 业务侧若已有共享 OkHttpClient（鉴权拦截器 / 统一超时配置等），可在 Application 中
 * 调 [setSharedClient] 注入；不调则使用内置默认客户端。
 */
internal object DownloadClient {

    @Volatile
    private var injected: OkHttpClient? = null

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    val okHttpClient: OkHttpClient
        get() = injected ?: defaultClient

    /**
     * 业务方注入自定义 OkHttpClient（推荐复用项目中已有的 OkHttpClient 以共享连接池）。
     *
     * @param client 业务侧构造好的 OkHttpClient
     */
    @JvmStatic
    fun setSharedClient(client: OkHttpClient) {
        injected = client
    }
}
