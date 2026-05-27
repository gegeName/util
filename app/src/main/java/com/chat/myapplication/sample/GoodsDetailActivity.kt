package com.chat.myapplication

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chat.pagingutil.CommonLoadStateAdapter
import com.chat.pagingutil.SwipeRefreshAdapter
import com.chat.rv_page.BaseListAdapter
import com.chat.rv_page.MultiListSectionHandle
import com.chat.rv_page.PagingSectionHandle
import com.chat.rv_page.RvPage
import com.chat.rv_page.RvPageController
import com.chat.rv_page.SingleSectionHandle
import com.chat.myapplication.databinding.ActivityGoodsDetailBinding
import com.chat.myapplication.databinding.ItemGdBannerBinding
import com.chat.myapplication.databinding.ItemGdBannerImageBinding
import com.chat.myapplication.databinding.ItemGdCommentBinding
import com.chat.myapplication.databinding.ItemGdDetailImageBinding
import com.chat.myapplication.databinding.ItemGdDetailTextBinding
import com.chat.myapplication.databinding.ItemGdDetailVideoBinding
import com.chat.myapplication.databinding.ItemGdHeaderBinding
import com.chat.myapplication.databinding.ItemGdRecGoodsBinding
import com.chat.myapplication.databinding.ItemGdRecListBinding
import com.chat.myapplication.databinding.ItemGdSectionTitleBinding
import com.chat.myapplication.databinding.ItemGdSpecBinding
import com.chat.myapplication.sample.COMMENT_DIFF
import com.chat.myapplication.sample.Comment
import com.chat.myapplication.sample.DETAIL_BLOCK_DIFF
import com.chat.myapplication.sample.DetailBlock
import com.chat.myapplication.sample.GoodsDetailViewModel
import com.chat.myapplication.sample.GoodsHeader
import com.chat.myapplication.sample.GoodsSpec
import com.chat.myapplication.sample.REC_GOODS_DIFF
import com.chat.myapplication.sample.RecGoods
import com.chat.rv_page.CarouselSectionHandle
import com.chat.rv_page.CarouselSnap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * RvPage 复杂界面演示：商品详情页（**无 tag 写法**）。
 *
 * 每个 section 声明时返回一个**强类型句柄**（[SingleSectionHandle] / [MultiListSectionHandle] /
 * [PagingSectionHandle]）；业务把句柄存到 Activity / Fragment 字段，喂数据时直接
 * `bannerSection.submit(data)` —— 不再有 tag 字符串重复、不会拼错、IDE 自动补全。
 */
class GoodsDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGoodsDetailBinding
    private val vm: GoodsDetailViewModel by viewModels()
    private lateinit var page: RvPageController

    // ── Section 句柄：每个 section 在 sections {} 内声明时赋值 ──
    private lateinit var bannerSection: SingleSectionHandle<List<Int>>
    private lateinit var headerSection: SingleSectionHandle<GoodsHeader>
    private lateinit var specSection: SingleSectionHandle<GoodsSpec>
    private lateinit var detailsSection: MultiListSectionHandle<DetailBlock>
    private lateinit var commentTitleSection: SingleSectionHandle<String>
    private lateinit var commentsSection: PagingSectionHandle<Comment>
    private lateinit var recSection: CarouselSectionHandle<RecGoods>

    // ── 嵌套子 Adapter（Banner / 推荐 的横向 RV）──
    private val bannerImageAdapter = object : BaseListAdapter<Int, ItemGdBannerImageBinding>(
        COLOR_DIFF, ItemGdBannerImageBinding::inflate
    ) {
        override fun onBind(binding: ItemGdBannerImageBinding, item: Int, position: Int) {
            binding.vBannerImage.setBackgroundColor(item)
        }
    }
    private val recImageAdapter = object : BaseListAdapter<RecGoods, ItemGdRecGoodsBinding>(
        REC_GOODS_DIFF, ItemGdRecGoodsBinding::inflate
    ) {
        override fun onBind(binding: ItemGdRecGoodsBinding, item: RecGoods, position: Int) {
            binding.vCover.setBackgroundColor(item.coverColor)
            binding.tvName.text = item.name
            binding.tvPrice.text = item.priceText
        }
    }.also {
        it.setOnItemClickListener(throttleMs = 600) { _, item, _ ->
            vm.toggleRecLike(item.id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGoodsDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        buildPage()
        observeData()
    }

    // ─────────────────────────────────────────────────────────────
    // 1) RvPage DSL 声明 + 把 section 句柄存到 Activity 字段
    // ─────────────────────────────────────────────────────────────
    private fun buildPage() {
        page = RvPage.with(this)
            .recyclerView(binding.rv)
            .layoutManager(LinearLayoutManager(this))
            .awaitStaticBeforePaging(200)
            .refreshAdapter(SwipeRefreshAdapter(binding.swipeRefresh))
            .pageState(binding.pageState)
            .onHeaderRefresh { vm.loadAll().join() }
            .hideAuxOnPageState(false)
            .sections {

                // ① Banner（嵌套横向 RV;保留 single 写法是为了顶上还有 "1/N" 文案）
                bannerSection = single<List<Int>, ItemGdBannerBinding>(ItemGdBannerBinding::inflate) {
                    onViewHolderCreated { _, b ->
                        b.rvBanner.layoutManager =
                            LinearLayoutManager(this@GoodsDetailActivity, RecyclerView.HORIZONTAL, false)
                        b.rvBanner.adapter = bannerImageAdapter
                    }
                    onBind { b, colors ->
                        bannerImageAdapter.submit(colors)
                        b.tvBannerHint.text = "1 / ${colors.size}"
                    }
                    // 单格曝光埋点:banner 至少 30% 露出时触发一次
                    onVisibilityChanged(thresholdPercent = 30) { _, visible ->
                        Log.d("GoodsDetail", "Banner visible=$visible")
                    }
                }

                // ② 标题 + 价格
                headerSection = single<GoodsHeader, ItemGdHeaderBinding>(ItemGdHeaderBinding::inflate) {
                    onBind { b, h ->
                        b.tvName.text = h.name
                        b.tvPrice.text = h.priceText
                        b.tvOriginalPrice.text = h.originalPriceText
                        b.tvOriginalPrice.paintFlags =
                            b.tvOriginalPrice.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                        b.tvSales.text = "已售 ${h.sales}"
                    }
                }

                // ③ 规格选择
                specSection = single<GoodsSpec, ItemGdSpecBinding>(ItemGdSpecBinding::inflate) {
                    onBind { b, s ->
                        b.tvSelected.text = s.selectedLabel
                        b.tvDelivery.text = s.deliveryDays
                    }
                    onItemClick(throttleMs = 600) { _, _ ->
                        toast("点击规格 → 打开规格选择弹窗")
                    }
                }

                // ④ 章节标题"商品详情"（静态文案 data 直接给）
                single<String, ItemGdSectionTitleBinding>(ItemGdSectionTitleBinding::inflate) {
                    data = "商品详情"
                    onBind { b, t -> b.tvTitle.text = t }
                }

                // ⑤ 详情多类型区块
                detailsSection = multiList<DetailBlock>(diff = DETAIL_BLOCK_DIFF) {
                    addType(
                        typeValue = DetailBlock.TYPE_TEXT,
                        inflate = ItemGdDetailTextBinding::inflate
                    ) { b, item, _ -> b.tvText.text = item.text }
                    addType(
                        typeValue = DetailBlock.TYPE_IMAGE,
                        inflate = ItemGdDetailImageBinding::inflate
                    ) { b, item, _ -> b.vImage.setBackgroundColor(item.imageColor) }
                    addType(
                        typeValue = DetailBlock.TYPE_VIDEO,
                        inflate = ItemGdDetailVideoBinding::inflate
                    ) { b, item, _ -> b.vCover.setBackgroundColor(item.videoColor) }
                    // 假如外层是 GridLayoutManager(this, 3),想让视频块跨整 3 列、其它占 1 列:
                    // spanSize { item, _, total -> if (item.itemType == DetailBlock.TYPE_VIDEO) total else 1 }
                }

                // ⑥ 章节标题"用户评价"（标题文案动态从 vm.commentCount 来）
                commentTitleSection = single<String, ItemGdSectionTitleBinding>(
                    ItemGdSectionTitleBinding::inflate
                ) {
                    onBind { b, t -> b.tvTitle.text = t }
                }

                // ⑦ 评论分页 —— Comment 是 data class,不传 diff,框架按 keyOf + equals 自动合成
                commentsSection = pagingList<Comment, ItemGdCommentBinding>(
                    ItemGdCommentBinding::inflate
                ) {
                    flow = vm.commentFlow
                    patcher(vm.commentPatcher)
                    onBind { b, c, _ ->
                        b.vAvatar.setBackgroundColor(c.avatarColor)
                        b.tvName.text = c.userName
                        b.tvContent.text = c.content
                        b.tvDate.text = c.date
                        b.btnLike.text = "👍 ${c.likeCount}"
                    }
                    childClickIds(R.id.btnLike)
                    onItemChildClick(throttleMs = 600) { _, item, _ ->
                        // 通过 handle 直接拿 paging controller，无需 tag
                        commentsSection.controller?.optimisticUpdate(
                            key = item.id,
                            transform = { it.copy(likeCount = it.likeCount + 1) },
                            request = { delay(400) },
                            onSuccess = { toast("点赞成功 ❤") },
                            onFailure = { toast("点赞失败：${it.message}") }
                        )
                    }
                    loadStateFooter { CommonLoadStateAdapter(onRetry = it) }
                    onLoadError { toast("评论加载失败：${it.message}") }
                    // 评论曝光埋点:每条评论 >= 50% 露出时上报一次
                    onItemVisibilityChanged(thresholdPercent = 50) { item, _, visible ->
                        if (visible) Log.d("GoodsDetail", "曝光评论 id=${item.id}")
                    }
                }

                // ⑧ 章节标题"相关推荐"
                single<String, ItemGdSectionTitleBinding>(ItemGdSectionTitleBinding::inflate) {
                    data = "相关推荐"
                    onBind { b, t -> b.tvTitle.text = t }
                }

                // ⑨ 推荐 —— 用 carousel DSL,不用再手写横向 RV / Adapter
                //         RecGoods 是 data class,这里只声明 keyOf,框架自动合成 diff
                recSection = carousel<RecGoods, ItemGdRecGoodsBinding>(
                    ItemGdRecGoodsBinding::inflate
                ) {
                    keyOf { it.id }
                    paddingStartDp = 12
                    paddingEndDp = 12
                    itemSpacingDp = 8
                    sharedPool= RecyclerView.RecycledViewPool()
                    onBind { b, item, _ ->
                        b.vCover.setBackgroundColor(item.coverColor)
                        b.tvName.text = item.name
                        b.tvPrice.text = item.priceText
                    }
                    onItemClick(throttleMs = 600, keyOf = { it.name }) { _, item, _ ->
                        toast("点击推荐:${item.name}")
                    }
                    // carousel 整行曝光埋点(进入屏幕一半以上触发一次)
                    onVisibilityChanged(thresholdPercent = 50) { visible ->
                        Log.d("GoodsDetail", "推荐区块 visible=$visible")
                    }
                }

            }
            .start()
    }

    // ─────────────────────────────────────────────────────────────
    // 2) 订阅 VM 的 StateFlow → 通过 section 句柄 .submit() 喂数据
    //    完全没有 tag 字符串
    // ─────────────────────────────────────────────────────────────
    private fun observeData() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.images.collect { bannerSection.submit(it.takeIf { it.isNotEmpty() }) } }
                launch { vm.header.collect { headerSection.submit(it) } }
                launch { vm.spec.collect { specSection.submit(it) } }
                launch { vm.detailBlocks.collect { detailsSection.submit(it) } }
                launch {
                    vm.commentCount.collect { c ->
                        if (c > 0) commentTitleSection.submit("用户评价 ($c)")
                    }
                }
                launch { vm.recommends.collect { recSection.submit(it) } }
            }
        }
    }

    private fun toast(msg: String) {
        Log.d("GoodsDetail", msg)
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private val COLOR_DIFF = object : DiffUtil.ItemCallback<Int>() {
            override fun areItemsTheSame(old: Int, new: Int) = old == new
            override fun areContentsTheSame(old: Int, new: Int) = old == new
        }
    }
}
