package com.simple.mylibrary.http

import com.simple.mylibrary.utils.SLog
import com.simple.mylibrary.utils.ToastUtils
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 全局统一 API 错误处理程序。
 *
 * 所有业务层未处理的异常最终都会经过 [handle]：
 * - [ApiException]：交由业务注册的处理器决定（按 errorCode 路由：跳登录 / 踢出房间 / 业务弹窗 / toast 等）
 * - [HttpException]：传输层非 2xx 响应
 * - 网络异常：超时、DNS、连接失败等
 * - 其他未识别异常
 *
 * 文案策略：所有 toast 文案都从业务注入的 [messages] 取，**不内置任何默认文案**（避免基础库
 * 写死英文，干扰国际化）。[messages] 未注入或某条返回 null/空串时直接不弹 toast。
 *
 * 业务接入（一般在 App 初始化时执行一次）：
 * ```
 * // 1. 文案
 * ApiErrorHandler.messages = object : ApiErrorHandler.Messages {
 *     override fun serviceError(httpCode: Int) = getString(R.string.service_error_fmt, httpCode)
 *     override fun networkTimeout()             = getString(R.string.network_timeout)
 *     override fun networkUnavailable()         = getString(R.string.network_unavailable)
 *     override fun unknown(t: Throwable)        = t.message
 *     override fun businessFallback(e: ApiException) = e.message
 * }
 *
 * // 2. 精确路由：按 errorCode 注册
 * ApiErrorHandler.onCode("401") { LoginRouter.toLogin(); true }
 * ApiErrorHandler.onCode("ROOM_KICKED") { e ->
 *     ActivityStack.finishActivity("com.gzxkwl.partyroom.page.activity.PartyRoomActivity")
 *     ToastUtils.showShort(e.message); true
 * }
 *
 * // 3. 兜底：未被 onCode 命中的 ApiException 走这里
 * ApiErrorHandler.onBusiness { e ->
 *     when {
 *         e.errorCode.startsWith("WALLET_") -> { showWalletDialog(e); true }
 *         else -> false
 *     }
 * }
 * ```
 *
 * 处理顺序（ApiException）：`onCode(errorCode)` → `onBusiness(兜底)` → `messages.businessFallback`。
 * 任一环节返回 true 即停止后续，避免重复弹 toast / 跳页。
 */
object ApiErrorHandler {

    /**
     * 文案提供器。所有内置 toast 文案从这里取；未设置（保持 null）时框架完全静默。
     *
     * 单条接口可返回 null —— 该类异常便不再 toast，留给业务自己决定（典型：网络异常想统一在
     * 上层 networkBanner 显示而不弹 toast）。
     */
    @Volatile
    var messages: Messages? = null

    /** errorCode → handler；handler 返回 true 表示"已处理"，链路结束 */
    private val codeHandlers = mutableMapOf<String, (ApiException) -> Boolean>()

    /** 兜底业务处理器：所有未被 [codeHandlers] 命中的 ApiException 走这里 */
    private var fallbackHandler: ((ApiException) -> Boolean)? = null

    /**
     * 文案接入点。每个方法对应 [handle] 中的一类异常分支。
     * 返回 null 或空串表示"该分支不弹 toast"，框架会跳过 toast，但日志依旧打印。
     */
    interface Messages {
        /** [HttpException] 传输层非 2xx；[httpCode] 即 HTTP 状态码 */
        fun serviceError(httpCode: Int): CharSequence?

        /** [SocketTimeoutException] 网络超时 */
        fun networkTimeout(): CharSequence?

        /** [UnknownHostException] / [ConnectException] 等"网络不可达"类异常 */
        fun networkUnavailable(): CharSequence?

        /** 其它未分类异常的兜底文案；可返回 [Throwable.message] 或固定提示 */
        fun unknown(throwable: Throwable): CharSequence?

        /** [ApiException] 未被 onCode/onBusiness 消化时的兜底文案；可返回 [ApiException.message] */
        fun businessFallback(e: ApiException): CharSequence?
    }

    /**
     * 注册指定 [code] 的处理器。同一个 code 重复注册时后者覆盖前者。
     * @param handler 返回 true = 已处理（不再走兜底 / 默认 toast）；false = 继续往下传递
     */
    fun onCode(code: String, handler: (ApiException) -> Boolean) {
        codeHandlers[code] = handler
    }

    /**
     * 注册兜底业务处理器，所有未在 [onCode] 命中的 ApiException 都会走这里。
     * 返回 true 时框架不再弹默认 toast。多次调用以最后一次为准。
     */
    fun onBusiness(handler: (ApiException) -> Boolean) {
        fallbackHandler = handler
    }

    /** 移除某个 errorCode 的注册 */
    fun removeCode(code: String) {
        codeHandlers.remove(code)
    }

    /** 清空所有注册与文案（典型场景：退出登录、模块卸载、单测 tearDown） */
    fun reset() {
        codeHandlers.clear()
        fallbackHandler = null
        messages = null
    }

    fun handle(throwable: Throwable) {
        val apiTag = (throwable as? ApiException)?.apiTag.orEmpty()
        val logTag = if (apiTag.isNotEmpty()) "ApiError[$apiTag]" else "ApiError"
        SLog.e(logTag, throwable) { "" }
        val m = messages
        when (throwable) {
            is ApiException -> dispatchBusiness(throwable, m)
            is HttpException -> m?.serviceError(throwable.code()).toastIfNotBlank()
            is SocketTimeoutException -> m?.networkTimeout().toastIfNotBlank()
            is UnknownHostException, is ConnectException -> m?.networkUnavailable().toastIfNotBlank()
            else -> m?.unknown(throwable).toastIfNotBlank()
        }
    }

    private fun dispatchBusiness(e: ApiException, m: Messages?) {
        val handled = codeHandlers[e.errorCode]?.invoke(e) == true
            || fallbackHandler?.invoke(e) == true
        if (!handled) {
            m?.businessFallback(e).toastIfNotBlank()
        }
    }

    private fun CharSequence?.toastIfNotBlank() {
        if (!this.isNullOrBlank()) ToastUtils.showShort(this)
    }
}
