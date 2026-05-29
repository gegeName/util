package com.chat.picker.loader

import android.net.Uri
import android.widget.ImageView

/**
 * 图片加载引擎接口。外部可用 Glide/Coil/Picasso 等实现，
 * 未注入时使用内部 [DefaultImageEngine]（基于自带 ImageLoader）。
 *
 * 视频首帧：由实现方决定如何取（Glide/Coil 自带 VideoFrameDecoder，
 * 默认实现使用 MediaMetadataRetriever）。
 */
interface IImageEngine {
    /** 列表缩略图（小尺寸，center crop） */
    fun loadThumbnail(view: ImageView, uri: Uri, isVideo: Boolean)

    /** 预览大图（原图或较高分辨率，fit center） */
    fun loadOriginal(view: ImageView, uri: Uri, isVideo: Boolean)
}
