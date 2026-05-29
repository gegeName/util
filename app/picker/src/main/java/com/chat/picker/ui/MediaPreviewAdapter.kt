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

        fun bind(item: MediaEntity) {
            loading.visibility = View.GONE
            MediaSelector.imageEngine().loadOriginal(image, item.uri, item.isVideo)
        }

        fun release() {
            image.setImageDrawable(null)
        }
    }

    private inner class VideoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val video: VideoView = v.findViewById(R.id.page_video)
        private val play: ImageView = v.findViewById(R.id.page_play)
        private var controller: MediaController? = null

        fun bind(item: MediaEntity) {
            val ctx = itemView.context
            val mc = MediaController(ctx).also { controller = it }
            mc.setAnchorView(video)
            video.setMediaController(mc)
            video.setVideoURI(item.uri)
            video.setOnPreparedListener { /* prepared */ }
            video.setOnCompletionListener { play.visibility = View.VISIBLE }
            play.setOnClickListener {
                play.visibility = View.GONE
                video.start()
            }
            play.visibility = View.VISIBLE
        }

        fun release() {
            runCatching { video.stopPlayback() }
            controller = null
        }
    }
}
