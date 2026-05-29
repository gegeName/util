package com.chat.picker.ui

import com.chat.picker.model.MediaEntity

/** 预览页与列表页之间共享当前展示的列表，避免 Intent 传超大 Parcel */
internal object PreviewBridge {
    var previewList: List<MediaEntity> = emptyList()
}
