package com.swiftplayer.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftplayer.data.emby.EmbyView
import com.swiftplayer.databinding.ItemEmbyViewBinding

/** Emby 媒体库视图列表适配器 */
class EmbyViewAdapter(
    private val onViewClick: (EmbyView) -> Unit
) : ListAdapter<EmbyView, EmbyViewAdapter.ViewHolder>(ViewDiff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEmbyViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemEmbyViewBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(view: EmbyView) {
            binding.embyViewName.text = view.name
            binding.root.setOnClickListener { onViewClick(view) }
        }
    }

    class ViewDiff : DiffUtil.ItemCallback<EmbyView>() {
        override fun areItemsTheSame(oldItem: EmbyView, newItem: EmbyView) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: EmbyView, newItem: EmbyView) = oldItem == newItem
    }
}
