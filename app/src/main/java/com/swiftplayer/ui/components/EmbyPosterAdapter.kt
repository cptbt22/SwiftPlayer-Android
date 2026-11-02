package com.swiftplayer.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.swiftplayer.data.emby.EmbyItem
import com.swiftplayer.data.model.VideoItem
import com.swiftplayer.databinding.ItemEmbyPosterBinding

/**
 * Emby 海报墙网格适配器（3 列）
 * - 海报走 Glide，固定尺寸 override(300, 450)（R6：避免多份缓存）
 * - onViewRecycled 清理请求
 */
class EmbyPosterAdapter(
    private val onClick: (EmbyItem) -> Unit,
    private val urlBuilder: (EmbyItem) -> String?
) : ListAdapter<EmbyItem, EmbyPosterAdapter.PosterViewHolder>(PosterDiff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PosterViewHolder {
        val binding = ItemEmbyPosterBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return PosterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PosterViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: PosterViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.binding.embyPoster).clear(holder.binding.embyPoster)
    }

    inner class PosterViewHolder(
        val binding: ItemEmbyPosterBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: EmbyItem) {
            binding.embyPosterTitle.text = item.name
            binding.embyPosterSubtitle.text = buildSubtitle(item)

            Glide.with(binding.embyPoster)
                .load(urlBuilder(item))
                .override(300, 450)
                .centerCrop()
                .into(binding.embyPoster)

            // 播放进度条：有观看进度的影片在海报底边显示百分比（v2.5.2）
            val percent = item.playedPercentage.toInt().coerceIn(0, 100)
            if (percent > 0) {
                binding.embyPosterProgress.visibility = android.view.View.VISIBLE
                binding.embyPosterProgress.progress = percent
            } else {
                binding.embyPosterProgress.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener { onClick(item) }
        }

        private fun buildSubtitle(item: EmbyItem): String {
            val parts = mutableListOf<String>()
            if (item.productionYear > 0) parts.add(item.productionYear.toString())
            if (item.durationMs > 0) parts.add(VideoItem.formatDuration(item.durationMs))
            return parts.joinToString(" · ")
        }
    }

    class PosterDiff : DiffUtil.ItemCallback<EmbyItem>() {
        override fun areItemsTheSame(oldItem: EmbyItem, newItem: EmbyItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: EmbyItem, newItem: EmbyItem) = oldItem == newItem
    }
}
