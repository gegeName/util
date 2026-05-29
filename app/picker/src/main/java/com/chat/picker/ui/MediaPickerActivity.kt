package com.chat.picker.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.api.SelectionConfig
import com.chat.picker.data.MediaRepository
import com.chat.picker.model.MediaEntity

class MediaPickerActivity : AppCompatActivity() {

    private lateinit var config: SelectionConfig
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var btnToggle: TextView
    private lateinit var btnConfirm: TextView
    private lateinit var btnPreview: TextView
    private lateinit var partialBar: View
    private var loadingDialog: LoadingDialog? = null

    private var isGrid: Boolean = true
    private var adapter: MediaListAdapter? = null

    // ===== 分页状态 =====
    private val pageSize: Int = MediaSelector.PAGE_SIZE
    private var currentOffset: Int = 0           // 累加：已请求过的总条数（含被去重的）
    private var hasMore: Boolean = true
    private var isLoadingPage: Boolean = false
    private var lastTriggerAt: Long = 0L         // 节流：避免高频 onScrolled 反复进入判断
    private val triggerCooldownMs = 200L
    private val loadedKeys = HashSet<Long>()     // id<<4|mediaType.ordinal 去重 key
    private val prefetchThreshold = 10           // 距底部 N 个时预加载下一页

    private fun keyOf(e: MediaEntity): Long =
        (e.id shl 4) or (e.mediaType.ordinal.toLong() and 0xF)

    /** 是否在 adapter 中显示相机入口：仅 grid + 开关开 + 非纯音频 */
    private fun shouldShowCamera(): Boolean =
        config.showCameraEntry && isGrid &&
            config.filter.type != com.chat.picker.model.MediaType.AUDIO

    /** 相机 item 占位偏移，0 或 1 */
    private fun cameraOffset(): Int = if (shouldShowCamera()) 1 else 0

