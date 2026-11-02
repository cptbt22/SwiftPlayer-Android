package com.swiftplayer.data.emby

/**
 * Emby 服务器（含认证信息）
 * @param id 由 baseUrl + userId 派生，保证唯一
 * @param lanBaseUrl 局域网地址（可选）；非空时优先探测，可达即走局域网直连（更快）
 */
data class EmbyServer(
    val id: String,
    val name: String,               // 备注名（空则取 host）
    val baseUrl: String,            // 规范化后的 http(s)://host:port（主地址，外网/通用）
    val lanBaseUrl: String = "",    // 局域网地址（可选，如 http://192.168.1.100:8096）
    val userId: String = "",
    val accessToken: String = "",
    val lastConnected: Long = 0L
)

/** 认证会话 */
data class EmbySession(
    val userId: String,
    val accessToken: String,
    val userName: String
)

/** 媒体库视图 */
data class EmbyView(val id: String, val name: String)

/** 类型/标签条目（名称 + 命中影片数），data 层定义供 Repository 聚合输出 */
data class EmbyTag(val name: String, val count: Int)

/**
 * 影片关联人物（Fields=People 时随影片返回，v2.5.1）。
 * 客户端聚合出「确实出现在现有影片中」的人物——服务器全库 Person 列表
 * 会混入已删影片的残留实体与导演/编剧等非演员。
 */
data class EmbyPerson(
    val id: String,
    val name: String,
    val role: String = "",
    val type: String = "",
    /** 人物头像 tag（空 = 服务器无此人物图片） */
    val imageTag: String? = null
)

/** 影片/剧集条目 */
data class EmbyItem(
    val id: String,
    val name: String,
    val runTimeTicks: Long = 0L,
    val productionYear: Int = 0,
    val overview: String? = null,
    val communityRating: Double = 0.0,
    val imageTag: String? = null,
    val isFavorite: Boolean = false,
    val playbackPositionTicks: Long = 0L,
    val playedPercentage: Double = 0.0,
    /** 刮削标签（Fields=Tags 时返回） */
    val tags: List<String> = emptyList(),
    /** 类型（Fields=Genres 时返回；所有刮削器必写，作为类型筛选主数据源） */
    val genres: List<String> = emptyList(),
    /** 关联人物（Fields=People 时返回） */
    val people: List<EmbyPerson> = emptyList()
) {
    /** ticks → ms */
    val durationMs: Long get() = runTimeTicks / 10_000

    /** 服务器续播位置（ms） */
    val playbackPositionMs: Long get() = playbackPositionTicks / 10_000

    /** 海报相对路径 */
    fun posterPath(maxWidth: Int = 300): String {
        val tagPart = imageTag?.let { "&tag=$it" } ?: ""
        return "/Items/$id/Images/Primary?maxWidth=$maxWidth$tagPart"
    }

    /** 背景图相对路径 */
    fun backdropPath(maxWidth: Int = 780): String = "/Items/$id/Images/Backdrop?maxWidth=$maxWidth"

    fun posterUrl(baseUrl: String, maxWidth: Int = 300): String =
        baseUrl.trimEnd('/') + posterPath(maxWidth)

    fun backdropUrl(baseUrl: String, maxWidth: Int = 780): String =
        baseUrl.trimEnd('/') + backdropPath(maxWidth)
}

/**
 * 外挂文本字幕流（仅取可被客户端 side-load 的 sidecar 文本字幕）。
 *
 * [url] 为构造好的完整字幕地址（含 api_key），由 [EmbyRepository.resolvePlayback] 填充；
 * 其格式固定为 `{base}/Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.srt?api_key=...`
 * （统一请求 .srt，让 Emby 侧完成格式转换，避免客户端解析差异）。
 */
data class EmbySubtitleStream(
    val index: Int,
    val label: String,
    val language: String? = null,
    val isDefault: Boolean = false,
    val url: String = ""
)

/** 播放决策结果：可播放地址 + 可供 side-load 的外挂字幕流 */
data class EmbyPlayback(
    val playUri: String,
    val playSessionId: String = "",
    val mediaSourceId: String = "",
    val subtitleStreams: List<EmbySubtitleStream>
)

/** PlaybackInfo 解析结果（用于 Direct Play 决策，D3） */
data class PlaybackInfo(
    val directStreamUrl: String?,
    val transcodingUrl: String?,
    val playSessionId: String?,
    val path: String? = null,
    val mediaSourceId: String? = null,
    val subtitleStreams: List<EmbySubtitleStream> = emptyList()
)
