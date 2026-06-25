package com.chat.uifoundation.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.NinePatch
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.core.graphics.get
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NinePatchUtil {

    private const val NO_COLOR = 0x00000001

    /** holder 复用防错 tag key —— 任意稳定 int，避开 R.id 常见区间 */
    private const val TAG_KEY_LOAD_URL = 0x7FFF_0001

    @Volatile private var defaultCapRatio = 3

    private class Entry(val chunk: ByteArray, val padding: Rect, val content: Bitmap)
    private var cache = LruCache<Any, Entry>(32)

    @JvmStatic
    fun setDefaultCapRatio(ratio: Int) {
        require(ratio > 1) { "ratio must be > 1" }
        defaultCapRatio = ratio
    }

    @JvmStatic
    fun resizeCache(maxSize: Int) { require(maxSize > 0); cache = LruCache(maxSize) }

    @JvmStatic
    fun clearCache() = cache.evictAll()

    @JvmStatic
    @JvmOverloads
    fun create(context: Context, bitmap: Bitmap, cacheKey: Any? = bitmap): NinePatchDrawable? {
        cacheKey?.let { k -> hit(context, k)?.let { return it } }

        bitmap.ninePatchChunk?.takeIf { NinePatch.isNinePatchChunk(it) }?.let { chunk ->
            cacheKey?.let { cache.put(it, Entry(chunk, Rect(), bitmap)) }
            return NinePatchDrawable(context.resources, bitmap, chunk, Rect(), null)
        }

        scanRaw9Patch(bitmap)?.let { e ->
            cacheKey?.let { cache.put(it, e) }
            return NinePatchDrawable(context.resources, e.content, e.chunk, e.padding, null)
        }

        val cap = minOf(bitmap.width, bitmap.height) / defaultCapRatio
        return fromCaps(context, bitmap, cap, cap, cap, cap, Rect(), cacheKey)
    }

    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        bitmap: Bitmap,
        cap: Int,
        padding: Rect = Rect(),
        cacheKey: Any? = capKey(bitmap, cap, cap, cap, cap),
    ): NinePatchDrawable? = fromCaps(context, bitmap, cap, cap, cap, cap, padding, cacheKey)

    @JvmStatic
    @JvmOverloads
    fun create(
        context: Context,
        bitmap: Bitmap,
        capLeft: Int,
        capTop: Int,
        capRight: Int,
        capBottom: Int,
        padding: Rect = Rect(),
        cacheKey: Any? = capKey(bitmap, capLeft, capTop, capRight, capBottom),
    ): NinePatchDrawable? = fromCaps(context, bitmap, capLeft, capTop, capRight, capBottom, padding, cacheKey)

    @JvmStatic
    @JvmOverloads
    fun load(view: View, url: String, asBackground: Boolean = true) {
        if (url.isEmpty()) return
        view.setTag(TAG_KEY_LOAD_URL, url)
        hit(view.context, url)?.let { applyDrawable(view, it, asBackground); return }
        internalLoad(view, url, asBackground, 0) { create(view.context, it, cacheKey = url) }
    }

    @JvmStatic
    @JvmOverloads
    fun load(view: View, url: String, cap: Int, asBackground: Boolean = true) {
        if (url.isEmpty()) return
        val key = "$url|$cap|$cap|$cap|$cap"
        view.setTag(TAG_KEY_LOAD_URL, url)
        hit(view.context, key)?.let { applyDrawable(view, it, asBackground); return }
        internalLoad(view, url, asBackground, 0) { create(view.context, it, cap, Rect(), key) }
    }

    @JvmStatic
    @JvmOverloads
    fun load(
        view: View,
        url: String,
        capLeft: Int,
        capTop: Int,
        capRight: Int,
        capBottom: Int,
        asBackground: Boolean = true,
    ) {
        if (url.isEmpty()) return
        val key = "$url|$capLeft|$capTop|$capRight|$capBottom"
        view.setTag(TAG_KEY_LOAD_URL, url)
        hit(view.context, key)?.let { applyDrawable(view, it, asBackground); return }
        internalLoad(view, url, asBackground, 0) {
            create(view.context, it, capLeft, capTop, capRight, capBottom, Rect(), key)
        }
    }

    /**
     * 完整版：带 padding（文字安全区）和 sourceDensity（设计稿 dpi）。
     * 用法示例（聊天气泡）：
     *   NinePatchUtil.load(view, url, 87, 63, 87, 63, Rect(20, 30, 20, 30), DisplayMetrics.DENSITY_440)
     *
     * @param padding 文字安全区 [left, top, right, bottom]，PNG 像素
     * @param sourceDensity 设计稿基准密度，0 = 不设
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        view: View,
        url: String,
        capLeft: Int,
        capTop: Int,
        capRight: Int,
        capBottom: Int,
        padding: Rect,
        sourceDensity: Int,
        asBackground: Boolean = true,
    ) {
        if (url.isEmpty()) return
        val key = "$url|$capLeft|$capTop|$capRight|$capBottom|" +
                "${padding.left}|${padding.top}|${padding.right}|${padding.bottom}|$sourceDensity"
        view.setTag(TAG_KEY_LOAD_URL, url)
        hit(view.context, key)?.let { applyDrawable(view, it, asBackground); return }
        internalLoad(view, url, asBackground, sourceDensity) {
            create(view.context, it, capLeft, capTop, capRight, capBottom, padding, key)
        }
    }

    private fun hit(context: Context, key: Any): NinePatchDrawable? =
        cache.get(key)?.takeIf { !it.content.isRecycled }?.let {
            NinePatchDrawable(context.resources, it.content, it.chunk, it.padding, null)
        }

    private fun applyDrawable(view: View, d: NinePatchDrawable, asBackground: Boolean) {
        if (asBackground) view.background = d
        else if (view is ImageView) view.setImageDrawable(d)
    }

    private fun fromCaps(
        context: Context,
        bitmap: Bitmap,
        capL: Int, capT: Int, capR: Int, capB: Int,
        padding: Rect,
        cacheKey: Any?,
    ): NinePatchDrawable? {
        cacheKey?.let { k -> hit(context, k)?.let { return it } }
        if (capL < 0 || capT < 0 || capR < 0 || capB < 0) return null
        if (capL + capR >= bitmap.width || capT + capB >= bitmap.height) return null

        val xs = intArrayOf(capL, bitmap.width - capR)
        val ys = intArrayOf(capT, bitmap.height - capB)
        val chunk = buildChunk(xs, ys, padding)
        if (!NinePatch.isNinePatchChunk(chunk)) return null
        cacheKey?.let { cache.put(it, Entry(chunk, padding, bitmap)) }
        return NinePatchDrawable(context.resources, bitmap, chunk, padding, null)
    }

    /**
     * tag 由 load 入口统一设置（含同步命中分支），此处仅校验。
     * dontTransform() 必加：否则 Glide 默认变换可能改变 bitmap 尺寸，导致 chunk px 失准。
     */
    private inline fun internalLoad(
        view: View,
        url: String,
        asBackground: Boolean,
        sourceDensity: Int,
        crossinline produce: (Bitmap) -> NinePatchDrawable?,
    ) {
        Glide.with(view).asBitmap()
            .dontTransform()
            .override(Target.SIZE_ORIGINAL)
            .load(url)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(r: Bitmap, t: Transition<in Bitmap>?) {
                    if (view.getTag(TAG_KEY_LOAD_URL) != url) return  // holder 已被复用到别条
                    if (sourceDensity > 0) r.density = sourceDensity
                    val d = produce(r) ?: return
                    applyDrawable(view, d, asBackground)
                }
                override fun onLoadCleared(p: Drawable?) {}
            })
    }

    private fun capKey(bitmap: Bitmap, capL: Int, capT: Int, capR: Int, capB: Int): String =
        "${System.identityHashCode(bitmap)}|$capL|$capT|$capR|$capB"

    private fun scanRaw9Patch(bitmap: Bitmap): Entry? {
        if (bitmap.width < 3 || bitmap.height < 3) return null
        val xDivs = scanDivs(1, bitmap.width - 1) { bitmap[it, 0] }
        val yDivs = scanDivs(1, bitmap.height - 1) { bitmap[0, it] }
        if (xDivs.isEmpty() || yDivs.isEmpty() || xDivs.size % 2 != 0 || yDivs.size % 2 != 0) return null

        val padX = scanDivs(1, bitmap.width - 1) { bitmap[it, bitmap.height - 1] }
        val padY = scanDivs(1, bitmap.height - 1) { bitmap[bitmap.width - 1, it] }

        val content = Bitmap.createBitmap(bitmap, 1, 1, bitmap.width - 2, bitmap.height - 2)
        val xs = IntArray(xDivs.size) { xDivs[it] - 1 }
        val ys = IntArray(yDivs.size) { yDivs[it] - 1 }
        val padding = Rect(
            if (padX.size >= 2) padX[0] - 1 else 0,
            if (padY.size >= 2) padY[0] - 1 else 0,
            if (padX.size >= 2) content.width - (padX.last() - 1) else 0,
            if (padY.size >= 2) content.height - (padY.last() - 1) else 0,
        )
        val chunk = buildChunk(xs, ys, padding)
        return if (NinePatch.isNinePatchChunk(chunk)) Entry(chunk, padding, content) else null
    }

    private inline fun scanDivs(start: Int, endExclusive: Int, pixelAt: (Int) -> Int): IntArray {
        val out = ArrayList<Int>(4)
        var inBlack = false
        for (i in start until endExclusive) {
            val black = pixelAt(i) == Color.BLACK
            if (black && !inBlack) { out.add(i); inBlack = true }
            else if (!black && inBlack) { out.add(i); inBlack = false }
        }
        if (inBlack) out.add(endExclusive)
        return out.toIntArray()
    }

    private fun buildChunk(xDivs: IntArray, yDivs: IntArray, padding: Rect): ByteArray {
        val numColors = (xDivs.size / 2 + 1) * (yDivs.size / 2 + 1)
        val size = 32 + 4 * xDivs.size + 4 * yDivs.size + 4 * numColors
        val bb = ByteBuffer.allocate(size).order(ByteOrder.nativeOrder())
        bb.put(1)
        bb.put(xDivs.size.toByte())
        bb.put(yDivs.size.toByte())
        bb.put(numColors.toByte())
        bb.putInt(0); bb.putInt(0)
        bb.putInt(padding.left); bb.putInt(padding.right)
        bb.putInt(padding.top); bb.putInt(padding.bottom)
        bb.putInt(0)
        xDivs.forEach(bb::putInt)
        yDivs.forEach(bb::putInt)
        repeat(numColors) { bb.putInt(NO_COLOR) }
        return bb.array()
    }
}
