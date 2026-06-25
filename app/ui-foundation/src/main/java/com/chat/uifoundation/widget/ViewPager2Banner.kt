package com.chat.uifoundation.widget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.databinding.ViewDataBinding
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.gzxkwl.common.R
import kotlin.math.abs
import kotlin.math.max

/**
 * 基于 ViewPager2 的通用 Banner。
 */
class ViewPager2Banner<T> @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val viewPager = ViewPager2(context)
    private val indicatorLayout = LinearLayout(context)
    private val bannerAdapter = BannerAdapter<T>()
    private var pageAnimator: ValueAnimator? = null
    private val autoPlayAction = object : Runnable {
        override fun run() {
            if (!isAutoPlaying || isUserTouching || bannerAdapter.realCount <= 1) return
            setCurrentItem(viewPager.currentItem + 1, true)
            postDelayed(this, intervalMillis)
        }
    }
    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) {
            updateIndicator(position)
        }
    }
    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            resumeAutoPlay()
        }

        override fun onStop(owner: LifecycleOwner) {
            pauseAutoPlay()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            unbindLifecycle()
        }
    }

    private var intervalMillis = DEFAULT_INTERVAL_MILLIS
    private var scrollDurationMillis = DEFAULT_SCROLL_DURATION_MILLIS
    private var scrollInterpolator: Interpolator = DecelerateInterpolator()
    private var isAutoPlaying = false
    private var autoPlayEnabled = false
    private var isAttached = false
    private var lifecycleOwner: LifecycleOwner? = null
    private var indicatorNormalColor = DEFAULT_INDICATOR_NORMAL_COLOR
    private var indicatorSelectedColor = DEFAULT_INDICATOR_SELECTED_COLOR
    private var indicatorNormalDrawable: Drawable? = null
    private var indicatorSelectedDrawable: Drawable? = null
    private var indicatorSize = dpToPx(DEFAULT_INDICATOR_SIZE_DP)
    private var indicatorSpace = dpToPx(DEFAULT_INDICATOR_SPACE_DP)
    private var createIndicatorView: ((parent: LinearLayout) -> View)? = null
    private var bindIndicatorView: ((view: View, isSelected: Boolean, position: Int) -> Unit)? = null
    private var visibleItemCount = DEFAULT_VISIBLE_ITEM_COUNT
    private var pageItemMargin = 0
    private var indicatorBottomMargin = dpToPx(DEFAULT_INDICATOR_BOTTOM_MARGIN_DP)
    private var disallowParentIntercept = true
    private var itemPageTransformer: ViewPager2.PageTransformer? = null
    private var isUserTouching = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private val clipPath = Path()
    private val clipRect = RectF()
    private val cornerRadii = FloatArray(8)

    init {
        viewPager.adapter = bannerAdapter
        clipChildren = false
        viewPager.clipToPadding = false
        viewPager.clipChildren = false
        (viewPager.getChildAt(0) as? RecyclerView)?.let { recyclerView ->
            recyclerView.clipToPadding = false
            recyclerView.clipChildren = false
        }
        viewPager.registerOnPageChangeCallback(pageCallback)
        addView(
            viewPager,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )

        indicatorLayout.orientation = LinearLayout.HORIZONTAL
        indicatorLayout.gravity = Gravity.CENTER
        val indicatorParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        indicatorParams.bottomMargin = indicatorBottomMargin
        addView(indicatorLayout, indicatorParams)
        readAttrs(attrs)
        updateOffscreenPageLimit()
    }

    /** 设置 Banner 数据，数据为空时不显示 item 和指示器。 */
    fun setItems(items: List<T>) {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
        if (recyclerView != null && recyclerView.isComputingLayout) {
            recyclerView.post { applyItems(items) }
        } else {
            applyItems(items)
        }
    }

    private fun applyItems(items: List<T>) {
        bannerAdapter.setItems(items)
        buildIndicators(items.size)
        if (items.isNotEmpty()) {
            val startPosition = getStartPosition(items.size)
            pageAnimator?.cancel()
            if (viewPager.isFakeDragging) viewPager.endFakeDrag()
            viewPager.setCurrentItem(startPosition, false)
            updateIndicator(startPosition)
        }
        restartAutoPlayIfNeeded()
    }

    /** 自定义每个 Banner item 的根内容 View；不设置时默认使用 ImageView。 */
    fun setCreateItemView(block: (parent: ViewGroup) -> View) {
        bannerAdapter.createItemBinding = null
        bannerAdapter.createItemView = block
    }

    /** 绑定自定义 item View 数据；设置后默认图片加载逻辑不再执行。 */
    fun setBindItemView(block: (view: View, item: T, realPosition: Int) -> Unit) {
        bannerAdapter.bindItemBinding = null
        bannerAdapter.bindItemView = block
    }

    /** 使用 ViewBinding 创建每个 Banner item，避免业务侧 findViewById。 */
    fun <B : ViewBinding> setCreateItemViewBinding(
        block: (inflater: LayoutInflater, parent: ViewGroup) -> B
    ) {
        bannerAdapter.createItemView = null
        bannerAdapter.createItemBinding = { inflater, parent -> block(inflater, parent) }
    }

    /** 使用 DataBinding 创建每个 Banner item，避免业务侧 findViewById。 */
    fun <B : ViewDataBinding> setCreateItemDataBinding(
        block: (inflater: LayoutInflater, parent: ViewGroup) -> B
    ) {
        bannerAdapter.createItemView = null
        bannerAdapter.createItemBinding = { inflater, parent -> block(inflater, parent) }
    }

    /** 绑定 ViewBinding item 数据；需要配合 setCreateItemViewBinding 使用。 */
    @Suppress("UNCHECKED_CAST")
    fun <B : ViewBinding> setBindItemViewBinding(block: (binding: B, item: T, realPosition: Int) -> Unit) {
        bannerAdapter.bindItemBinding = { binding, item, realPosition ->
            block(binding as B, item, realPosition)
        }
    }

    /** 绑定 DataBinding item 数据；需要配合 setCreateItemDataBinding 使用。 */
    @Suppress("UNCHECKED_CAST")
    fun <B : ViewDataBinding> setBindItemDataBinding(block: (binding: B, item: T, realPosition: Int) -> Unit) {
        bannerAdapter.bindItemBinding = { binding, item, realPosition ->
            block(binding as B, item, realPosition)
        }
    }

    /** 设置默认 ImageView 的缩放模式；未自定义 item View 时生效。 */
    fun setImageScaleType(scaleType: ImageView.ScaleType) {
        bannerAdapter.imageScaleType = scaleType
        notifyAdapterSafely()
    }

    /** 设置图片加载回调，用于接入 Glide 等图片加载框架。 */
    fun setLoadImage(block: (imageView: ImageView, item: T, realPosition: Int) -> Unit) {
        bannerAdapter.loadImage = block
    }

    /** 设置图片 Banner 数据，同时设置图片加载和点击回调。 */
    @Suppress("UNCHECKED_CAST")
    fun <R> setImageBannerItems(
        items: List<R>,
        loadImage: (imageView: ImageView, item: R, realPosition: Int) -> Unit,
        onClick: ((item: R, realPosition: Int) -> Unit)? = null
    ) {
        val banner = this as ViewPager2Banner<R>
        banner.setLoadImage(loadImage)
        banner.setOnBannerClickListener(onClick)
        banner.setItems(items)
    }

    /** 设置 Banner item 点击回调。 */
    fun setOnBannerClickListener(block: ((item: T, realPosition: Int) -> Unit)?) {
        bannerAdapter.onItemClick = block
    }

    /** 设置自动轮播间隔，单位毫秒，最小值为 1000ms。 */
    fun setAutoPlayInterval(intervalMillis: Long) {
        this.intervalMillis = intervalMillis.coerceAtLeast(MIN_INTERVAL_MILLIS)
        restartAutoPlayIfNeeded()
    }

    /** 设置自动/手动平滑切换的动画时长，单位毫秒。 */
    fun setScrollDuration(durationMillis: Long) {
        scrollDurationMillis = durationMillis.coerceAtLeast(MIN_SCROLL_DURATION_MILLIS)
    }

    /** 设置 Banner 切换动画插值器。 */
    fun setScrollInterpolator(interpolator: Interpolator) {
        scrollInterpolator = interpolator
    }

    /** 切换到指定 adapter position，smoothScroll 为 true 时使用自定义时长动画。 */
    fun setCurrentItem(position: Int, smoothScroll: Boolean = true) {
        if (!smoothScroll || scrollDurationMillis <= 0) {
            pageAnimator?.cancel()
            // 见 setItems 注释：fake drag 进行中调 setCurrentItem 会崩，先终止
            if (viewPager.isFakeDragging) viewPager.endFakeDrag()
            viewPager.setCurrentItem(position, smoothScroll)
            return
        }
        smoothScrollToItem(position)
    }

    /** 设置默认圆点指示器颜色；未设置 drawable 或自定义绑定时生效。 */
    fun setIndicatorColor(normalColor: Int, selectedColor: Int) {
        indicatorNormalColor = normalColor
        indicatorSelectedColor = selectedColor
        updateIndicator(viewPager.currentItem)
    }

    /** 设置指示器选中和未选中的 Drawable；优先级高于默认颜色圆点。 */
    fun setIndicatorDrawable(normalDrawable: Drawable?, selectedDrawable: Drawable?) {
        indicatorNormalDrawable = normalDrawable
        indicatorSelectedDrawable = selectedDrawable
        updateIndicator(viewPager.currentItem)
    }

    /** 通过资源 id 设置指示器选中和未选中的 Drawable。 */
    fun setIndicatorDrawable(normalResId: Int, selectedResId: Int) {
        setIndicatorDrawable(
            normalDrawable = ContextCompat.getDrawable(context, normalResId),
            selectedDrawable = ContextCompat.getDrawable(context, selectedResId)
        )
    }

    /** 设置默认指示器大小和间距，单位 dp。 */
    fun setIndicatorSize(sizeDp: Float, spaceDp: Float = DEFAULT_INDICATOR_SPACE_DP) {
        indicatorSize = dpToPx(sizeDp)
        indicatorSpace = dpToPx(spaceDp)
        buildIndicators(bannerAdapter.realCount)
        updateIndicator(viewPager.currentItem)
    }

    /** 设置指示器距离 Banner 底部的间距，单位 dp。 */
    fun setIndicatorBottomMargin(bottomMarginDp: Float) {
        indicatorBottomMargin = dpToPx(bottomMarginDp)
        updateIndicatorLayoutBottomMargin()
    }

    /** 设置指示器是否显示。 */
    fun setIndicatorVisible(visible: Boolean) {
        indicatorLayout.visibility = if (visible) VISIBLE else GONE
    }

    /** 返回当前指示器是否显示。 */
    fun isIndicatorVisible(): Boolean {
        return indicatorLayout.visibility == VISIBLE
    }

    /** 获取指示器容器，用于业务侧进一步自定义样式。 */
    fun getIndicatorLayout(): LinearLayout = indicatorLayout

    /** 设置指示器容器内部 gravity。 */
    fun setIndicatorGravity(gravity: Int) {
        indicatorLayout.gravity = gravity
    }

    /** 修改指示器容器布局参数，例如位置、外边距等。 */
    fun setIndicatorLayoutParams(block: (params: LayoutParams) -> Unit) {
        val params = indicatorLayout.layoutParams as? LayoutParams ?: LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )
        block(params)
        indicatorLayout.layoutParams = params
    }

    /** 自定义创建每个指示器 item View；传 null 恢复默认圆点创建逻辑。 */
    fun setCreateIndicatorView(block: ((parent: LinearLayout) -> View)?) {
        createIndicatorView = block
        buildIndicators(bannerAdapter.realCount)
        updateIndicator(viewPager.currentItem)
    }

    /** 自定义绑定指示器选中/未选中状态；设置后默认颜色或 drawable 逻辑不再执行。 */
    fun setBindIndicatorView(block: ((view: View, isSelected: Boolean, position: Int) -> Unit)?) {
        bindIndicatorView = block
        updateIndicator(viewPager.currentItem)
    }

    /** 设置一屏显示的 item 数量和 item 间距；count 为偶数时会自动转成下一个奇数。 */
    fun setVisibleItemCount(count: Int, itemMarginDp: Float = 0f) {
        visibleItemCount = if (count % 2 == 0) count + 1 else count
        visibleItemCount = visibleItemCount.coerceAtLeast(DEFAULT_VISIBLE_ITEM_COUNT)
        pageItemMargin = dpToPx(itemMarginDp).coerceAtLeast(0)
        updateOffscreenPageLimit()
        updatePageDisplay(width)
    }

    /**
     * 设置 Banner item 变换效果；会与一屏多 item 的内部位移逻辑组合执行。
     * position 为 ViewPager2 标准 position：0 表示当前页，-1/1 表示左右相邻页。
     */
    fun setItemPageTransformer(transformer: ViewPager2.PageTransformer?) {
        itemPageTransformer = transformer
        applyPageTransformer()
    }

    /** 设置是否在横向滑动时请求父布局不要拦截事件，用于解决嵌套滑动冲突。 */
    fun setDisallowParentIntercept(disallow: Boolean) {
        disallowParentIntercept = disallow
    }

    /** 设置四个角统一圆角，单位 dp。 */
    fun setCornerRadius(radiusDp: Float) {
        setCornerRadius(radiusDp, radiusDp, radiusDp, radiusDp)
    }

    /** 分别设置左上、右上、右下、左下圆角，单位 dp。 */
    fun setCornerRadius(
        leftTopDp: Float,
        rightTopDp: Float,
        rightBottomDp: Float,
        leftBottomDp: Float
    ) {
        setCornerRadiusPx(
            leftTop = dpToPx(leftTopDp).toFloat(),
            rightTop = dpToPx(rightTopDp).toFloat(),
            rightBottom = dpToPx(rightBottomDp).toFloat(),
            leftBottom = dpToPx(leftBottomDp).toFloat()
        )
    }

    /** 开启自动轮播；会自动跟随生命周期暂停和恢复。 */
    fun startAutoPlay() {
        autoPlayEnabled = true
        resumeAutoPlay()
    }

    /** 停止自动轮播。 */
    fun stopAutoPlay() {
        autoPlayEnabled = false
        pauseAutoPlay()
    }

    /** 手动绑定生命周期；不调用时会尝试从 ViewTree 自动获取 LifecycleOwner。 */
    fun bindLifecycle(owner: LifecycleOwner) {
        if (lifecycleOwner === owner) return
        unbindLifecycle()
        lifecycleOwner = owner
        owner.lifecycle.addObserver(lifecycleObserver)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            resumeAutoPlay()
        } else {
            pauseAutoPlay()
        }
    }

    /** 解绑生命周期观察，通常在 View detach 时自动调用。 */
    fun unbindLifecycle() {
        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
    }

    /** 获取内部 ViewPager2，供业务侧设置高级属性。 */
    fun getViewPager(): ViewPager2 = viewPager

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isAttached = true
        if (lifecycleOwner == null) {
            findViewTreeLifecycleOwner()?.let { owner ->
                bindLifecycle(owner)
            }
        }
        resumeAutoPlay()
    }

    override fun onDetachedFromWindow() {
        pauseAutoPlay()
        isAttached = false
        unbindLifecycle()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        handleUserTouch(ev)
        handleParentIntercept(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClipPath(w, h)
        updatePageDisplay(w)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (hasCornerRadius() && !clipPath.isEmpty) {
            val saveCount = canvas.save()
            canvas.clipPath(clipPath)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    private fun updateClipPath(width: Int, height: Int) {
        clipPath.reset()
        if (width <= 0 || height <= 0) return
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.addRoundRect(clipRect, cornerRadii, Path.Direction.CW)
    }

    private fun hasCornerRadius(): Boolean {
        return cornerRadii.any { it > 0f }
    }

    private fun readAttrs(attrs: AttributeSet?) {
        if (attrs == null) return
        val typedArray = context.obtainStyledAttributes(attrs, R.styleable.ViewPager2Banner)
        try {
            intervalMillis = typedArray.getInt(
                R.styleable.ViewPager2Banner_vpb_autoPlayInterval,
                DEFAULT_INTERVAL_MILLIS.toInt()
            ).toLong().coerceAtLeast(MIN_INTERVAL_MILLIS)
            scrollDurationMillis = typedArray.getInt(
                R.styleable.ViewPager2Banner_vpb_scrollDuration,
                DEFAULT_SCROLL_DURATION_MILLIS.toInt()
            ).toLong().coerceAtLeast(MIN_SCROLL_DURATION_MILLIS)
            indicatorNormalColor = typedArray.getColor(
                R.styleable.ViewPager2Banner_vpb_indicatorNormalColor,
                DEFAULT_INDICATOR_NORMAL_COLOR
            )
            indicatorSelectedColor = typedArray.getColor(
                R.styleable.ViewPager2Banner_vpb_indicatorSelectedColor,
                DEFAULT_INDICATOR_SELECTED_COLOR
            )
            indicatorNormalDrawable = drawableFromAttr(
                typedArray.getResourceId(R.styleable.ViewPager2Banner_vpb_indicatorNormalDrawable, 0)
            )
            indicatorSelectedDrawable = drawableFromAttr(
                typedArray.getResourceId(R.styleable.ViewPager2Banner_vpb_indicatorSelectedDrawable, 0)
            )
            indicatorSize = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_indicatorSize,
                indicatorSize
            )
            indicatorSpace = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_indicatorSpace,
                indicatorSpace
            )
            indicatorBottomMargin = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_indicatorBottomMargin,
                indicatorBottomMargin
            )
            indicatorLayout.visibility = if (typedArray.getBoolean(
                    R.styleable.ViewPager2Banner_vpb_indicatorVisible,
                    true
                )
            ) VISIBLE else GONE
            visibleItemCount = typedArray.getInt(
                R.styleable.ViewPager2Banner_vpb_visibleItemCount,
                DEFAULT_VISIBLE_ITEM_COUNT
            ).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(DEFAULT_VISIBLE_ITEM_COUNT)
            pageItemMargin = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_itemMargin,
                0
            ).coerceAtLeast(0)
            disallowParentIntercept = typedArray.getBoolean(
                R.styleable.ViewPager2Banner_vpb_disallowParentIntercept,
                true
            )
            bannerAdapter.imageScaleType = imageScaleTypeFromValue(
                typedArray.getInt(R.styleable.ViewPager2Banner_vpb_imageScaleType, 1)
            )
            val cornerRadius = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_cornerRadius,
                0
            ).toFloat()
            val leftTop = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_leftTopRadius,
                cornerRadius.toInt()
            ).toFloat()
            val rightTop = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_rightTopRadius,
                cornerRadius.toInt()
            ).toFloat()
            val rightBottom = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_rightBottomRadius,
                cornerRadius.toInt()
            ).toFloat()
            val leftBottom = typedArray.getDimensionPixelSize(
                R.styleable.ViewPager2Banner_vpb_leftBottomRadius,
                cornerRadius.toInt()
            ).toFloat()
            setCornerRadiusPx(leftTop, rightTop, rightBottom, leftBottom)
            updateIndicatorLayoutBottomMargin()
            if (typedArray.getBoolean(R.styleable.ViewPager2Banner_vpb_autoPlay, false)) {
                autoPlayEnabled = true
            }
        } finally {
            typedArray.recycle()
        }
    }

    private fun imageScaleTypeFromValue(value: Int): ImageView.ScaleType {
        return when (value) {
            0 -> ImageView.ScaleType.FIT_XY
            2 -> ImageView.ScaleType.CENTER_INSIDE
            3 -> ImageView.ScaleType.FIT_CENTER
            else -> ImageView.ScaleType.CENTER_CROP
        }
    }

    private fun drawableFromAttr(resId: Int): Drawable? {
        if (resId == 0) return null
        return ContextCompat.getDrawable(context, resId)
    }

    private fun setCornerRadiusPx(
        leftTop: Float,
        rightTop: Float,
        rightBottom: Float,
        leftBottom: Float
    ) {
        cornerRadii[0] = leftTop
        cornerRadii[1] = leftTop
        cornerRadii[2] = rightTop
        cornerRadii[3] = rightTop
        cornerRadii[4] = rightBottom
        cornerRadii[5] = rightBottom
        cornerRadii[6] = leftBottom
        cornerRadii[7] = leftBottom
        updateClipPath(width, height)
        invalidate()
    }

    private fun updateIndicatorLayoutBottomMargin() {
        val params = indicatorLayout.layoutParams as? LayoutParams ?: return
        params.bottomMargin = indicatorBottomMargin
        indicatorLayout.layoutParams = params
    }

    private fun handleUserTouch(event: MotionEvent) {
        if (!viewPager.isUserInputEnabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isUserTouching = true
                pageAnimator?.cancel()
                removeCallbacks(autoPlayAction)
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                isUserTouching = false
                restartAutoPlayIfNeeded()
            }
        }
    }

    private fun handleParentIntercept(event: MotionEvent) {
        if (!disallowParentIntercept || !viewPager.isUserInputEnabled) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                    parent?.requestDisallowInterceptTouchEvent(abs(dx) > abs(dy))
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun updatePageDisplay(containerWidth: Int) {
        if (containerWidth <= 0) return
        val pageWidth = max(1, containerWidth / visibleItemCount)
        val itemWidth = max(1, pageWidth - pageItemMargin)
        viewPager.setPadding(0, 0, 0, 0)
        applyPageTransformer()
        // 仅在 item 尺寸真正变化时刷新，避免每次 onSizeChanged 都全量重建 ViewHolder
        if (bannerAdapter.itemWidth != itemWidth || bannerAdapter.itemMargin != pageItemMargin) {
            bannerAdapter.itemWidth = itemWidth
            bannerAdapter.itemMargin = pageItemMargin
            notifyAdapterSafely()
        }
    }

    /** 数据刷新防护：RecyclerView 正在计算布局/滚动时延后到下一帧，避免 IllegalStateException。 */
    private fun notifyAdapterSafely() {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView
        if (recyclerView != null && recyclerView.isComputingLayout) {
            recyclerView.post { bannerAdapter.notifyDataSetChanged() }
        } else {
            bannerAdapter.notifyDataSetChanged()
        }
    }

    private fun applyPageTransformer() {
        val containerWidth = width
        val pageWidth = if (visibleItemCount > 1 && containerWidth > 0) {
            max(1, containerWidth / visibleItemCount)
        } else {
            containerWidth
        }
        viewPager.setPageTransformer { page, position ->
            if (visibleItemCount > 1 && containerWidth > 0) {
                page.translationX = -position * (containerWidth - pageWidth)
            } else {
                page.translationX = 0f
            }
            itemPageTransformer?.transformPage(page, position)
        }
    }

    @SuppressLint("WrongConstant")
    private fun setOffscreenPageLimit(limit: Int) {
        viewPager.offscreenPageLimit = limit
    }

    /** 单 item 用 ViewPager2 默认离屏策略；一屏多 item 时保留两侧可见页不被回收。 */
    private fun updateOffscreenPageLimit() {
        val limit = if (visibleItemCount > 1) {
            visibleItemCount
        } else {
            ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
        }
        setOffscreenPageLimit(limit)
    }

    private fun smoothScrollToItem(position: Int) {
        val currentItem = viewPager.currentItem
        val distance = position - currentItem
        if (distance == 0) return
        val pageWidth = viewPager.width
        pageAnimator?.cancel()
        if (viewPager.isFakeDragging) viewPager.endFakeDrag()
        if (pageWidth <= 0 || !viewPager.beginFakeDrag()) {
            viewPager.setCurrentItem(position, true)
            return
        }
        val totalOffset = -distance.toFloat() * pageWidth
        pageAnimator = ValueAnimator.ofFloat(0f, totalOffset).apply {
            duration = scrollDurationMillis
            interpolator = scrollInterpolator
            var lastValue = 0f
            addUpdateListener { animator ->
                val current = animator.animatedValue as Float
                if (viewPager.isFakeDragging) {
                    viewPager.fakeDragBy(current - lastValue)
                    lastValue = current
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (viewPager.isFakeDragging) viewPager.endFakeDrag()
                }
            })
            start()
        }
    }

    private fun resumeAutoPlay() {
        removeCallbacks(autoPlayAction)
        if (!autoPlayEnabled || bannerAdapter.realCount <= 1 || !isAttached) {
            isAutoPlaying = false
            return
        }
        isAutoPlaying = true
        postDelayed(autoPlayAction, intervalMillis)
    }

    private fun pauseAutoPlay() {
        isAutoPlaying = false
        removeCallbacks(autoPlayAction)
        // 暂停时取消进行中的平滑滚动动画，避免页面不可见/已分离后动画仍在 scrollBy
        pageAnimator?.cancel()
    }

    private fun restartAutoPlayIfNeeded() {
        if (autoPlayEnabled) {
            resumeAutoPlay()
        }
    }

    private fun getStartPosition(realCount: Int): Int {
        if (realCount <= 1) return 0
        val middle = Int.MAX_VALUE / 2
        return middle - middle % realCount
    }

    private fun buildIndicators(count: Int) {
        indicatorLayout.removeAllViews()
        repeat(count) { position ->
            val dot = createIndicatorView?.invoke(indicatorLayout) ?: View(context)
            if (dot.layoutParams == null) {
                val params = LinearLayout.LayoutParams(indicatorSize, indicatorSize)
                params.leftMargin = indicatorSpace / 2
                params.rightMargin = indicatorSpace / 2
                dot.layoutParams = params
            }
            indicatorLayout.addView(dot)
            bindIndicatorView?.invoke(dot, false, position)
        }
    }

    private fun updateIndicator(position: Int) {
        val count = indicatorLayout.childCount
        if (count <= 0) return
        val selectedPosition = position % count
        for (index in 0 until count) {
            val itemView = indicatorLayout.getChildAt(index)
            val isSelected = index == selectedPosition
            val bindBlock = bindIndicatorView
            if (bindBlock != null) {
                bindBlock.invoke(itemView, isSelected, index)
            } else {
                itemView.background = indicatorDrawable(isSelected)
            }
        }
    }

    private fun indicatorDrawable(isSelected: Boolean): Drawable {
        val drawable = if (isSelected) indicatorSelectedDrawable else indicatorNormalDrawable
        return drawable?.constantState?.newDrawable()?.mutate()
            ?: createDotDrawable(if (isSelected) indicatorSelectedColor else indicatorNormalColor)
    }

    private fun createDotDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    private fun dpToPx(value: Float): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private class BannerAdapter<T> : RecyclerView.Adapter<BannerViewHolder>() {

        private val items = mutableListOf<T>()
        var createItemView: ((parent: ViewGroup) -> View)? = null
        var createItemBinding: ((inflater: LayoutInflater, parent: ViewGroup) -> Any)? = null
        var bindItemView: ((view: View, item: T, realPosition: Int) -> Unit)? = null
        var bindItemBinding: ((binding: Any, item: T, realPosition: Int) -> Unit)? = null
        var loadImage: ((imageView: ImageView, item: T, realPosition: Int) -> Unit)? = null
        var onItemClick: ((item: T, realPosition: Int) -> Unit)? = null
        var imageScaleType = ImageView.ScaleType.CENTER_CROP
        var itemWidth = LayoutParams.MATCH_PARENT
        var itemMargin = 0

        val realCount: Int
            get() = items.size

        override fun getItemCount(): Int {
            return when (items.size) {
                0 -> 0
                1 -> 1
                else -> Int.MAX_VALUE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BannerViewHolder {
            val binding = createItemBinding?.invoke(LayoutInflater.from(parent.context), parent)
            val contentView = bindingRoot(binding) ?: createItemView?.invoke(parent) ?: ImageView(parent.context).apply {
                scaleType = imageScaleType
            }
            val pageView = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT
                )
                addView(contentView, createContentLayoutParams())
            }
            val holder = BannerViewHolder(pageView, contentView, binding)
            val clickAction = OnClickListener {
                val adapterPosition = holder.bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION && items.isNotEmpty()) {
                    val realPosition = adapterPosition % items.size
                    onItemClick?.invoke(items[realPosition], realPosition)
                }
            }
            pageView.setOnClickListener(clickAction)
            contentView.setOnClickListener(clickAction)
            return holder
        }

        override fun onBindViewHolder(holder: BannerViewHolder, position: Int) {
            if (items.isEmpty()) return
            updateItemLayoutParams(holder.contentView)
            val realPosition = position % items.size
            val item = items[realPosition]
            val bindBindingBlock = bindItemBinding
            val bindBlock = bindItemView
            val imageView = holder.contentView as? ImageView
            if (bindBindingBlock != null && holder.binding != null) {
                bindBindingBlock.invoke(holder.binding, item, realPosition)
            } else if (bindBlock != null) {
                bindBlock.invoke(holder.contentView, item, realPosition)
            } else if (imageView != null) {
                imageView.scaleType = imageScaleType
                bindImageView(imageView, item, realPosition)
            }
        }

        fun setItems(newItems: List<T>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        private fun bindImageView(imageView: ImageView, item: T, realPosition: Int) {
            val loadBlock = loadImage
            if (loadBlock != null) {
                loadBlock.invoke(imageView, item, realPosition)
                return
            }
            when (item) {
                is Int -> imageView.setImageResource(item)
                is Uri -> imageView.setImageURI(item)
                is Drawable -> imageView.setImageDrawable(item)
                is String -> Glide.with(imageView).load(item).into(imageView)
            }
        }

        private fun updateItemLayoutParams(itemView: View) {
            val params = itemView.layoutParams as? LayoutParams
            if (params != null) {
                params.width = itemWidth
                params.height = LayoutParams.MATCH_PARENT
                params.gravity = Gravity.CENTER
                val halfMargin = itemMargin / 2
                params.leftMargin = halfMargin
                params.rightMargin = halfMargin
                itemView.layoutParams = params
            }
        }

        private fun createContentLayoutParams(): LayoutParams {
            val halfMargin = itemMargin / 2
            return LayoutParams(
                itemWidth,
                LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            ).apply {
                leftMargin = halfMargin
                rightMargin = halfMargin
            }
        }

        private fun bindingRoot(binding: Any?): View? {
            return when (binding) {
                is ViewBinding -> binding.root
                is ViewDataBinding -> binding.root
                else -> null
            }
        }
    }

    private class BannerViewHolder(
        itemView: View,
        val contentView: View,
        val binding: Any?
    ) : RecyclerView.ViewHolder(itemView)

    companion object {
        private const val DEFAULT_INTERVAL_MILLIS = 3000L
        private const val MIN_INTERVAL_MILLIS = 1000L
        private const val DEFAULT_SCROLL_DURATION_MILLIS = 800L
        private const val MIN_SCROLL_DURATION_MILLIS = 100L
        private const val DEFAULT_INDICATOR_SIZE_DP = 6f
        private const val DEFAULT_INDICATOR_SPACE_DP = 4f
        private const val DEFAULT_INDICATOR_BOTTOM_MARGIN_DP = 8f
        private const val DEFAULT_VISIBLE_ITEM_COUNT = 1
        private val DEFAULT_INDICATOR_NORMAL_COLOR = Color.parseColor("#66FFFFFF")
        private val DEFAULT_INDICATOR_SELECTED_COLOR = Color.WHITE
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T> ViewPager2Banner<*>.asTypedBanner(): ViewPager2Banner<T> {
    return this as ViewPager2Banner<T>
}

/**
 * XML/DataBinding 场景下设置 Banner 数据。
 *
 * XML 中无法声明 ViewPager2Banner<T> 的泛型，直接调用 setItems 可能推断成 Any?；
 * 通过 items 参数可让 Kotlin 自动推断 T。
 */
fun <T> ViewPager2Banner<*>.setBannerItems(items: List<T>) {
    asTypedBanner<T>().setItems(items)
}

/**
 * XML/DataBinding 场景下设置 Banner 数据和点击回调。
 *
 * T 会从 items 自动推断，onClick 中的 item 会是具体业务类型。
 */
fun <T> ViewPager2Banner<*>.setBannerData(
    items: List<T>,
    onClick: ((item: T, realPosition: Int) -> Unit)? = null
) {
    val banner = asTypedBanner<T>()
    banner.setOnBannerClickListener(onClick)
    banner.setItems(items)
}

/**
 * XML/DataBinding 场景下设置自定义 View Banner 数据、绑定回调和点击回调。
 *
 * T 会从 items 自动推断，bindItem/onClick 中的 item 会是具体业务类型。
 */
fun <T> ViewPager2Banner<*>.setBannerData(
    items: List<T>,
    bindItem: (view: View, item: T, realPosition: Int) -> Unit,
    onClick: ((item: T, realPosition: Int) -> Unit)? = null
) {
    val banner = asTypedBanner<T>()
    banner.setBindItemView(bindItem)
    banner.setOnBannerClickListener(onClick)
    banner.setItems(items)
}

/**
 * XML/DataBinding 场景下设置图片 Banner 数据、图片加载回调和点击回调。
 *
 * T 会从 items 自动推断，loadImage/onClick 中的 item 会是具体业务类型。
 */
fun <T> ViewPager2Banner<*>.setBannerImageData(
    items: List<T>,
    loadImage: (imageView: ImageView, item: T, realPosition: Int) -> Unit,
    onClick: ((item: T, realPosition: Int) -> Unit)? = null
) {
    val banner = asTypedBanner<T>()
    banner.setLoadImage(loadImage)
    banner.setOnBannerClickListener(onClick)
    banner.setItems(items)
}

/**
 * XML/DataBinding 场景下追加设置图片加载回调。
 *
 * 需要通过 sampleItem 提供类型信息，适用于数据已通过其它入口设置的场景。
 */
fun <T> ViewPager2Banner<*>.setBannerLoadImage(
    sampleItem: T,
    loadImage: (imageView: ImageView, item: T, realPosition: Int) -> Unit
) {
    asTypedBanner<T>().setLoadImage(loadImage)
}

/**
 * XML/DataBinding 场景下追加设置点击回调。
 *
 * 需要通过 sampleItem 提供类型信息，适用于数据已通过其它入口设置的场景。
 */
fun <T> ViewPager2Banner<*>.setBannerClickListener(
    sampleItem: T,
    onClick: ((item: T, realPosition: Int) -> Unit)?
) {
    asTypedBanner<T>().setOnBannerClickListener(onClick)
}

/**
 * XML/DataBinding 场景下使用 ViewBinding 设置自定义 item Banner。
 *
 * T 会从 items 自动推断，B 会从 createBinding 返回值或 bindItem 参数自动推断。
 */
fun <T, B : ViewBinding> ViewPager2Banner<*>.setBannerViewBindingData(
    items: List<T>,
    createBinding: (inflater: LayoutInflater, parent: ViewGroup) -> B,
    bindItem: (binding: B, item: T, realPosition: Int) -> Unit,
    onClick: ((item: T, realPosition: Int) -> Unit)? = null
) {
    val banner = asTypedBanner<T>()
    banner.setCreateItemViewBinding(createBinding)
    banner.setBindItemViewBinding(bindItem)
    banner.setOnBannerClickListener(onClick)
    banner.setItems(items)
}

/**
 * XML/DataBinding 场景下使用 DataBinding 设置自定义 item Banner。
 *
 * T 会从 items 自动推断，B 会从 createBinding 返回值或 bindItem 参数自动推断。
 */
fun <T, B : ViewDataBinding> ViewPager2Banner<*>.setBannerDataBindingData(
    items: List<T>,
    createBinding: (inflater: LayoutInflater, parent: ViewGroup) -> B,
    bindItem: (binding: B, item: T, realPosition: Int) -> Unit,
    onClick: ((item: T, realPosition: Int) -> Unit)? = null
) {
    val banner = asTypedBanner<T>()
    banner.setCreateItemDataBinding(createBinding)
    banner.setBindItemDataBinding(bindItem)
    banner.setOnBannerClickListener(onClick)
    banner.setItems(items)
}
