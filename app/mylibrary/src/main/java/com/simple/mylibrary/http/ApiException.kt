package com.simple.mylibrary.http

/**
 * Created by Alexis.Shelton on 15/12/2020.
 */
class ApiException : RuntimeException {

    var errorCode: String = ""

    /**
     * 失败的接口标识。优先由 `HttpResult.unwrap(api = ...)` 显式传入；
     * 未传时由 `HttpResultExt` 自动从调用栈解析（通常即为 HttpHelper 方法名），
     * 便于日志、Bugly 上报与差异化业务处理。
     */
    var apiTag: String = ""

    constructor(message: String?) : super(message)

    constructor(errorCode: String, message: String?) : super(message) {
        this.errorCode = errorCode
    }

    constructor(errorCode: String, message: String?, apiTag: String) : super(message) {
        this.errorCode = errorCode
        this.apiTag = apiTag
    }

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
