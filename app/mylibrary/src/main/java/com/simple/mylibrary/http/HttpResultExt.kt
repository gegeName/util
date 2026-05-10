package com.simple.mylibrary.http

/**
 * 把 [HttpResult] 协议层包装拆成业务真实数据。
 *
 * 适用：suspend / Flow / 任意协程返回 [HttpResult] 的接口。配合
 * `com.gzxkwl.common.ktx.launchApi` 使用，code 不为 SUCCESS 时抛 [ApiException]，
 * 由 CoroutineExceptionHandler 路由到全局错误处理。
 *
 * 失败定位：
 * - 显式传 [api] 最准确（推荐）：`unwrap(api = "getUser")`，会写入 [ApiException.apiTag]。
 * - 未传时会自动从调用栈解析最近的非本扩展帧（通常即 HttpHelper 方法名）作为 apiTag。
 *
 * 用法：
 * ```
 * // ApiService
 * @POST("{url}")
 * suspend fun getUser(@Path("url") url: String): HttpResult<UserBean>
 *
 * // HttpHelper（推荐显式传 api 名，混淆后也能稳定识别）
 * suspend fun getUser() = httpService.getUser(url).unwrap(api = "getUser")
 *
 * // 调用侧
 * lifecycleScope.launchApi {
 *     binding.tv.text = HttpHelper.getUser().nickname
 * }
 * ```
 */
@Throws(ApiException::class)
fun <T> HttpResult<T>.unwrap(api: String = ""): T {
    ensureSuccess(api)
    @Suppress("UNCHECKED_CAST")
    return result as T
}

/**
 * 允许 result 为 null 的版本（void / Unit 接口、或 result 字段允许缺省时使用）。
 */
@Throws(ApiException::class)
fun <T> HttpResult<T>.unwrapOrNull(api: String = ""): T? {
    ensureSuccess(api)
    return result
}

@Throws(ApiException::class)
private fun HttpResult<*>.ensureSuccess(api: String) {
    if (success()) return
    val ex = ApiException(errorCode, message.orEmpty())
    ex.apiTag = api.ifEmpty { safeResolveCallerTag(ex.stackTrace) }
    throw ex
}

private const val SELF_KT_PREFIX = "com.simple.mylibrary.http.HttpResultExtKt"
private const val HTTP_RESULT_PREFIX = "com.simple.mylibrary.http.HttpResult"

/**
 * 跳过 HttpResultExtKt 自身与 HttpResult / DefaultImpls / DefaultHttpResult 帧，
 * 取首个业务调用帧作为 apiTag。混淆后类名会变化，因此推荐调用方显式传 [unwrap] 的 `api` 参数；
 * 此函数仅作 debug 兜底。
 *
 * 注意：HttpResult 现在是接口，`success()` 默认实现可能落在 `HttpResult$DefaultImpls`，
 * 用 startsWith 一次性把这些帧全部跳过。
 *
 * 任何异常（空栈、字符串处理 NPE 等）都被吞掉返回空串，绝不影响 ApiException 主流程。
 */
private fun safeResolveCallerTag(stack: Array<StackTraceElement>?): String =
    runCatching {
        stack?.forEach { frame ->
            val cn = frame.className ?: return@forEach
            if (cn.startsWith(SELF_KT_PREFIX)) return@forEach
            if (cn.startsWith(HTTP_RESULT_PREFIX)) return@forEach
            return@runCatching "${cn.substringAfterLast('.')}.${frame.methodName.orEmpty()}"
        }
        ""
    }.getOrDefault("")
