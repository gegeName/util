package com.lhj.effect


import androidx.core.net.toUri

/**
 * 特效资源类型。**可扩展**：内置 [SVGA] / [MP4] / [GIF]，业务可通过继承 [EffectType] 注册自定义类型
 * （例如 WebP、APNG、自研动画格式）。
 *
 * ```kotlin
 * // 1. 定义自定义类型（object 单例 + 指定后缀）
 * object WebpEffectType : EffectType(key = "webp", urlSuffix = "webp")
 *
 * // 2. Application 启动时注册(让 fromUrl 能识别 .webp 后缀)
 * EffectType.register(WebpEffectType)
 *
 * // 3. Factory 加分支
 * override fun create(type: EffectType) = when (type) {
 *     EffectType.SVGA -> svga
 *     WebpEffectType -> webp
 *     ...
 *     else -> throw IllegalStateException("unknown type: $type")
 * }
 *
 * // 4. 业务直接 enqueue,框架按 type 调度
 * EffectManager.enqueue(webpUrl)
 * ```
 *
 * @param key 类型唯一标识，也作为缓存文件扩展名使用（例如 `<md5>.svga`）。
 * @param urlSuffix URL path 的标准后缀，[fromUrl] 据此推断。**null** 表示该类型不能从 URL 推断
 *   (比如纯本地资源 / 业务自己生成的资源),业务必须显式构造 [EffectResource] 入队。
 */
open class EffectType(
    val key: String,
    val urlSuffix: String? = null,
) {

    /**
     * 内置类型。等号比较走 [equals]，基于 [key]；object 单例同样安全。
     */
    object SVGA : EffectType(key = "svga", urlSuffix = "svga")
    object MP4 : EffectType(key = "mp4", urlSuffix = "mp4")
    object GIF : EffectType(key = "gif", urlSuffix = "gif")

    override fun equals(other: Any?): Boolean = other is EffectType && other.key == key
    override fun hashCode(): Int = key.hashCode()
    override fun toString(): String = "EffectType($key)"

    companion object {
        private val registry = linkedMapOf<String, EffectType>()

        init {
            register(SVGA)
            register(MP4)
            register(GIF)
        }

        /**
         * 注册自定义 [EffectType]，使其能被 [fromUrl] 识别。
         *
         * 同 [urlSuffix] 重复注册会覆盖。[urlSuffix] 为 null 的类型不会进入注册表。
         */
        @JvmStatic
        fun register(type: EffectType) {
            val suffix = type.urlSuffix?.lowercase() ?: return
            synchronized(registry) { registry[suffix] = type }
        }

        /**
         * 从 URL 后缀推断资源类型。剥掉 query/fragment，看 path 最后一段的扩展名,
         * 在注册表中查找匹配的 [EffectType]。
         */
        @JvmStatic
        fun fromUrl(url: String): EffectType? {
            if (url.isBlank()) return null
            val path = runCatching { url.toUri().path }.getOrNull().orEmpty()
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return null
            return synchronized(registry) { registry[ext] }
        }
    }
}

/**
 * 队列优先级。
 *
 * - [NORMAL]：入队尾，按 FIFO 播放。
 * - [HIGH]：入队首，等当前播放结束后立即播。不打断当前播放。
 */
enum class EffectPriority {
    NORMAL,
    HIGH,
}

/**
 * 一条待播放的特效资源。
 *
 * @param url 资源远端地址（也是缓存键的输入）
 * @param type 资源类型，决定走哪个 [IEffectPlayer]
 * @param priority 入队位置策略，见 [EffectPriority]
 * @param tag 业务标记，仅用于日志/调试，不参与去重
 * @param persistent 必播标记。true 时该资源在 stage 切换（attach 新 stage / detach 当前 stage /
 *   自动模式 Activity 切换）时**不会被丢弃**，留在内存队列里等下次有 stage 时继续播；
 *   即使当前正在播放也会被重新插回队首在新 stage 上重播。仅 [EffectManager.clear] 会强制清掉。
 *   适合 VIP 入场、高价值礼物、关键提示等"必须看到"的场景。默认 false。
 * @param extras 透传给播放器的额外参数（如音量、循环次数），由业务方与自定义 Player 约定
 */
data class EffectResource(
    val url: String,
    val type: EffectType,
    val priority: EffectPriority = EffectPriority.NORMAL,
    val tag: String? = null,
    val persistent: Boolean = false,
    val extras: Map<String, Any>? = null,
)
