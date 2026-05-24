package com.chat.pagingutil

import kotlin.reflect.KProperty1

/**
 * 字段级 diff
 * 用 `LinkedHashSet` 保留传参顺序，方便 Adapter 侧按字段顺序遍历 binding 逻辑。
 * ### 用法
 * Diff 侧：
 * ```
 * class ConvDiff : DiffUtil.ItemCallback<ConvItem>() {
 *     override fun areItemsTheSame(a: ConvItem, b: ConvItem) = a.id == b.id
 *     override fun areContentsTheSame(a: ConvItem, b: ConvItem) = a == b
 *     override fun getChangePayload(a: ConvItem, b: ConvItem) =
 *         diffChangedFields(
 *             a, b,
 *             ConvItem::unread,
 *             ConvItem::lastMessage,
 *             ConvItem::avatar,
 *             ConvItem::nickname,
 *         ).takeIf { it.isNotEmpty() }
 * }
 * ```
 *
 * Adapter 侧接收：
 * ```
 * override fun onBindViewHolder(holder: VH, pos: Int, payloads: List<Any>) {
 *     if (payloads.isEmpty()) { onBindViewHolder(holder, pos); return }
 *     val item = getItem(pos)
 *     // 同一帧多次 notify 时 payload 会被合并成多个 Set，这里 flatten 合并
 *     val changes = payloads.flatMap { it as Set<*> }.toSet()
 *     if (ConvItem::unread      in changes) bindUnread(holder, item)
 *     if (ConvItem::lastMessage in changes) bindLastMsg(holder, item)
 *     if (ConvItem::avatar      in changes) bindAvatar(holder, item)
 *     if (ConvItem::nickname    in changes) bindNickname(holder, item)
 * }
 * ```
 * getChangePayload 返回的是 Set<KProperty1<T, *>>。如果同一个 position
 * 在同一帧被 notifyItemChanged 多次（不同事件源），RecyclerView
 * 会把多次的 payload 打包成 List<Any> 一起送到 onBindViewHolder。
 * 所以接收端要写：
 * val changes = payloads.flatMap { it as Set<*> }.toSet()
 *
 */
fun <T : Any> diffChangedFields(
    old: T,
    new: T,
    vararg props: KProperty1<T, *>,
): Set<KProperty1<T, *>> {
    val changed = LinkedHashSet<KProperty1<T, *>>(props.size)
    for (p in props) {
        if (p.get(old) != p.get(new)) changed += p
    }
    return changed
}
