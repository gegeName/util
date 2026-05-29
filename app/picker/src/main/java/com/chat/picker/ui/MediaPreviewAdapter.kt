package com.chat.picker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.MediaController
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.chat.picker.R
import com.chat.picker.api.MediaSelector
import com.chat.picker.model.MediaEntity
import com.chat.picker.util.ZoomGestureHelper

internal class MediaPreviewAdapter
    : ListAdapter<MediaEntity, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_IMAGE = 1
        private const val TYPE_VIDEO = 2

        private val DIFF = object : DiffUtil.ItemCallback<MediaEntity>() {
            override fun areItemsTheSame(oldItem: MediaEntity, newItem: MediaEntity): Boolean =
                oldItem.id == newItem.id && oldItem.mediaType == newItem.mediaType

            override fun areContentsTheSame(oldItem: MediaEntity, newItem: MediaEntity): Boolean =
                oldItem == newItem
        }
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position).isVideo) TYPE_VIDEO else TYPE_IMAGE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VIDEO) {
            VideoVH(inflater.inflate(R.layout.picker_page_video, parent, false))
        } else {
            ImageVH(inflater.inflate(R.layout.picker_page_image, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ImageVH -> holder.bind(item)
            is VideoVH -> holder.bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is VideoVH -> holder.release()
            is ImageVH -> holder.release()
        }
    }

    private inner class ImageVH(v: View) : RecyclerView.ViewHolder(v) {
        val image: ImageView = v.findViewById(R.id.page_image)
        private val loading: ProgressBar = v.findViewById(R.id.page_loading)

        init {
            ZoomGestureHelper.attach(image)
        }

        fun bind(item: MediaEntity) {
            loading.visibility = View.GONE
            MediaSelector.imageEngine().loadOriginal(image, item.uri, item.isVideo)
        }

        fun release() {
            image.setImageDrawable(null)
        }
    }

    private inner class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumb: ImageView = v.findViewById(R.id.page_video_thumb)
        private val video: VideoView = v.findViewById(R.id.page_video)
        private val play: ImageView = v.findViewById(R.id.page_play)
        private val loading: ProgressBar = v.findViewById(R.id.page_video_loading)
        private var controller: MediaController? = null
        private var prepared: Boolean = false

        fun bind(item: MediaEntity) {
            val ctx = itemView.context
            prepared = false
            thumb.visibility = View.VISIBLE
            play.visibility = View.VISIBLE
            loading.visibility = View.GONE
            MediaSelector.imageEngine().loadThumbnail(thumb, item.uri, true)

            val mc = MediaController(ctx).also { controller = it }
            mc.setAnchorView(video)
            video.setMediaController(mc)

            video.setOnPreparedListener { mp ->
                prepared = true
                mp.setOnVideoSizeChangedListener { _, _, _ -> video.requestLayout() }
                mp.setOnInfoListener { _, what, _ ->
                    when (what) {
                        android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                            loading.visibility = View.GONE
                            thumb.visibility = View.GONE
                        }

                        android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            loading.visibility = View.VISIBLE
                        }

                        android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            loading.visibility = View.GONE
                        }
                    }
                    false
                }
                video.start()
            }
            video.setOnErrorListener { _, _, _ ->
                prepared = false
                loading.visibility = View.GONE
                thumb.visibility = View.VISIBLE
                play.visibility = View.VISIBLE
                runCatching { video.stopPlayback() }
                true
            }
            video.setOnCompletionListener {
                thumb.visibility = View.VISIBLE
                play.visibility = View.VISIBLE
            }
            play.setOnClickListener {
                play.visibility = View.GONE
                if (prepared) {
                    video.start()
                } else {
                    loading.visibility = View.VISIBLE
                    runCatching { video.setVideoURI(item.uri) }
                }
            }
        }

        fun release() {
            prepared = false
            runCatching { video.stopPlayback() }
            video.setOnPreparedListener(null)
            video.setOnErrorListener(null)
            video.setOnCompletionListener(null)
            thumb.setImageDrawable(null)
            controller = null
        }
    }
}
