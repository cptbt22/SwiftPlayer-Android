package com.swiftplayer.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.swiftplayer.R
import com.swiftplayer.data.model.VideoItem
import com.swiftplayer.databinding.ItemVideoBinding

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VideoViewHolder>(VideoDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = ItemVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VideoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: VideoViewHolder) {
        super.onViewRecycled(holder)
        holder.clear()
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VideoItem) {
            binding.title.text = item.title
            if (item.isDirectory) {
                // 目录条目：文件夹图标居中（保留占位底），隐藏时长/分辨率/大小
                binding.thumbnail.setImageResource(R.drawable.ic_folder)
                binding.thumbnail.imageTintList =
                    android.content.res.ColorStateList.valueOf(
                        binding.root.context.getColor(R.color.primary)
                    )
                binding.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                binding.duration.text = ""
                binding.size.text = ""
                binding.resolution.text = ""
                binding.path.text = ""
            } else {
                binding.thumbnail.imageTintList = null
                binding.thumbnail.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                binding.duration.text = item.durationText
                binding.size.text = item.sizeText
                binding.resolution.text = item.resolutionText
                binding.path.text = item.path

                Glide.with(binding.thumbnail)
                    .load(item.thumbnailUri ?: item.uri)
                    .centerCrop()
                    .into(binding.thumbnail)
            }

            binding.root.setOnClickListener { onClick(item) }
        }

        fun clear() {
            Glide.with(binding.thumbnail).clear(binding.thumbnail)
        }
    }
}

class VideoDiffCallback : DiffUtil.ItemCallback<VideoItem>() {
    override fun areItemsTheSame(oldItem: VideoItem, newItem: VideoItem) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: VideoItem, newItem: VideoItem) = oldItem == newItem
}
