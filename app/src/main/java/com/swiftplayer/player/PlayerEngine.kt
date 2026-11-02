package com.swiftplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.swiftplayer.data.model.SmbServer
import com.swiftplayer.smb.SmbConnectionProvider
import com.swiftplayer.smb.SmbDataSource
import okhttp3.OkHttpClient

/**
 * 播放引擎封装
 * - 本地：file:// / content://
 * - 局域网：smb://（SmbDataSource 随机读取，连接复用）
 * - Emby 网络流：http(s)://，经 OkHttp 数据源（统一超时/连接池）
 */
class PlayerEngine(private val context: Context) {

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var listener: Player.Listener? = null
    private var smbServer: SmbServer? = null
    private var smbProvider: SmbConnectionProvider? = null

    private val okHttpClient: OkHttpClient by lazy { OkHttpClient.Builder().build() }

    /**
     * 网络缓冲策略：
     * - 15s 起播 / 60s 上限，降低 seek 后等待与内存压力；
     * - 45s 回看缓冲，快进过头往回拖时尽量命中已缓冲区间。
     */
    private val loadControl: LoadControl by lazy {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 800, 1_500)
            .setBackBuffer(45_000, true)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
    }

    /** 默认数据源：file/content 本地，http(s) 经 OkHttp */
    private val defaultDataSourceFactory: DataSource.Factory by lazy {
        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient))
    }

    val currentPosition: Long
        get() = player?.currentPosition ?: 0

    val duration: Long
        get() = player?.duration ?: 0

    val isPlaying: Boolean
        get() = player?.isPlaying ?: false

    val playbackSpeed: Float
        get() = player?.playbackParameters?.speed ?: 1f

    fun create(): ExoPlayer {
        // 不再限制最大视频分辨率（移除历史 SD 上限）：交给设备能力自动选择最佳轨道
        trackSelector = DefaultTrackSelector(context)

        return ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector!!)
            .setMediaSourceFactory(DefaultMediaSourceFactory(defaultDataSourceFactory))
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { player = it }
    }

    fun getPlayer(): ExoPlayer? = player

    fun setListener(listener: Player.Listener) {
        this.listener = listener
        player?.addListener(listener)
    }

    /** SMB 播放前注入服务器信息（URI 中只有共享名与路径）。 */
    fun setSmbServer(server: SmbServer?) {
        if (smbServer == server) return
        smbServer = server
        if (server == null) {
            smbProvider?.release()
            smbProvider = null
        }
    }

    /**
     * 播放指定 URI。
     * @param headers 可选请求头（非空时使用独立的 OkHttp 数据源注入）
     * @param subtitleConfigs 可选外挂字幕配置（side-load，如 Emby 字幕流）
     */
    fun play(
        uri: Uri,
        title: String = "",
        headers: Map<String, String> = emptyMap(),
        subtitleConfigs: List<MediaItem.SubtitleConfiguration> = emptyList()
    ) {
        val builder = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .build()
            )
        if (subtitleConfigs.isNotEmpty()) {
            builder.setSubtitleConfigurations(subtitleConfigs)
        }
        val mediaItem = builder.build()

        val factory: DataSource.Factory = when {
            uri.scheme == "smb" -> {
                val server = smbServer
                    ?: throw IllegalArgumentException("SMB playback requires server credentials")
                val provider = smbProvider ?: SmbConnectionProvider(server).also {
                    smbProvider = it
                }
                SmbDataSource.Factory(provider)
            }
            headers.isEmpty() -> defaultDataSourceFactory
            else -> DefaultDataSource.Factory(
                context,
                OkHttpDataSource.Factory(okHttpClient).setDefaultRequestProperties(headers)
            )
        }
        val mediaSource = DefaultMediaSourceFactory(factory).createMediaSource(mediaItem)

        // 网络流用 CLOSEST_SYNC：seek 直接落到最近关键帧，明显降低拖动后的等待；本地保持精确 seek。
        val seekParams = when (uri.scheme) {
            "http", "https", "smb" -> SeekParameters.CLOSEST_SYNC
            else -> SeekParameters.EXACT
        }

        player?.apply {
            setSeekParameters(seekParams)
            setMediaSource(mediaSource)
            prepare()
            play()
        }
    }

    fun playPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekTo(position: Long) {
        player?.seekTo(position)
    }

    fun seekRelative(deltaMs: Long) {
        player?.let {
            val dur = it.duration
            val target = if (dur > 0) {
                (it.currentPosition + deltaMs).coerceIn(0, dur)
            } else {
                (it.currentPosition + deltaMs).coerceAtLeast(0)
            }
            it.seekTo(target)
        }
    }

    fun setSpeed(speed: Float) {
        player?.playbackParameters = PlaybackParameters(speed)
    }

    fun release() {
        listener?.let { player?.removeListener(it) }
        player?.release()
        player = null
        trackSelector = null
        smbProvider?.release()
        smbProvider = null
    }
}
