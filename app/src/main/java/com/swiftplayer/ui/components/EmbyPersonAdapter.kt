package com.swiftplayer.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.swiftplayer.data.emby.EmbyItem
import com.swiftplayer.data.emby.EmbyTag
import com.swiftplayer.databinding.ItemEmbyPersonBinding
import com.swiftplayer.databinding.ItemEmbyTagBinding

/**
 * Emby 演员/人物网格适配器（v2.5.0）。
 * 人物复用 [EmbyItem]（IncludeItemTypes=Person），头像走 Primary 图。
 */
class EmbyPersonAdapter(
    private val onClick: (EmbyItem) -> Unit,
    private val urlBuilder: (EmbyItem) -> String?
) : ListAdapter<EmbyItem, EmbyPersonAdapter.ViewHolder>(PersonDiff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEmbyPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        Glide.with(holder.itemView).clear(holder.binding.personAvatar)
    }

    inner class ViewHolder(val binding: ItemEmbyPersonBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(person: EmbyItem) {
            binding.personName.text = person.name
            val url = urlBuilder(person)
            Glide.with(binding.personAvatar)
                .load(url)
                .centerCrop()
                .into(binding.personAvatar)
            binding.root.setOnClickListener { onClick(person) }
        }
    }

    class PersonDiff : DiffUtil.ItemCallback<EmbyItem>() {
        override fun areItemsTheSame(oldItem: EmbyItem, newItem: EmbyItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: EmbyItem, newItem: EmbyItem) = oldItem == newItem
    }
}

/** Emby 标签列表适配器：条目类型为 data 层的 [EmbyTag]（v2.5.1 下沉） */
class EmbyTagAdapter(
    private val onClick: (EmbyTag) -> Unit
) : ListAdapter<EmbyTag, EmbyTagAdapter.ViewHolder>(TagDiff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEmbyTagBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemEmbyTagBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tag: EmbyTag) {
            binding.tagName.text = tag.name
            binding.tagCount.text = "${tag.count} 部"
            binding.root.setOnClickListener { onClick(tag) }
        }
    }

    class TagDiff : DiffUtil.ItemCallback<EmbyTag>() {
        override fun areItemsTheSame(oldItem: EmbyTag, newItem: EmbyTag) = oldItem.name == newItem.name
        override fun areContentsTheSame(oldItem: EmbyTag, newItem: EmbyTag) = oldItem == newItem
    }
}
