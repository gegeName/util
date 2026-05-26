package com.chat.mylibrary.http

import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.lifecycleScope

/**
 * Activity 组件基类：把 Activity 里某一块 UI + 业务从 Activity 中拆出来，
 * 让 Activity 只剩"组装 component"这一件事。业务逻辑放 ViewModel，UI 逻辑放 Component。
 *
 * ─────────────────────────── 设计要点 ───────────────────────────
 *
 * 1. 持有 activity 与 binding 引用，子类可直接操作 view（无需通过接口回调到 Activity）
 * 2. 自动注册到 [activity.lifecycle]，按需重写 onCreate/onStart/onResume/onPause/onStop/onDestroy
 * 3. [scope] = activity.lifecycleScope，随 Activity 销毁自动取消
 * 4. Activity 不像 Fragment 有"视图重建"问题，所以只有一个 lifecycle，不存在"绑视图还是绑实例"的选择
 *
 * ─────────────────────────── 使用案例 1：单个 Component ───────────────────────────
 *
 * Component:
 * ```
 * class PartyRankComponent(
 *     activity: PartyRoomActivity,
 *     binding: ActivityPartyRoomBinding,
 *     private val viewModel: PartyRankVM,
 * ) : ActivityComponent<PartyRoomActivity, ActivityPartyRoomBinding>(activity, binding) {
 *
 *     private val adapter = RankAdapter()
 *
 *     override fun onCreate(owner: LifecycleOwner) {
 *         binding.rvRank.adapter = adapter
 *         binding.btnRefresh.setOnClickListener { viewModel.refresh() }
 *
 *         scope.launch {
 *             viewModel.rankFlow.collect { adapter.submitList(it) }
 *         }
 *
 *         viewModel.loadInitial()
 *     }
 *
 *     override fun onResume(owner: LifecycleOwner) { viewModel.startAutoRefresh() }
 *     override fun onPause(owner: LifecycleOwner)  { viewModel.stopAutoRefresh() }
 * }
 * ```
 *
 * Activity 接入：
 * ```
 * class PartyRoomActivity : RxActivity() {
 *     private lateinit var binding: ActivityPartyRoomBinding
 *     private val rankVM by getViewModel<PartyRankVM>()
 *
 *     override fun initView() {
 *         PartyRankComponent(this, binding, rankVM)
 *         // 不需要存引用，addObserver 之后 lifecycle 会持有它
 *     }
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 2：多 Component 组装（复杂页面）───────────────────────────
 *
 * 一个 Activity 里多块独立 UI（如派对房间的麦位/排行榜/礼物/聊天/PK），每块拆一个 Component：
 * ```
 * class PartyRoomActivity : RxActivity() {
 *     private lateinit var binding: ActivityPartyRoomBinding
 *     private val rankVM by getViewModel<PartyRankVM>()
 *     private val giftVM by getViewModel<GiftVM>()
 *     private val micVM  by getViewModel<MicVM>()
 *     private val chatVM by getViewModel<ChatVM>()
 *
 *     override fun initView() {
 *         PartyRankComponent(this, binding, rankVM)
 *         PartyGiftComponent(this, binding, giftVM)
 *         PartyMicComponent(this, binding, micVM)
 *         PartyChatComponent(this, binding, chatVM)
 *         // 加新功能 = 加一行，Activity 不会膨胀
 *     }
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 3：跨 Component 通信（走 ViewModel 共享状态）───────────────────────────
 *
 * 错误做法：A.component 持有 B.component 的引用，互相调用 → 紧耦合、循环依赖。
 *
 * 推荐做法：把通信媒介放在共享 ViewModel 里（同一 Activity 作用域的 VM 自然共享）。
 * 例：礼物组件收到送礼后通知排行榜刷新：
 * ```
 * // 共享 VM
 * class PartyShareVM : ViewModel() {
 *     private val _giftSent = MutableSharedFlow<GiftEvent>()
 *     val giftSent: SharedFlow<GiftEvent> = _giftSent
 *     suspend fun emitGiftSent(e: GiftEvent) = _giftSent.emit(e)
 * }
 *
 * // GiftComponent 里
 * scope.launch { shareVM.emitGiftSent(event) }
 *
 * // RankComponent 里
 * scope.launch {
 *     shareVM.giftSent.collect { viewModel.refresh() }
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 4：需要外部触发 Component 行为 ───────────────────────────
 *
 * 有时 Activity 收到外部事件（如 onNewIntent、onActivityResult、外部 ARouter 跳转）需要让 component 响应。
 * 暴露 public 方法即可，不要硬塞进生命周期回调：
 * ```
 * class PartyRankComponent(...) : ActivityComponent<...>(activity, binding) {
 *     fun onUserBlocked(userId: String) {
 *         viewModel.removeFromRank(userId)
 *     }
 * }
 *
 * class PartyRoomActivity : RxActivity() {
 *     private val rank = PartyRankComponent(this, binding, rankVM)   // 这种场景才需要持引用
 *
 *     override fun initView() { rank }
 *     override fun onNewIntent(intent: Intent) {
 *         super.onNewIntent(intent)
 *         intent.getStringExtra("blockedUid")?.let { rank.onUserBlocked(it) }
 *     }
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 5：横竖屏 / 配置变更 ───────────────────────────
 *
 * Activity 重建会触发新的 onCreate → 新 binding → new component 一遍，旧 component 随旧 Activity GC。
 * 不需要做任何额外处理，只要保证 component 内部不持有"应该跨重建保留的状态"——这种状态本来就该放
 * ViewModel（VM 默认在配置变更时存活）。
 *
 * ─────────────────────────── 注意事项 ───────────────────────────
 *
 * 1. **Component 不要持有 Activity 之外的 Context**
 *    构造时传进来的 activity 已经够用。需要 ApplicationContext 时直接 `activity.applicationContext`。
 *    不要把 Activity 引用塞到任何静态变量/单例里——会跟着 Activity 一起泄漏到下次启动。
 *
 * 2. **跨 Component 通信走 ViewModel 共享状态，不要互相持有引用**
 *    Activity 拿 `getViewModel<SharedVM>()` 时，传同一个 ViewModelStoreOwner（默认 this）即可拿到同实例。
 *    详见使用案例 3。
 *
 * 3. **全局监听必须在 onDestroy 解注册**
 *    LiveEventBus / PartyRoomDataManager / 网络回调 / Agora SDK 等注册了全局 listener 的，
 *    在 [onDestroy] 解掉。优先方案：让这些数据源用 Flow 暴露状态，Component 用 [scope] collect，
 *    scope 自动取消，无需手动解注册。
 *
 * 4. **重操作放 ViewModel，Component 只做"UI 翻译"**
 *    网络请求、定时器、IM 长连接 → ViewModel；Component 只负责 binding 操作 + collect VM 状态 +
 *    把 UI 事件转发给 VM。一个 Component 不应该出现 `httpService.xxx().subscribe { ... }` 这种代码。
 *
 * 5. **Activity 持不持引用看实际需要**
 *    - 不需要从外部触发 → `PartyRankComponent(this, binding, vm)` 直接 new，不存引用
 *      （lifecycle 内部会持有 observer，不会被 GC）
 *    - 需要外部调用其方法（见案例 4）→ 存成成员变量
 *
 * 6. **构造函数里别做重活**
 *    构造里只做赋值；初始化 UI、订阅状态都放 [onCreate]。
 *    原因：addObserver 的瞬间生命周期回调还没补发，binding 不一定 ready，且 onCreate 之外的回调（如
 *    onStop）你也碰不到。
 *
 * 7. **finish() / startActivity() / ARouter 跳转：技术上完全可以在 Component 里调，分情况选**
 *    持有 activity 引用，直接调跟在 Activity 里调没区别——不崩、不泄漏、无额外开销。
 *
 *    直接在 Component 里调（推荐）：
 *    - Component 内部行为触发的明确跳转：点关闭按钮 → activity.finish()；点条目 → 跳详情页
 *    - 跳转参数完全来自当前 Component 自己的状态
 *
 *    抽事件让 Activity 处理（更清爽）：
 *    - 跳转参数需要**多个 Component 的状态**拼装（否则 Component 之间要互相持有引用）
 *    - 同一个 Component 在**多个 Activity 复用**，但跳转目标各页面不同
 *    - 跳转后需要 onActivityResult 回到调用方处理（result 只能在 Activity 接）
 *    示例：
 *    ```
 *    // Component 暴露事件
 *    private val _nav = MutableSharedFlow<NavEvent>()
 *    val nav: SharedFlow<NavEvent> = _nav
 *    fun onItemClick(id: String) = scope.launch { _nav.emit(NavEvent.Detail(id)) }
 *
 *    // Activity 收口路由
 *    scope.launch { rank.nav.collect { when (it) { is Detail -> ARouter... } } }
 *    ```
 */
abstract class ActivityComponent<A : AppCompatActivity, B : ViewDataBinding>(
    protected val activity: A,
    protected val binding: B
) : DefaultLifecycleObserver {

    init {
        activity.lifecycle.addObserver(this)
    }

    protected val scope: LifecycleCoroutineScope
        get() = activity.lifecycleScope
}
