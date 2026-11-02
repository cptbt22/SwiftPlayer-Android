package com.swiftplayer.data.model

/** 媒体来源类型 */
enum class SourceType { LOCAL, SMB, EMBY }

/**
 * 三源统一媒体条目
 * 以 [sourceType] 区分 LOCAL / SMB / EMBY，承载「可导航元数据 + 可播放地址」。
 */
data class MediaEntry(
    val id: String,                       // 唯一键：local=path / smb=uri / emby=itemId
    val title: String,
    val playUri: String,                  // file:// | smb:// | http(s)://（Emby 带 api_key）
    val sourceType: SourceType,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String = "video/*",
    val posterUrl: String? = null,        // Emby Primary 图
    val backdropUrl: String? = null,      // Emby Backdrop 图
    val overview: String? = null,
    val year: Int = 0,
    val rating: Double = 0.0,
    val embyItemId: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()  // 预留
)
