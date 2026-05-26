package com.chat.mylibrary.http

/**
 * 通用 HTTP 响应包装的协议接口。
 *
 * 框架**只依赖语义**，不绑定字段类型、字段名、序列化库：
 * - [success]    业务自定义判定（典型：`code == 0`、`status == "OK"`、HTTP 200 等）
 * - [message]    失败描述文案，做 [ApiException.message]
 * - [result]     业务数据，[unwrap] 返回它
 * - [errorCode]  失败时给 [ApiException.errorCode]，供 [ApiErrorHandler.onCode] 路由用；
 *                业务后端如果是 Int / Long / 没有这个字段，自己在 getter 里 `toString()` 或留空串
 *
 * 框架不约束：
 * - `code` 字段的类型（String / Int / 不存在）—— 接口压根不暴露 `code`
 * - JSON 字段名 —— 实现类按自己的库（Gson `@SerializedName` / Moshi `@Json` / kotlinx `@SerialName` /
 *   原生反射默认按字段名）打注解，框架不参与
 * - 序列化库 —— 接口里没有任何序列化库依赖
 *
 * **场景 1：String code，字段名为 code/message/result（最常见）**
 * 直接用 [DefaultHttpResult]：
 * ```
 * @POST("user") suspend fun getUser(): DefaultHttpResult<UserBean>
 * ```
 *
 * **场景 2：Int code，字段名是 code/msg/data，用 Gson**
 * ```
 * data class MyGsonResult<T>(
 *     @SerializedName("code") val codeInt: Int? = null,
 *     @SerializedName("msg")  val msg: String?  = null,
 *     @SerializedName("data") val data: T?      = null,
 * ) : HttpResult<T> {
 *     override fun success() = codeInt == 0
 *     override val message: String? get() = msg
 *     override val result: T?       get() = data
 *     override val errorCode: String get() = codeInt?.toString().orEmpty()
 * }
 * ```
 *
 * **场景 3：Int code，用 Moshi**
 * ```
 * @JsonClass(generateAdapter = true)
 * data class MyMoshiResult<T>(
 *     @Json(name = "code") val codeInt: Int? = null,
 *     @Json(name = "msg")  val msg: String?  = null,
 *     @Json(name = "data") val data: T?      = null,
 * ) : HttpResult<T> {
 *     override fun success() = codeInt == 200
 *     override val message: String? get() = msg
 *     override val result: T?       get() = data
 *     override val errorCode: String get() = codeInt?.toString().orEmpty()
 * }
 * ```
 *
 * **场景 4：kotlinx.serialization**
 * ```
 * @Serializable
 * data class MyKxResult<T>(
 *     @SerialName("code") val codeInt: Int = -1,
 *     @SerialName("msg")  val msg: String? = null,
 *     @SerialName("data") val data: T?     = null,
 * ) : HttpResult<T> {
 *     override fun success() = codeInt == 0
 *     override val message: String? get() = msg
 *     override val result: T?       get() = data
 *     override val errorCode: String get() = codeInt.toString()
 * }
 * ```
 *
 * 设计点：用接口而不是 data class —— Retrofit + 任意 Converter 必须反序列化到**具体 class**，
 * 所以业务在 ApiService 声明的是自家的实现类；框架扩展（[unwrap] / [unwrapOrNull]）操作接口，
 * 对所有实现透明。这就是"字段类型 / 字段名 / 序列化方式都由外部决定，而框架仍能拿到语义值"的实现方式。
 */
interface HttpResult<T> {
    /** 成功判定 —— 业务自己实现，可 `code == 0`、`status in 200..299`、看 `success: Boolean` 等任意逻辑 */
    fun success(): Boolean

    /** 失败描述文案 */
    val message: String?

    /** 业务数据；void 接口或失败时可为 null */
    val result: T?

    /**
     * 失败时塞给 [ApiException.errorCode] 的字符串标识，用于 [ApiErrorHandler.onCode] 精确路由。
     * 业务后端如果是 Int / 复合 code 自己 toString 或拼接；不需要 errorCode 维度时返回空串即可（默认）。
     */
    val errorCode: String get() = ""
}

/**
 * 字段名为 `code / message / result`（String code）的标准实现。
 * 想用框架默认约定时 ApiService 返回本类；自定义字段名 / 类型 / 序列化库请自行实现 [HttpResult]。
 *
 * 不写 `@SerializedName` 等具体注解 —— Gson / Moshi / kotlinx.serialization 在字段名匹配时
 * 都按字段名默认解析，本类对各序列化库都通用。
 */
data class DefaultHttpResult<T>(
    val code: String? = null,
    override val message: String? = null,
    override val result: T? = null,
) : HttpResult<T> {
    override fun success(): Boolean = code == successCode
    override val errorCode: String get() = code.orEmpty()

    companion object {
        /**
         * [DefaultHttpResult] 的成功码（默认 `"0"`）。后端约定不同（如 `"200"`）时 App 启动时设置一次：
         * ```
         * DefaultHttpResult.successCode = "200"
         * ```
         * 业务自定义实现 [HttpResult] 时按自己的语义 override [success]，不读这个变量。
         */
        @Volatile
        var successCode: String = "0"
    }
}
