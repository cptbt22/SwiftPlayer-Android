package com.swiftplayer.ui.components

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftplayer.R
import com.swiftplayer.data.emby.EmbyServer
import com.swiftplayer.databinding.ItemEmbyServerBinding

/** Emby 服务器列表适配器 */
class EmbyServerAdapter(
    private val onServerClick: (EmbyServer) -> Unit,
    private val onServerDelete: (EmbyServer) -> Unit
) : ListAdapter<EmbyServer, EmbyServerAdapter.ServerViewHolder>(ServerDiff()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemEmbyServerBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ServerViewHolder(
        private val binding: ItemEmbyServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(server: EmbyServer) {
            binding.embyServerName.text = server.name
            binding.embyServerAddress.text = server.baseUrl
            binding.btnDeleteServer.visibility = View.VISIBLE
            binding.btnDeleteServer.setOnClickListener { onServerDelete(server) }
            binding.root.setOnClickListener { onServerClick(server) }
            binding.root.setOnLongClickListener {
                onServerDelete(server)
                true
            }
        }
    }

    class ServerDiff : DiffUtil.ItemCallback<EmbyServer>() {
        override fun areItemsTheSame(oldItem: EmbyServer, newItem: EmbyServer) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: EmbyServer, newItem: EmbyServer) = oldItem == newItem
    }
}
