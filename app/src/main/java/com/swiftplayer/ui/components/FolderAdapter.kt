package com.swiftplayer.ui.components

import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.swiftplayer.data.local.BrowseItem
import com.swiftplayer.data.local.FolderItem
import com.swiftplayer.data.model.VideoItem
import com.swiftplayer.databinding.ItemFolderBinding
import com.swiftplayer.databinding.ItemVideoBinding
import java.io.File

class FolderBrowseAdapter(
    private val onFolderClick: (BrowseItem.Folder) -> Unit,
    private val onVideoClick: (VideoItem) -> Unit
) : ListAdapter<BrowseItem, RecyclerView.ViewHolder>(BrowseItemDiffCallback()) {

    companion object {
        private const val TYPE_FOLDER = 0
        private const val TYPE_VIDEO = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is BrowseItem.Folder -> TYPE_FOLDER
            is BrowseItem.Video -> TYPE_VIDEO
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FOLDER -> FolderViewHolder(
                ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
            else -> VideoViewHolder(
                ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is BrowseItem.Folder -> (holder as FolderViewHolder).bind(item)
            is BrowseItem.Video -> (holder as VideoViewHolder).bind(item)
        }
    }

    inner class FolderViewHolder(
        private val binding: ItemFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BrowseItem.Folder) {
            binding.folderName.text = item.file.name
            binding.videoCount.text = if (item.videoCount > 0) {
                "${item.videoCount} 个视频"
            } else {
                "空文件夹"
            }
            binding.root.setOnClickListener { onFolderClick(item) }
        }
    }

    inner class VideoViewHolder(
        private val binding: ItemVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: BrowseItem.Video) {
            val file = item.file
            val duration = getVideoDuration(file)
            val videoItem = VideoItem(
                id = file.absolutePath.hashCode().toLong(),
                title = file.nameWithoutExtension,
                path = file.absolutePath,
                uri = android.net.Uri.fromFile(file),
                duration = duration,
                size = file.length(),
                mimeType = "video/*",
                dateAdded = file.lastModified()
            )

            binding.title.text = videoItem.title
            binding.duration.text = videoItem.durationText
            binding.size.text = videoItem.sizeText
            binding.resolution.text = ""
            binding.path.text = videoItem.path
            binding.thumbnail.setImageResource(android.R.drawable.ic_media_play)

            binding.root.setOnClickListener { onVideoClick(videoItem) }
        }

        private fun getVideoDuration(file: File): Long {
            return try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(file.absolutePath)
                val d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                retriever.release()
                d
            } catch (_: Exception) {
                0L
            }
        }
    }
}

class FolderShortcutAdapter(
    private val onClick: (FolderItem) -> Unit
) : ListAdapter<FolderItem, FolderShortcutAdapter.ViewHolder>(FolderDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemFolderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: FolderItem) {
            binding.folderName.text = item.displayName
            binding.videoCount.text = if (item.videoCount > 0) {
                "${item.videoCount} 个视频"
            } else {
                "无视频"
            }
            binding.root.setOnClickListener { onClick(item) }
        }
    }
}

class BrowseItemDiffCallback : DiffUtil.ItemCallback<BrowseItem>() {
    override fun areItemsTheSame(oldItem: BrowseItem, newItem: BrowseItem): Boolean {
        return when {
            oldItem is BrowseItem.Folder && newItem is BrowseItem.Folder ->
                oldItem.file.absolutePath == newItem.file.absolutePath
            oldItem is BrowseItem.Video && newItem is BrowseItem.Video ->
                oldItem.file.absolutePath == newItem.file.absolutePath
            else -> false
        }
    }

    override fun areContentsTheSame(oldItem: BrowseItem, newItem: BrowseItem) = oldItem == newItem
}

class FolderDiffCallback : DiffUtil.ItemCallback<FolderItem>() {
    override fun areItemsTheSame(oldItem: FolderItem, newItem: FolderItem) =
        oldItem.file.absolutePath == newItem.file.absolutePath
    override fun areContentsTheSame(oldItem: FolderItem, newItem: FolderItem) = oldItem == newItem
}