    /** 给 adapter 提交的最终列表：可能在前面加 CAMERA_ENTRY */
    private fun buildDisplayList(data: List<MediaEntity>): List<MediaEntity> {
        if (!shouldShowCamera()) return data.toList()
        return ArrayList<MediaEntity>(data.size + 1).apply {
            add(MediaListAdapter.CAMERA_ENTRY)
            addAll(data)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (PermissionHelper.anyUsable(this, config.filter.type)) {
            MediaSelector.invalidateCache()
            loadData()
            updatePartialBarVisibility()
        } else {
            emptyView.visibility = View.VISIBLE
            (emptyView as TextView).text = "未授予媒体权限"
            dismissLoading()
        }
    }

    // ===== 拍照 =====
    private var pendingCamera: com.chat.picker.camera.CameraHelper.Pending? = null
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val p = pendingCamera ?: return@registerForActivityResult
        pendingCamera = null
        val file = java.io.File(p.filePath)
        val exists = file.exists()
        val len = if (exists) file.length() else 0L
        com.chat.picker.util.PickerLog.d(
            "in-picker TakePicture success=$success exists=$exists size=$len path=${p.filePath}"
        )
        val ok = success && exists && len > 0
        if (ok) {
            // 异步注册到系统媒体库
            p.onSuccess()
            val entity = com.chat.picker.camera.CameraHelper.makeEntity(p.filePath, p.uri)
            insertCapturedPhoto(entity)
            // 下次进 picker 重新查询能拿到新照片
            MediaSelector.invalidateCache()
        } else {
            p.onFail()
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) doLaunchCamera()
        else android.widget.Toast.makeText(
            this, "拍照需要相机权限", android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun launchCamera() {
        if (com.chat.picker.camera.CameraHelper.hasCameraPermission(this)) {
            doLaunchCamera()
        } else {
            cameraPermLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun doLaunchCamera() {
        val p = com.chat.picker.camera.CameraHelper.prepare(this)
        pendingCamera = p
        cameraLauncher.launch(p.uri)
    }

    /** 拍照完成：插入 Selection.all 第 0 位（adapter 上呈现为 camera 后第 2 位）并自动选中 */
    private fun insertCapturedPhoto(entity: MediaEntity) {
        com.chat.picker.util.PickerLog.d(
            "insertCapturedPhoto id=${entity.id} uri=${entity.uri} path=${entity.filePath} " +
                "all.size(before)=${Selection.all.size} adapter.itemCount=${adapter?.itemCount}"
        )
        if (!loadedKeys.add(keyOf(entity))) {
            com.chat.picker.util.PickerLog.w("key already loaded, skip")
            return
        }
        Selection.all.add(0, entity)
        // 自动选中（超上限时给提示，但仍把图保留在列表中供查看）
        val result = Selection.toggle(entity)
        if (!result.accepted) {
            android.widget.Toast.makeText(
                this, "已超出上限 ${config.maxCount} 项，请先取消其它选择",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        adapter?.submitList(buildDisplayList(Selection.all))
        updateConfirmButton()
    }

    private val previewLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == MediaPreviewActivity.RESULT_CONFIRMED) {
            finishWithResult()
        } else {
            // 预览页可能改了多次选中态，无法精确还原变化集；走全量带 payload 刷新
            adapter?.notifySelectionChangedAll()
            updateConfirmButton()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = MediaSelector.pendingConfig
        if (cfg == null) {
            finish(); return
        }
        config = cfg
        isGrid = config.startInGrid

        setContentView(R.layout.picker_activity_list)
        com.chat.picker.util.EdgeToEdge.apply(
            activity = this,
            root = findViewById(R.id.picker_root),
            topBar = findViewById(R.id.picker_top_bar),
            bottomBar = findViewById(R.id.picker_bottom_bar),
        )
        recycler = findViewById(R.id.picker_recycler)
        emptyView = findViewById(R.id.picker_empty)
        partialBar = findViewById(R.id.picker_partial_bar)
        btnToggle = findViewById(R.id.picker_btn_toggle)
        btnConfirm = findViewById(R.id.picker_confirm)
        btnPreview = findViewById(R.id.picker_preview)

        Selection.clear()
        Selection.max = config.maxCount
        // 灌入预选项：picker 列表渲染时通过 Selection.indexOf 自动显示角标
        if (config.preSelected.isNotEmpty()) {
            Selection.preSelect(config.preSelected)
        }

        findViewById<TextView>(R.id.picker_btn_cancel).setOnClickListener {
            setResult(Activity.RESULT_CANCELED); finish()
        }
        btnToggle.setOnClickListener { toggleLayout() }
        btnConfirm.setOnClickListener { finishWithResult() }
        findViewById<TextView>(R.id.picker_partial_manage).setOnClickListener {
            // 重新拉起权限弹窗让用户管理可访问的项
            permissionLauncher.launch(PermissionHelper.requiredPermissions(config.filter.type))
        }
        btnPreview.setOnClickListener {
            if (Selection.selected.isEmpty()) return@setOnClickListener
            openPreview(Selection.selected.toList(), 0, false)
        }

        setupRecycler()
        updateConfirmButton()   // preSelected 灌入后立即显示数字
        requestPermissionsAndLoad()
    }

    private fun setupRecycler() {
        adapter = MediaListAdapter(
            isGrid = isGrid,
            onItemClick = { position, _ ->
                // 列表里点击：position 已是 adapter 内位置，需要减去相机偏移
                val realIndex = position - cameraOffset()
                if (realIndex in Selection.all.indices) {
                    openPreview(Selection.all, realIndex, true)
                }
            },
            onCheckClick = { _, item -> onCheckToggle(item) },
            onCameraClick = { launchCamera() },
        )
        recycler.layoutManager = if (isGrid)
            GridLayoutManager(this, config.gridSpanCount)
        else LinearLayoutManager(this)
        recycler.adapter = adapter
        recycler.itemAnimator = null
        adapter?.submitList(buildDisplayList(Selection.all))
        btnToggle.text = if (isGrid) "列表" else "网格"
        attachPrefetchListener()
    }

    private fun attachPrefetchListener() {
        recycler.clearOnScrollListeners()
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // 多重短路：方向 → 状态 → 节流（最便宜的判断在前）
                if (dy <= 0) return
                if (isLoadingPage || !hasMore) return
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastTriggerAt < triggerCooldownMs) return

                val lm = rv.layoutManager ?: return
                val total = lm.itemCount
                if (total <= 0) return
                val lastVisible = when (lm) {
                    is GridLayoutManager -> lm.findLastVisibleItemPosition()
                    is LinearLayoutManager -> lm.findLastVisibleItemPosition()
                    else -> return
                }
                if (lastVisible < 0) return
                if (lastVisible >= total - prefetchThreshold) {
                    lastTriggerAt = now
                    loadNextPage()
                }
            }
        })
    }

    private fun toggleLayout() {
        isGrid = !isGrid
        setupRecycler()
    }

    private fun onCheckToggle(item: MediaEntity) {
        val result = Selection.toggle(item)
        if (!result.accepted) {
            android.widget.Toast.makeText(
                this, "最多选择 ${config.maxCount} 项", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        adapter?.notifySelectionChanged(result.affected)
        updateConfirmButton()
    }

    private fun updateConfirmButton() {
        btnConfirm.text = "完成(${Selection.selected.size}/${config.maxCount})"
    }

    private fun requestPermissionsAndLoad() {
        val perms = PermissionHelper.requiredPermissions(config.filter.type)
        if (PermissionHelper.anyUsable(this, config.filter.type)) {
            loadData()
            updatePartialBarVisibility()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    private fun updatePartialBarVisibility() {
        partialBar.visibility =
            if (PermissionHelper.isPartialAccess(this, config.filter.type)) View.VISIBLE
            else View.GONE
    }

    private fun loadData() {
        // 重置分页状态：首次/重新拉权限后调用
        emptyView.visibility = View.GONE
        currentOffset = 0
        hasMore = true
        isLoadingPage = false
        lastTriggerAt = 0L
        loadedKeys.clear()
        Selection.all.clear()
        adapter?.submitList(buildDisplayList(emptyList()))

        val isCanonical = config.filter.mimeTypes.isEmpty() && config.filter.extraSelection == null
        val cached = MediaSelector.cached(config.filter.type)
        if (cached != null && isCanonical) {
            // 命中首页缓存：秒开，不弹 loading；后续翻页继续 query
            appendPage(cached, fromCache = true)
            return
        }

        showLoading("正在加载文件...")
        loadPageInternal(isCanonical, isFirstPage = true)
    }

    private fun loadNextPage() {
        if (isLoadingPage || !hasMore) return
        val isCanonical = config.filter.mimeTypes.isEmpty() && config.filter.extraSelection == null
        loadPageInternal(isCanonical, isFirstPage = false)
    }

    private fun loadPageInternal(isCanonical: Boolean, isFirstPage: Boolean) {
        isLoadingPage = true
        val offset = currentOffset
        MediaRepository.queryAsync(
            applicationContext, config.filter,
            offset = offset, limit = pageSize,
        ) { list ->
            runOnUiThread {
                // 首页且 canonical 时回写缓存（与列表查询保持同节奏）
                if (isFirstPage && isCanonical && list.isNotEmpty()) {
                    MediaSelector.putCache(config.filter.type, list)
                }
                appendPage(list, fromCache = false)
            }
        }
    }

    /** 把新一页数据追加进列表，自动去重并更新 hasMore */
    private fun appendPage(page: List<MediaEntity>, fromCache: Boolean) {
        dismissLoading()
        val newOnes = page.filter { loadedKeys.add(keyOf(it)) }
        if (newOnes.isNotEmpty()) {
            Selection.all.addAll(newOnes)
            adapter?.submitList(buildDisplayList(Selection.all))
        }
        // 关键：offset 累加实际请求的条数（不是已加入列表的条数），
        // 否则"返回非空但全被去重"会让 offset 不前进 → 下次查同一页 → 死循环
        currentOffset += page.size

        // hasMore 三道判定，任一为否就停：
        //   1) 返回不足一页 → 数据已到尾
        //   2) 返回非空但去重后无新增 → offset 重叠或全量已加载，再查也只会重复
        //   3) cache 命中且 cache 不足一页 → 数据本就只有这么多
        hasMore = when {
            page.size < pageSize -> false
            page.isNotEmpty() && newOnes.isEmpty() -> false
            fromCache && page.size < pageSize -> false
            else -> true
        }
        isLoadingPage = false

        emptyView.visibility = if (Selection.all.isEmpty()) View.VISIBLE else View.GONE
        updateConfirmButton()
    }

    private fun showLoading(text: String) {
        if (isFinishing || isDestroyed) return
        val dlg = loadingDialog ?: LoadingDialog(this).also { loadingDialog = it }
        dlg.setText(text)
        if (!dlg.isShowing) dlg.show()
    }

    private fun dismissLoading() {
        loadingDialog?.takeIf { it.isShowing }?.dismiss()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 退出列表时清理共享状态 + 释放图片缓存
        if (isFinishing) {
            Selection.clear()
            MediaSelector.clearActiveEngine()
            MediaSelector.clearActiveCompressors()
            com.chat.picker.loader.ImageLoader.clear()
        }
        // 中断未完成的压缩任务，避免 Activity 销毁后仍占用 CPU
        compressPool?.shutdownNow()
        compressPool = null
        dismissLoading()
        loadingDialog = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            com.chat.picker.loader.ImageLoader.clear()
        }
    }

    private fun openPreview(source: List<MediaEntity>, index: Int, fromList: Boolean) {
        PreviewBridge.previewList = source
        val intent = Intent(this, MediaPreviewActivity::class.java).apply {
            putExtra(MediaPreviewActivity.EXTRA_INDEX, index)
            putExtra(MediaPreviewActivity.EXTRA_FROM_LIST, fromList)
            putExtra(MediaPreviewActivity.EXTRA_MAX_COUNT, config.maxCount)
        }
        previewLauncher.launch(intent)
    }

    // ===== 压缩流程 =====
    private var compressPool: java.util.concurrent.ExecutorService? = null

    private fun finishWithResult() {
        val list = Selection.selected.toList()
        val imageC = MediaSelector.imageCompressor()
        val videoC = MediaSelector.videoCompressor()

        // 判断有哪些项真的需要走压缩：按类型分别匹配 + 各自 needsCompress
        val needCompress = list.any { item ->
            (item.isImage && imageC != null && imageC.needsCompress(item)) ||
                (item.isVideo && videoC != null && videoC.needsCompress(item))
        }
        if (!needCompress) {
            deliverResult(list); return
        }
        runCompress(list, imageC, videoC)
    }

    private fun runCompress(
        list: List<MediaEntity>,
        imageC: com.chat.picker.compress.IImageCompressor?,
        videoC: com.chat.picker.compress.IVideoCompressor?,
    ) {
        val total = list.size
        val results = arrayOfNulls<MediaEntity>(total)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val parallel = (Runtime.getRuntime().availableProcessors() / 2)
            .coerceIn(1, 4).coerceAtMost(total)
        // 池只负责"发起任务"：同步实现会在池线程里跑完压缩，异步实现则在 launch 后立刻空闲
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallel)
            .also { compressPool = it }

        val imgCount = list.count { it.isImage && imageC != null && imageC.needsCompress(it) }
        val vidCount = list.count { it.isVideo && videoC != null && videoC.needsCompress(it) }
        showLoading(buildProgressText(0, total, imgCount, vidCount))

        // 每项完成时统一走这里（onSuccess/onError 内部已保证只触发一次）
        fun onItemDone(index: Int, result: MediaEntity) {
            results[index] = result
            val c = done.incrementAndGet()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                showLoading(buildProgressText(c, total, imgCount, vidCount))
                if (c == total) {
                    pool.shutdown()
                    compressPool = null
                    deliverResult(results.filterNotNull())
                }
            }
        }

        list.forEachIndexed { i, item ->
            // callback 内置原 item 引用：用户调 onError 时框架自动兜底原文件
            val callback = com.chat.picker.compress.CompressCallback(item) { result ->
                onItemDone(i, result)
            }
            pool.execute {
                try {
                    when {
                        item.isImage && imageC != null && imageC.needsCompress(item) ->
                            imageC.compress(applicationContext, item, callback)
                        item.isVideo && videoC != null && videoC.needsCompress(item) ->
                            videoC.compress(applicationContext, item, callback)
                        else -> callback.onSuccess(item) // 音频/不需压缩项：原样返回
                    }
                } catch (e: Throwable) {
                    // 用户实现 compress() 同步抛异常 → 走 onError 兜底
                    callback.onError(e)
                }
            }
        }
    }

    private fun buildProgressText(done: Int, total: Int, img: Int, vid: Int): String =
        buildString {
            append("正在压缩 $done/$total")
            if (img > 0 || vid > 0) {
                append("\n")
                if (img > 0) append("图片×$img")
                if (img > 0 && vid > 0) append("  ")
                if (vid > 0) append("视频×$vid")
            }
        }

    private fun deliverResult(list: List<MediaEntity>) {
        dismissLoading()
        val intent = Intent().apply {
            putParcelableArrayListExtra(MediaSelector.EXTRA_RESULT, ArrayList(list))
        }
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}
