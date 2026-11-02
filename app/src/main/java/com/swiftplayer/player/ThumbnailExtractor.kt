package com.swiftplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ThumbnailExtractor(private val context: Context) {

    private var retriever: MediaMetadataRetriever? = null
    private var currentUri: Uri? = null
    private val mutex = Mutex()  // 线程安全

    fun setDataSource(uri: Uri) {
        if (currentUri == uri) return
        release()
        try {
            retriever = MediaMetadataRetriever().apply {
                setDataSource(context, uri)
            }
            currentUri = uri
        } catch (e: Exception) {
            retriever = null
            currentUri = null
        }
    }

    suspend fun getFrameAtTime(
        timeMs: Long,
        width: Int = 160,
        height: Int = 90
    ): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                retriever?.getScaledFrameAtTime(
                    timeMs * 1000,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    width,
                    height
                )
            } catch (_: Exception) {
                null
            }
        }
    }

    fun release() {
        try {
            retriever?.release()
        } catch (_: Exception) {}
        retriever = null
        currentUri = null
    }
}
