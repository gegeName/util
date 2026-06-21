package com.chat.mylibrary.html

import android.graphics.drawable.Drawable

/**
 * 富文本媒体加载接口。
 *
 * HtmlTextView 不绑定具体图片库，由业务方注入实现（Glide / Coil 等）。
 * 两个方法均在主线程被调用，实现方完成加载后必须在【主线程】回调。
 * 回调返回的 [Drawable] 需带有正确的 intrinsicWidth / intrinsicHeight，
 * HtmlTextView 依据其固有尺寸决定图片是独占一行还是与文字同行。
 */
interface HtmlMediaLoader {

    /**
     * 加载普通图片。
     *
     * @param url      图片地址
     * @param maxWidth 控件当前可用宽度（px），便于实现方做尺寸约束 / 降采样；可能为 0（控件尚未测量）
     * @param callback 加载结果回调，失败回调 null
     */
    fun loadImage(url: String, maxWidth: Int, callback: (Drawable?) -> Unit)

    /**
     * 加载视频首帧。
     *
     * @param url      视频地址
     * @param maxWidth 控件当前可用宽度（px）
     * @param callback 首帧结果回调，失败回调 null
     */
    fun loadVideoFrame(url: String, maxWidth: Int, callback: (Drawable?) -> Unit)
}
