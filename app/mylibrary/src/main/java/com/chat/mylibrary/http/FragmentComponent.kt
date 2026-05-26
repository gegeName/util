package com.chat.mylibrary.http

import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope

/**
 * Fragment 组件基类：把 Fragment 里某一块 UI + 业务从 Fragment 中拆出来，
 * 让 Fragment 只剩"组装 component"这一件事。业务逻辑放 ViewModel，UI 逻辑放 Component。
 *
 * ─────────────────────────── 关键差异（vs ActivityComponent）───────────────────────────
 *
 * 1. 绑定 [Fragment.getViewLifecycleOwner] 而非 fragment.lifecycle
 *    Fragment 有两个 lifecycle：fragment 自身、view（onCreateView ↔ onDestroyView）。
 *    Component 持有 binding 引用，必须跟 view 同生共死，否则视图重建后会持有旧 binding。
 *
 * 2. [scope] 随 onDestroyView 自动取消
 *    回调里访问 binding 不会 NPE；视图重建后旧 component 被 GC，新 component 重新绑定。
 *
 * 3. 视图重建时（DialogFragment 切横竖屏、navigate back 等）会重新 new 一次
 *    所以 component 实例不要存"页面级长生命周期数据"，那种东西放 ViewModel。
 *
 * ─────────────────────────── 可见性回调 onVisible / onInvisible ───────────────────────────
 *
 * 适用场景：ViewPager2、show/hide、navigate 切换等"view 在但用户看不到"的情况。
 * 这两个回调**不会自动触发**，必须由宿主 Fragment 在恰当时机调用 [dispatchVisibility]。
 * 内部已做去重 + 视图状态校验：
 * - true → false → true 才会再次触发
 * - view 已销毁时调用会被忽略
 *
 * ─────────────────────────── 使用案例 1：普通 Fragment（单页）───────────────────────────
 *
 * Component:
 * ```
 * class AllPartyListComponent(
 *     fragment: AllPartyFragment,
 *     binding: FragmentAllPartyBinding,
 *     private val viewModel: AllPartyVM,
 * ) : FragmentComponent<AllPartyFragment, FragmentAllPartyBinding>(fragment, binding) {
 *
 *     private val adapter = AllPartyAdapter()
 *
 *     override fun onCreate(owner: LifecycleOwner) {
 *         binding.rvList.adapter = adapter
 *         binding.refreshLayout.setOnRefreshListener { viewModel.refresh() }
 *
 *         scope.launch {
 *             viewModel.languageList.collect { adapter.submitList(it) }
 *         }
 *     }
 * }
 * ```
 *
 * 宿主 Fragment：
 * ```
 * class AllPartyFragment : BaseFragment<FragmentAllPartyBinding>() {
 *     private val viewModel by autoLoadingViewModel<AllPartyVM>()
 *
 *     override fun initView(view: View) {
 *         AllPartyListComponent(this, binding, viewModel)
 *         AllPartyBannerComponent(this, binding, viewModel)
 *         // 视图重建时 initView 会再跑一次 → component 自动重建，无需手动管
 *     }
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 2：ViewPager2 中的 Fragment（可见性感知）───────────────────────────
 *
 * 痛点：ViewPager2 的相邻页面也会走 onCreateView/onResume（offscreenPageLimit 范围内），
 * 单纯 onResume 不能代表"用户在看这一页"。FragmentStateAdapter 默认用 setMaxLifecycle
 * 把不可见页压到 STARTED，**只有当前页是 RESUMED**。利用这个特性派发可见性即可。
 *
 * Component（带 onVisible/onInvisible）：
 * ```
 * class AllPartyListComponent(
 *     fragment: AllPartyFragment,
 *     binding: FragmentAllPartyBinding,
 *     private val viewModel: AllPartyVM,
 * ) : FragmentComponent<AllPartyFragment, FragmentAllPartyBinding>(fragment, binding) {
 *
 *     override fun onCreate(owner: LifecycleOwner) {
 *         binding.rvList.adapter = AllPartyAdapter()
 *         scope.launch { viewModel.languageList.collect { ... } }
 *     }
 *
 *     override fun onVisible() {
 *         // 用户切到这一页 → 拉新数据 + 启动埋点
 *         viewModel.refresh()
 *         viewModel.startTrackingExposure()
 *     }
 *
 *     override fun onInvisible() {
 *         // 用户切走 → 停掉轮询/埋点，节省资源
 *         viewModel.stopTrackingExposure()
 *     }
 * }
 * ```
 *
 * 宿主 Fragment：
 * ```
 * class AllPartyFragment : LazyFragment<FragmentAllPartyBinding>() {
 *     private val viewModel by autoLoadingViewModel<AllPartyVM>()
 *     private lateinit var listComponent: AllPartyListComponent
 *
 *     override fun initView(view: View) {
 *         listComponent = AllPartyListComponent(this, binding, viewModel)
 *     }
 *
 *     // ViewPager2 切到当前页 → onResume；切走 → onPause（依赖 setMaxLifecycle 默认行为）
 *     override fun onResume() { super.onResume(); listComponent.dispatchVisibility(true) }
 *     override fun onPause()  { super.onPause();  listComponent.dispatchVisibility(false) }
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 3：show/hide 切换的 Fragment ───────────────────────────
 *
 * `FragmentTransaction.show/hide` 不会触发 onResume/onPause，需要 hook [Fragment.onHiddenChanged]：
 * ```
 * override fun onHiddenChanged(hidden: Boolean) {
 *     super.onHiddenChanged(hidden)
 *     listComponent.dispatchVisibility(!hidden)
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 4：多 Component 集中分发可见性 ───────────────────────────
 *
 * 一个 Fragment 里多个 Component 都要感知可见性时，统一收口避免每加一个就要改 onResume：
 * ```
 * class AllPartyFragment : LazyFragment<FragmentAllPartyBinding>() {
 *     private val components = mutableListOf<FragmentComponent<*, *>>()
 *
 *     override fun initView(view: View) {
 *         components += AllPartyListComponent(this, binding, viewModel)
 *         components += AllPartyBannerComponent(this, binding, viewModel)
 *     }
 *
 *     override fun onResume() {
 *         super.onResume()
 *         components.forEach { it.dispatchVisibility(true) }
 *     }
 *     override fun onPause() {
 *         super.onPause()
 *         components.forEach { it.dispatchVisibility(false) }
 *     }
 * }
 * ```
 *
 * ─────────────────────────── 使用案例 5：LazyFragment 时机说明 ───────────────────────────
 *
 * `LazyFragment.initView()` 在首次 onResume 才被调用。此时 viewLifecycleOwner 已 STARTED，
 * addObserver 时 [DefaultLifecycleObserver.onCreate] 会立即补发到当前状态，所以 onCreate
 * → onStart → onResume 都能正常拿到。视图重建后 LazyFragment 会再次走 initView，
 * Component 重建一次，符合预期。
 *
 * ─────────────────────────── 注意事项 ───────────────────────────
 *
 * 1. **不要把 Component 实例存在 fragment 的成员且跨视图重建复用**
 *    每次 onCreateView 后都应 new 一个新的（在 initView 里 new 即可）。原因：旧 component 持
 *    有的 binding 是已销毁的 view，绑定的也是旧 viewLifecycleOwner，复用会拿到失效引用。
 *
 * 2. **不要在 Component 里持有任何全局/单例的回调引用而忘记解注册**
 *    如果非要注册全局监听（如 LiveEventBus、PartyRoomDataManager 的某个 listener），
 *    在 [onDestroy] 里解注册；否则会跟着 Fragment view 一起泄漏到下次创建。
 *    优先方案：把这些数据用 Flow/StateFlow 暴露在 ViewModel，Component 用 [scope] collect。
 *
 * 3. **不要用 fragment.lifecycleScope，要用本类提供的 [scope]（= viewLifecycleScope）**
 *    fragment.lifecycleScope 跨视图重建仍然存活，回调里访问 binding 会触发 NPE。
 *
 * 4. **不要在 Component 里做"取后立即用"的 ViewModel 初始化**
 *    onCreate 里直接调用 `viewModel.someMethod()` 没问题；但若依赖 LazyFragment 的 loadData()
 *    时机，应该让 fragment 主动调 component 的方法触发，而不是 component 自己调。
 *
 * 5. **跨 Component 通信走 ViewModel 共享状态**
 *    A 组件触发了一个事件，B 组件要响应：在 ViewModel 里建一个 SharedFlow，A 调 emit、
 *    B 在 onCreate 里 collect。不要互相持有 component 引用。
 *
 * 6. **DialogFragment 一般不推荐用 Component 拆分**
 *    DialogFragment 自身定位就是"一块独立 UI"，再拆 component 通常过度设计。除非 dialog 体量
 *    很大（多 Tab、多状态），否则直接写就行。
 */
