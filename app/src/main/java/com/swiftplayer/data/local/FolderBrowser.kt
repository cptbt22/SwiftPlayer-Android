package com.swiftplayer.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.provider.MediaStore
import android.net.Uri
import com.swiftplayer.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FolderBrowser(private val context: Context) {

    companion object {
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "3gp",
            "wmv", "asf", "ogv", "m4v", "mpg", "mpeg", "vob"
        )

        fun isVideoFile(file: File): Boolean {
            val ext = file.extension.lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }

    /**
     * 获取根目录列表（必须在 IO 线程调用）
     *
     * @param showHidden true 时展示以「.」开头的隐藏目录（D5：默认显示）
     */
    suspend fun getRootFolders(showHidden: Boolean = true): List<FolderItem> = withContext(Dispatchers.IO) {
        val folders = mutableListOf<FolderItem>()

        val internal = Environment.getExternalStorageDirectory()
        if (internal.exists() && internal.canRead()) {
            folders.add(FolderItem(internal, "内部存储", countVideosQuick(internal)))
        }

        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        if (dcim.exists() && dcim.canRead()) {
            folders.add(FolderItem(dcim, "相机 (DCIM)", countVideosQuick(dcim)))
        }

        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (movies.exists() && movies.canRead()) {
            folders.add(FolderItem(movies, "视频 (Movies)", countVideosQuick(movies)))
        }

        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (download.exists() && download.canRead()) {
            folders.add(FolderItem(download, "下载 (Download)", countVideosQuick(download)))
        }

        val rootDirs = internal.listFiles()
            ?.filter { it.isDirectory && it.canRead() && (showHidden || !it.name.startsWith(".")) }
            ?.sortedByDescending { it.lastModified() }
            ?.take(20)
            ?: emptyList()

        for (dir in rootDirs) {
            if (folders.none { it.file.absolutePath == dir.absolutePath }) {
                val count = countVideosQuick(dir)
                if (count > 0) {
                    folders.add(FolderItem(dir, dir.name, count))
                }
            }
        }

        folders
    }

    suspend fun browseFolder(path: File, showHidden: Boolean = true): BrowseResult = withContext(Dispatchers.IO) {
        val items = mutableListOf<BrowseItem>()

        try {
            val files = path.listFiles() ?: return@withContext BrowseResult(emptyList(), 0)

            val dirs = files.filter { it.isDirectory && it.canRead() && (showHidden || !it.name.startsWith(".")) }
                .sortedBy { it.name.lowercase() }

            for (dir in dirs) {
                val count = countVideosQuick(dir)
                items.add(BrowseItem.Folder(dir, count))
            }

            val videos = files.filter { it.isFile && isVideoFile(it) && it.canRead() && it.length() > 0 }
                .sortedByDescending { it.lastModified() }

            for (video in videos) {
                items.add(BrowseItem.Video(video))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val videoCount = items.count { it is BrowseItem.Video }
        BrowseResult(items, videoCount)
    }

    /**
     * 快速统计视频数量（仅检查扩展名，不读元数据）
     */
    private fun countVideosQuick(dir: File): Int {
        return try {
            dir.listFiles()?.count { it.isFile && isVideoFile(it) && it.length() > 0 } ?: 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * File → VideoItem，尝试提取时长
     */
    fun fileToVideoItem(file: File): VideoItem {
        val duration = getVideoDuration(file)
        val uri = Uri.fromFile(file)
        return VideoItem(
            id = file.absolutePath.hashCode().toLong(),
            title = file.nameWithoutExtension,
            path = file.absolutePath,
            uri = uri,
            duration = duration,
            size = file.length(),
            mimeType = getMimeType(file),
            dateAdded = file.lastModified()
        )
    }

    /**
     * 提取视频时长（毫秒），失败返回 0
     */
    private fun getVideoDuration(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.release()
            duration
        } catch (_: Exception) {
            0L
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "flv" -> "video/x-flv"
            "ts" -> "video/mp2t"
            else -> "video/*"
        }
    }
}

data class FolderItem(
    val file: File,
    val displayName: String,
    val videoCount: Int
)

sealed class BrowseItem {
    data class Folder(val file: File, val videoCount: Int) : BrowseItem()
    data class Video(val file: File) : BrowseItem()
}

data class BrowseResult(
    val items: List<BrowseItem>,
    val videoCount: Int
)
