package com.chat.spanutil.span

import androidx.annotation.DrawableRes

/**
 * 全局 emoji 注册表。token → 资源(Int @DrawableRes 或 String URL)。
 *
 * 业务侧在 Application.onCreate 里一次性注册整张表,后续 SpanBuilder
 * 通过 `:smile:` / `[smile]` 这类 token 自动替换为对应小图。
 *
 * 默认匹配两种 token 形式:
 * - `:tokenName:`  例如 `:smile:`
 * - `[tokenName]`  例如 `[heart]`
 *
 * token 支持的字符: a-z A-Z 0-9 _ + - 及中文(`一-龥`),
 * 这样既兼容英文 emoji shortcode,也允许 `[爱心]` 这种本地化标签。
 */
object EmojiRegistry {

    private val table = HashMap<String, Any>()

    val DEFAULT_PATTERN: Regex = Regex(
        ":[A-Za-z0-9_+\\-\\u4e00-\\u9fa5]+:|\\[[A-Za-z0-9_+\\-\\u4e00-\\u9fa5]+\\]"
    )

    fun register(token: String, @DrawableRes resId: Int) {
        table[token] = resId
    }

    fun register(token: String, url: String) {
        table[token] = url
    }

    fun registerAll(map: Map<String, Any>) {
        map.forEach { (k, v) ->
            require(v is Int || v is String) {
                "EmojiRegistry value must be @DrawableRes Int or String url, got ${v::class.java}"
            }
            table[k] = v
        }
    }

    fun unregister(token: String) {
        table.remove(token)
    }

    fun clear() = table.clear()

    /** 返回 Int(resId) / String(url) / null。 */
    fun resolve(token: String): Any? = table[token]
}
