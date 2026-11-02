package com.swiftplayer.ui.components

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftplayer.R
import com.swiftplayer.data.model.SmbServer
import com.swiftplayer.databinding.ItemSmbServerBinding

class SmbServerAdapter(
    private val onServerClick: (SmbServer) -> Unit,
    private val onAddClick: () -> Unit
) : ListAdapter<SmbServer, SmbServerAdapter.ServerViewHolder>(ServerDiffCallback()) {

    var showAddButton: Boolean = true

    override fun getItemCount(): Int {
        return if (showAddButton) currentList.size + 1 else currentList.size
    }

    override fun getItemViewType(position: Int): Int {
        return if (position >= currentList.size) TYPE_ADD else TYPE_SERVER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemSmbServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        if (position < currentList.size) {
            holder.bindServer(getItem(position))
        } else {
            holder.bindAdd()
        }
    }

    inner class ServerViewHolder(
        private val binding: ItemSmbServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bindServer(server: SmbServer) {
            binding.serverName.text = server.name
            binding.serverAddress.text = server.displayAddress
            binding.icon.setImageResource(R.drawable.ic_server)
            binding.btnDelete.visibility = android.view.View.GONE
            binding.root.setOnClickListener { onServerClick(server) }
        }

        fun bindAdd() {
            binding.serverName.text = "手动添加服务器"
            binding.serverAddress.text = "输入 SMB 地址连接"
            binding.icon.setImageResource(R.drawable.ic_add)
            binding.btnDelete.visibility = android.view.View.GONE
            binding.root.setOnClickListener { onAddClick() }
        }
    }

    companion object {
        private const val TYPE_SERVER = 0
        private const val TYPE_ADD = 1
    }
}

class ServerDiffCallback : DiffUtil.ItemCallback<SmbServer>() {
    override fun areItemsTheSame(oldItem: SmbServer, newItem: SmbServer): Boolean {
        return oldItem.address == newItem.address
    }

    override fun areContentsTheSame(oldItem: SmbServer, newItem: SmbServer): Boolean {
        return oldItem == newItem
    }
}
