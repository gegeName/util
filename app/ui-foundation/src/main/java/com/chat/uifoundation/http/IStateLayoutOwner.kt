package com.chat.uifoundation.http

import com.chat.statelayout.StateLayout

/**
 * 页面声明"我有一个 StateLayout"。配合 `autoLoadingViewModel` 委托使用，
 * 当 VM 实现 [com.common.network.base.PageStateOwner] 时，框架在 lifecycle ≥ STARTED 时
 * 自动调用 [stateLayout].bindPageState(vm.pageState, ...)，业务无需手写 bind。
 *
 * 实现要求：
 * - Activity：在 `initView`/`setContentView` 之后赋值 [stateLayout]，确保 `onStart` 时可读
 * - Fragment：在 `initView`/`onViewCreated` 之后赋值 [stateLayout]，view 重建时自动 re-bind
 * - 同页多个 StateLayout：本接口只暴露一个"主 StateLayout"。其他用 `bindPageState` 手动接通。
 */
interface IStateLayoutOwner {
    val stateLayout: StateLayout
}
