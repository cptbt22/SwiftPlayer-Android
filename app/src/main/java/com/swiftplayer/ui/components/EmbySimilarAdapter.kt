package com.swiftplayer.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.swiftplayer.data.emby.EmbyItem
import com.swiftplayer.databinding.ItemEmbySimilarBinding

/**
 * 详情页「相似影片」横向适配器
 * - 海报固定 override(220, 330)（约 2:3）
 * - onViewRecycled 清理 Glide，避免横向滚动时的内存抖动
 */
class EmbySimilarAdapter(
    private val onClick: (EmbyItem) -> Unit,
    private val urlBuilder: (EmbyItem) -> String?
) : ListAdapter<EmbyItem, EmbySimilarAdapter.SimilarViewHolder>(SimilarDiff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SimilarViewHolder {
        val binding = ItemEmbySimilarBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SimilarViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SimilarViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: SimilarViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.binding.similarPoster).clear(holder.binding.similarPoster)
    }

    inner class SimilarViewHolder(
        val binding: ItemEmbySimilarBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: EmbyItem) {
            binding.similarTitle.text = item.name
            Glide.with(binding.similarPoster)
                .load(urlBuilder(item))
                .override(220, 330)
                .centerCrop()
                .into(binding.similarPoster)
            binding.root.setOnClickListener { onClick(item) }
        }
    }

    class SimilarDiff : DiffUtil.ItemCallback<EmbyItem>() {
        override fun areItemsTheSame(oldItem: EmbyItem, newItem: EmbyItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: EmbyItem, newItem: EmbyItem) = oldItem == newItem
    }
}
