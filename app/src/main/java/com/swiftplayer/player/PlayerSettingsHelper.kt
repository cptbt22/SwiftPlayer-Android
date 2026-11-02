package com.swiftplayer.player

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.WindowManager
import android.widget.Toast
import androidx.media3.ui.PlayerView
import java.io.File
import java.io.FileOutputStream

class PlayerSettingsHelper(
    private val activity: Activity,
    private val playerView: PlayerView,
    private val playerEngine: PlayerEngine
) {
    // 画面比例模式 (使用 PlayerView 的 resizeMode 常量)
    private val zoomModes = listOf(
        "适应" to 0,  // RESIZE_MODE_FIT
        "填充" to 4,  // RESIZE_MODE_ZOOM
        "拉伸" to 1,  // RESIZE_MODE_FILL
        "16:9" to 2,  // RESIZE_MODE_FIXED_WIDTH
        "4:3" to 3    // RESIZE_MODE_FIXED_HEIGHT
    )
    private var currentZoomIndex = 0

    var isLocked = false
        private set

    fun showSpeedSelector(currentSpeed: Float, onSpeedSelected: (Float) -> Unit) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        val currentIndex = speeds.indexOfFirst { Math.abs(it - currentSpeed) < 0.01f }
            .coerceAtLeast(0)

        android.app.AlertDialog.Builder(activity)
            .setTitle("播放速度")
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                onSpeedSelected(speeds[which])
                dialog.dismiss()
            }
            .show()
    }

    fun toggleLock(): Boolean {
        isLocked = !isLocked
        if (isLocked) {
            // 锁屏时保持常亮；控制层显隐由 PlayerControlsController 接管（解耦 useController）
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        return isLocked
    }

    fun cycleZoomMode(): String {
        currentZoomIndex = (currentZoomIndex + 1) % zoomModes.size
        val (label, mode) = zoomModes[currentZoomIndex]
        playerView.resizeMode = mode
        return label
    }

    fun captureScreenshot(uri: Uri, title: String) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(activity, uri)
            val position = playerEngine.currentPosition * 1000
            val bitmap = retriever.getScaledFrameAtTime(
                position, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1920, 1080
            )
            retriever.release()

            if (bitmap != null) {
                saveBitmap(bitmap, title)
                Toast.makeText(activity, "截图已保存", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "截图失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "截图失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveBitmap(bitmap: Bitmap, title: String) {
        val filename = "SwiftPlayer_${title}_${System.currentTimeMillis()}.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SwiftPlayer")
            }
            val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                activity.contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
                }
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "SwiftPlayer")
            dir.mkdirs()
            FileOutputStream(File(dir, filename)).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
        }
        bitmap.recycle()
    }

    data class VideoInfo(
        val title: String, val path: String, val size: String,
        val duration: String, val resolution: String, val mimeType: String
    )

    fun getVideoInfo(uri: Uri, title: String, fileSize: Long = 0): VideoInfo {
        var resolution = ""
        var mimeType = ""
        var durationStr = ""
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(activity, uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: ""
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: ""
            if (w.isNotEmpty() && h.isNotEmpty()) resolution = "${w}×${h}"
            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            durationStr = formatDuration(dur)
            retriever.release()
        } catch (_: Exception) {}

        return VideoInfo(title, uri.path ?: uri.toString(), formatFileSize(fileSize), durationStr, resolution, mimeType)
    }

    fun showVideoInfo(info: VideoInfo) {
        val msg = buildString {
            append("文件名：${info.title}\n\n路径：${info.path}\n\n大小：${info.size}\n")
            if (info.resolution.isNotEmpty()) append("分辨率：${info.resolution}\n")
            if (info.duration.isNotEmpty()) append("时长：${info.duration}\n")
            if (info.mimeType.isNotEmpty()) append("格式：${info.mimeType}")
        }
        android.app.AlertDialog.Builder(activity).setTitle("视频信息").setMessage(msg).setPositiveButton("确定", null).show()
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%02d:%02d", m, sec)
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
