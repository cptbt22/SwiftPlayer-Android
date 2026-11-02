package com.swiftplayer.data.model

import android.net.Uri

data class VideoItem(
    val id: Long,
    val title: String,
    val path: String,
    val uri: Uri,
    val duration: Long,      // 毫秒
    val size: Long,          // 字节
    val width: Int = 0,
    val height: Int = 0,
    val mimeType: String = "video/*",
    val dateAdded: Long = 0,
    val thumbnailUri: Uri? = null,
    /** v2.5.0：SMB 浏览时目录也作为条目返回，true 表示可进入的子目录 */
    val isDirectory: Boolean = false
) {
    val durationText: String
        get() = formatDuration(duration)

    val sizeText: String
        get() = formatFileSize(size)

    val resolutionText: String
        get() = if (width > 0 && height > 0) "${width}×${height}" else ""

    companion object {
        fun formatDuration(ms: Long): String {
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }

        fun formatFileSize(bytes: Long): String {
            return when {
                bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
                bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
                bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
                else -> "$bytes B"
            }
        }
    }
}