abstract class FragmentComponent<F : Fragment, B : ViewDataBinding>(
    protected val fragment: F,
    protected val binding: B
) : DefaultLifecycleObserver {

    init {
        fragment.viewLifecycleOwner.lifecycle.addObserver(this)
    }

    protected val scope: LifecycleCoroutineScope
        get() = fragment.viewLifecycleOwner.lifecycleScope

    protected val activity: FragmentActivity
        get() = fragment.requireActivity()

    private var lastVisible = false

    /**
     * 由宿主 Fragment 在可见性变化时调用。已做去重 + 视图状态校验：
     * - 状态相同时不重复触发
     * - viewLifecycleOwner 未到 CREATED（视图已销毁或还没创建）时忽略
     */
    fun dispatchVisibility(visible: Boolean) {
        if (visible == lastVisible) return
        if (!fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) return
        lastVisible = visible
        if (visible) onVisible() else onInvisible()
    }

    /** 用户可见时回调（ViewPager2 切到当前页 / hidden=false / show()）。需宿主调用 [dispatchVisibility] */
    protected open fun onVisible() {}

    /** 用户不可见时回调。需宿主调用 [dispatchVisibility] */
    protected open fun onInvisible() {}

    override fun onDestroy(owner: LifecycleOwner) {
        lastVisible = false
    }
}
