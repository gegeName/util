package com.chat.picker.api

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import com.chat.picker.compress.IImageCompressor
import com.chat.picker.compress.IVideoCompressor
import com.chat.picker.loader.DefaultImageEngine
import com.chat.picker.loader.IImageEngine
import com.chat.picker.model.MediaEntity
import com.chat.picker.model.MediaFilter
import com.chat.picker.model.MediaType
import com.chat.picker.preview.IOtherPreviewProvider

/**
 * 入口。用法：
 *   MediaSelector.with(activity)
 *     .type(MediaType.IMAGE)
 *     .maxCount(9)
 *     .grid(true)
 *     .start { result -> ... }
 *
 * 初始化预查询：
 *   MediaSelector.preload(context, MediaType.IMAGE, MediaType.VIDEO)
 */
class MediaSelector private constructor(private val activity: ComponentActivity) {

    private val cfg = SelectionConfig()

    fun type(type: MediaType) = apply {
        cfg.filter = MediaFilter.Builder(type).build()
    }

    fun filter(filter: MediaFilter) = apply { cfg.filter = filter }

    fun filter(type: MediaType, block: MediaFilter.Builder.() -> Unit = {}) = apply {
        cfg.filter = MediaFilter.Builder(type).apply(block).build()
    }

    fun maxCount(n: Int) = apply { cfg.maxCount = n.coerceAtLeast(1) }
    fun grid(enable: Boolean) = apply { cfg.startInGrid = enable }
    fun spanCount(n: Int) = apply { cfg.gridSpanCount = n.coerceAtLeast(2) }
    fun multiSelect(enable: Boolean) = apply { cfg.enableMultiSelect = enable }

    /** 单次覆盖：仅本次调用使用该 engine，不影响全局 */
    fun imageEngine(engine: IImageEngine) = apply { MediaSelectorInternal.activeEngine = engine }

    /** 单次覆盖：仅本次使用该图片压缩器 */
    fun imageCompressor(c: IImageCompressor) = apply { MediaSelectorInternal.activeImageCompressor = c }

    /** 单次覆盖：仅本次使用该视频压缩器 */
    fun videoCompressor(c: IVideoCompressor) = apply { MediaSelectorInternal.activeVideoCompressor = c }

    /** 启用系统 Photo Picker（API 33+，零权限）。AUDIO 类型会回退到本框架 */
    fun useSystemPicker(enable: Boolean) = apply { cfg.useSystemPhotoPicker = enable }

    /** 列表首位显示"相机入口" */
    fun showCameraEntry(enable: Boolean) = apply { cfg.showCameraEntry = enable }

    /** 传入已选过的项；打开 picker 时自动复选（按 id+mediaType 匹配） */
    fun preSelected(list: List<MediaEntity>) = apply { cfg.preSelected = list }

    fun start(listener: OnPickResultListener) {
        if (shouldUseSystemPicker()) {
            MediaSelectorInternal.launchSystemPicker(activity, cfg, listener)
        } else {
            MediaSelectorInternal.launchInternalPicker(activity, cfg, listener)
        }
    }

    private fun shouldUseSystemPicker(): Boolean =
        cfg.useSystemPhotoPicker &&
            cfg.filter.type != MediaType.AUDIO &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    companion object {
        const val EXTRA_RESULT = "picker_result"
        const val PAGE_SIZE = 50

        fun with(activity: ComponentActivity) = MediaSelector(activity)

        /**
         * 独立拍照入口：不进 picker UI，直接调系统相机拍一张并返回路径/uri。
         * @param listener onResult(success, filePath, uri)
         */
        @JvmStatic
        fun takePhoto(activity: ComponentActivity, listener: OnPhotoTakenListener) {
            com.chat.picker.camera.CameraHelper.take(activity) { ok, path, uri ->
                if (ok) invalidateCache()
                listener.onResult(ok, path, uri)
            }
        }

        /** 全局设置图片加载引擎；传 null 恢复内置默认 */
        fun setImageEngine(engine: IImageEngine?) {
            MediaSelectorInternal.globalEngine = engine
        }

        /** 全局注册"其他文件"预览扩展（doc/xls/pdf/zip 等）；传 null 取消 */
        fun setOtherPreviewProvider(provider: IOtherPreviewProvider?) {
            MediaSelectorInternal.globalOtherPreviewProvider = provider
        }

        /** 全局设置图片压缩器；传 null 则不压缩图片 */
        fun setImageCompressor(c: IImageCompressor?) {
            MediaSelectorInternal.globalImageCompressor = c
        }

        /** 全局设置视频压缩器；传 null 则不压缩视频 */
        fun setVideoCompressor(c: IVideoCompressor?) {
            MediaSelectorInternal.globalVideoCompressor = c
        }

        /**
         * 后台预查询：可在权限已就绪后调用，命中后列表页直接展示。
         * 调用前需具备相应类型读取权限，否则该类型直接跳过不入缓存。
         */
        fun preload(context: Context, vararg types: MediaType) =
            MediaSelectorInternal.preload(context, types, PAGE_SIZE)

        /** 仅在缓存非空时视为命中 */
        fun cached(type: MediaType): List<MediaEntity>? = MediaSelectorInternal.cached(type)

        fun invalidateCache() = MediaSelectorInternal.invalidateCache()


        internal val pendingConfig: SelectionConfig?
            get() = MediaSelectorInternal.pendingConfig

        internal fun otherPreviewProvider(): IOtherPreviewProvider? =
            MediaSelectorInternal.globalOtherPreviewProvider

        internal fun imageEngine(): IImageEngine =
            MediaSelectorInternal.activeEngine
                ?: MediaSelectorInternal.globalEngine
                ?: DefaultImageEngine

        internal fun clearActiveEngine() {
            MediaSelectorInternal.activeEngine = null
        }

        internal fun imageCompressor(): IImageCompressor? =
            MediaSelectorInternal.activeImageCompressor
                ?: MediaSelectorInternal.globalImageCompressor

        internal fun videoCompressor(): IVideoCompressor? =
            MediaSelectorInternal.activeVideoCompressor
                ?: MediaSelectorInternal.globalVideoCompressor

        internal fun clearActiveCompressors() {
            MediaSelectorInternal.activeImageCompressor = null
            MediaSelectorInternal.activeVideoCompressor = null
        }

        internal fun putCache(type: MediaType, list: List<MediaEntity>) =
            MediaSelectorInternal.putCache(type, list)
    }
}

typealias PickIt = MediaSelector
