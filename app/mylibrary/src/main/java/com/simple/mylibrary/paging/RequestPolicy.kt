package com.simple.mylibrary.paging

/**
 * 乐观更新的请求调度策略。
 *
 * 通过 [PagingController] 的 optimistic* 系列方法的 policy 参数指定。
 */
sealed class RequestPolicy {

    /** 默认：每次都立即执行，不做限制 */
    object None : RequestPolicy()

    /**
     * 节流：同一 key 在 [intervalMs] 内的后续请求被丢弃（leading edge）。
     * 适合"防连点"场景：第一次立即生效，窗口内的连点静默忽略。
     */
    data class Throttle(val intervalMs: Long) : RequestPolicy()

    /**
     * 取消旧的：同一 key 的新请求会取消正在进行的旧请求。
     * 适合搜索建议等参数频繁变化的场景。
     */
    object Latest : RequestPolicy()

    /**
     * 串行：同一 key 的请求按调用顺序逐个执行，避免竞态。
     * 适合计数器、评分等顺序敏感的写入。
     */
    object Serial : RequestPolicy()
}
