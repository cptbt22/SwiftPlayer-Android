package com.swiftplayer.data.emby

import com.swiftplayer.data.model.MediaEntry
import com.swiftplayer.data.model.SourceType
import java.util.concurrent.ConcurrentHashMap

/**
 * Emby 高层编排 + 地址优选 + 播放地址构造
 *
 * 地址优选（R1）：服务器若配置了局域网地址（lanBaseUrl），
 * 按 60s TTL 探测其可达性（/System/Info/Public，1.5s 超时）——
 * 局域网可达时全链路（媒体库/详情/播放/图片）优先走局域网，否则回退主地址。
 *
 * 播放地址决策（D3）：DirectStreamUrl 优先 → Path 可直连 → TranscodingUrl 回退 → 兜底 static。
 * 统一拼接 api_key（走查询参数，降低兼容风险）。
 */
class EmbyRepository(private val api: EmbyApi, private val auth: EmbyAuthManager) {

    /** serverId -> (已解析地址, 解析时间戳)；局域网探测结果短时缓存，避免每次请求都探测 */
    private val resolvedCache = ConcurrentHashMap<String, Pair<String, Long>>()

    companion object {
        private const val RESOLVE_TTL_MS = 60_000L
    }

    /** 测试地址可达性（不落库） */
    suspend fun ping(baseUrl: String): Result<Unit> = api.ping(baseUrl)

    /** 登录并持久化服务器（lanUrl 可选：局域网地址；在家优先走它） */
    suspend fun login(
        baseUrl: String,
        user: String,
        pwd: String,
        remark: String,
        lanUrl: String = ""
    ): Result<EmbyServer> {
        val normalized = api.normalize(baseUrl)
        val normalizedLan = api.normalize(lanUrl)
        return api.authenticate(normalized, user, pwd).map { session ->
            val server = EmbyServer(
                id = "$normalized#${session.userId}",
                name = remark.ifEmpty { hostOf(normalized) },
                baseUrl = normalized,
                lanBaseUrl = normalizedLan,
                userId = session.userId,
                accessToken = session.accessToken,
                lastConnected = System.currentTimeMillis()
            )
            auth.upsert(server)
            auth.setCurrent(server.id)
            resolvedCache.remove(server.id)
            server
        }
    }

    /**
     * 解析当前应使用的服务器地址：局域网地址可达则优先，否则主地址。
     * 结果按 [RESOLVE_TTL_MS] 缓存；未配置局域网地址时直接返回主地址。
     */
    suspend fun resolveBaseUrl(server: EmbyServer): String {
        val lan = server.lanBaseUrl
        if (lan.isEmpty()) return server.baseUrl

        val now = System.currentTimeMillis()
        resolvedCache[server.id]?.let { (url, ts) ->
            if (now - ts < RESOLVE_TTL_MS) return url
        }
        val chosen = if (api.pingFast(lan)) lan else server.baseUrl
        resolvedCache[server.id] = chosen to now
        return chosen
    }

    /** 同步读取最近一次解析结果（供图片 URL 等构建使用；未解析过则回退主地址） */
    fun cachedBaseUrl(server: EmbyServer): String =
        resolvedCache[server.id]?.first ?: server.baseUrl

    suspend fun views(server: EmbyServer): Result<List<EmbyView>> {
        val base = resolveBaseUrl(server)
        return api.getViews(base, server.accessToken, server.userId)
    }

    suspend fun items(server: EmbyServer, viewId: String): Result<List<EmbyItem>> {
        val base = resolveBaseUrl(server)
        return api.getItems(base, server.accessToken, server.userId, viewId)
    }

    suspend fun detail(server: EmbyServer, itemId: String): Result<EmbyItem> {
        val base = resolveBaseUrl(server)
        return api.getItemDetail(base, server.accessToken, server.userId, itemId)
    }

    /** 删除影片（库条目 + 媒体文件 + 刮削文件），需管理员账号 */
    suspend fun deleteItem(server: EmbyServer, itemId: String): Result<Unit> {
        val base = resolveBaseUrl(server)
        return api.deleteItem(base, server.accessToken, itemId)
    }

    /** 收藏/取消收藏（favorite=true 收藏，false 取消） */
    suspend fun setFavorite(server: EmbyServer, itemId: String, favorite: Boolean): Result<Unit> {
        val base = resolveBaseUrl(server)
        return api.setFavorite(base, server.accessToken, server.userId, itemId, favorite)
    }

    /** 收藏夹列表 */
    suspend fun favorites(server: EmbyServer): Result<List<EmbyItem>> {
        val base = resolveBaseUrl(server)
        return api.getFavoriteItems(base, server.accessToken, server.userId)
    }

    /** 相似影片推荐 */
    suspend fun similar(server: EmbyServer, itemId: String): Result<List<EmbyItem>> {
        val base = resolveBaseUrl(server)
        return api.getSimilar(base, server.accessToken, server.userId, itemId)
    }

    /** 搜索影片/剧集 */
    suspend fun search(server: EmbyServer, query: String): Result<List<EmbyItem>> {
        val base = resolveBaseUrl(server)
        return api.search(base, server.accessToken, server.userId, query)
    }

    // ---- 演员 / 类型（v2.5.1 客户端聚合） ----

    /**
     * 指定演员的影片/剧集（服务端 PersonIds 过滤）。
     * personId 来自 [aggregatePeople]（影片 People 字段），天然限定现有影片关联人物。
     */
    suspend fun itemsByPerson(server: EmbyServer, personId: String): Result<List<EmbyItem>> {
        val base = resolveBaseUrl(server)
        return api.getItemsByPerson(base, server.accessToken, server.userId, personId)
    }

    /**
     * 全库条目（含 Tags/Genres/People），供演员与类型聚合。
     * 按 serverId 缓存一次拉取结果，页内切换不再重复请求。
     */
    suspend fun itemsWithTags(server: EmbyServer): Result<List<EmbyItem>> {
        val base = resolveBaseUrl(server)
        return api.getItemsWithTags(base, server.accessToken, server.userId)
            .onSuccess { tagsCache[server.id] = it }
    }

    /** 读取最近一次全库缓存（未拉取过返回 null） */
    fun cachedTagItems(serverId: String): List<EmbyItem>? = tagsCache[serverId]

    /**
     * 聚合类型/标签清单：**Genres 优先**（所有刮削器必写），全空时回退 Tags
     * （多数 Emby 刮削器不写 Tags，v2.5.0 真机验证标签页因此为空）。
     * 按影片数降序、名称升序。
     */
    fun aggregateCategories(items: List<EmbyItem>): List<EmbyTag> {
        val counts = LinkedHashMap<String, Int>()
        val source: List<String> = if (items.any { it.genres.isNotEmpty() }) {
            items.flatMap { it.genres }
        } else {
            items.flatMap { it.tags }
        }
        source.forEach { name -> counts[name] = (counts[name] ?: 0) + 1 }
        return counts.entries
            .map { EmbyTag(it.key, it.value) }
            .sortedWith(compareByDescending<EmbyTag> { it.count }.thenBy { it.name.lowercase() })
    }

    /**
     * 从影片 People 字段聚合演员清单：
     * - 只保留**有头像**的人物（服务器没图的条目在网格里必然裂图，真机反馈的"头像没显示"）；
     * - 按出演影片数降序——主角在前，龙套/客串沉底；
     * - 数据源是现有影片的关联人物，不含已删影片残留与纯幕后人员。
     */
    fun aggregatePeople(items: List<EmbyItem>): List<EmbyItem> {
        val acc = LinkedHashMap<String, Pair<EmbyPerson, Int>>()
        items.forEach { item ->
            item.people.forEach { p ->
                val existing = acc[p.id]
                acc[p.id] = if (existing == null) p to 1 else existing.first to existing.second + 1
            }
        }
        return acc.values
            .filter { it.first.imageTag != null }
            .sortedByDescending { it.second }
            .map { (person, count) ->
                EmbyItem(
                    id = person.id,
                    name = person.name,
                    imageTag = person.imageTag,
                    communityRating = count.toDouble() // 复用字段承载"出演影片数"，供排序/调试
                )
            }
    }

    private val tagsCache = ConcurrentHashMap<String, List<EmbyItem>>()

    /**
     * 解析播放地址（Direct Play 优先，失败/不可直连回退转码流，最后兜底静态流）
     */
    suspend fun resolvePlayUri(server: EmbyServer, itemId: String): Result<String> {
        val base = resolveBaseUrl(server)
        return api.getPlaybackInfo(base, server.accessToken, server.userId, itemId)
            .map { info ->
                val (candidate, _) = decideStream(info, itemId)
                appendAuth(preferLanHost(candidate, server, base), base, server.accessToken)
            }
    }

    /**
     * 解析播放决策：可播放地址 + 可 side-load 的外挂字幕流（F3）。
     *
     * 播放地址优先级：DirectStreamUrl → Path 直连（static） → TranscodingUrl 回退 → 兜底 static。
     * 字幕 side-load 仅在 Direct Play 生效（candidate 为 DirectStreamUrl / static）：
     * 若回退到转码流（TranscodingUrl，通常为 .m3u8），字幕由服务端烧录/输出，客户端不再 side-load，
     * 避免与容器内嵌字幕或服务端字幕出现重复轨。
     *
     * 字幕地址固定构造为：`{resolvedBase}/Videos/{itemId}/{mediaSourceId}/Subtitles/{index}/Stream.srt?api_key={token}`
     * （无 mediaSourceId 时以 itemId 兜底）。
     */
    suspend fun resolvePlayback(server: EmbyServer, itemId: String): Result<EmbyPlayback> {
        val base = resolveBaseUrl(server)
        return api.getPlaybackInfo(base, server.accessToken, server.userId, itemId)
            .map { info ->
                val (candidate, isDirect) = decideStream(info, itemId)
                val playSessionId = info.playSessionId?.ifEmpty { null }
                    ?: java.util.UUID.randomUUID().toString()
                val playUri = appendPlaySessionId(
                    appendAuth(preferLanHost(candidate, server, base), base, server.accessToken),
                    playSessionId
                )
                val mediaSourceId = if (info.mediaSourceId.isNullOrEmpty()) itemId else info.mediaSourceId
                val streams = if (isDirect) {
                    info.subtitleStreams.map { s ->
                        s.copy(url = buildSubtitleUrl(base, itemId, mediaSourceId, s.index, server.accessToken))
                    }
                } else {
                    emptyList()
                }
                EmbyPlayback(
                    playUri = playUri,
                    playSessionId = playSessionId,
                    mediaSourceId = mediaSourceId,
                    subtitleStreams = streams
                )
            }
    }

    /** 决策播放流：返回 (可播放候选地址, 是否 Direct Play) */
    private fun decideStream(info: PlaybackInfo, itemId: String): Pair<String, Boolean> = when {
        !info.directStreamUrl.isNullOrEmpty() -> info.directStreamUrl!! to true
        !info.path.isNullOrEmpty() -> "/Videos/$itemId/stream?static=true" to true
        !info.transcodingUrl.isNullOrEmpty() -> info.transcodingUrl!! to false
        else -> "/Videos/$itemId/stream?static=true" to true
    }

    /** 构造外挂字幕 side-load 地址（统一 .srt，让 Emby 转换） */
    private fun buildSubtitleUrl(
        base: String,
        itemId: String,
        mediaSourceId: String,
        index: Int,
        token: String
    ): String {
        val root = base.trimEnd('/')
        val path = "/Videos/$itemId/$mediaSourceId/Subtitles/$index/Stream.srt"
        return if (token.isEmpty()) "$root$path" else "$root$path?api_key=$token"
    }

    /**
     * Direct 返回「绝对地址」且指向主地址 host 时：若已判定局域网可用（当前解析结果为局域网地址），
     * 则重写 host 为主机的局域网地址——保证在家播放也走局域网直连（更快、快进更顺）。
     */
    private fun preferLanHost(url: String, server: EmbyServer, resolvedBase: String): String {
        if (server.lanBaseUrl.isEmpty() || resolvedBase != server.lanBaseUrl) return url
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url
        return if (url.startsWith(server.baseUrl + "/") || url.startsWith(server.baseUrl + "?")) {
            server.lanBaseUrl + url.removePrefix(server.baseUrl)
        } else {
            url
        }
    }

    /** Emby item → 统一媒体条目 */
    fun toMediaEntry(server: EmbyServer, item: EmbyItem): MediaEntry {
        val base = cachedBaseUrl(server)
        val fallbackUri = appendAuth(
            "/Videos/${item.id}/stream?static=true",
            base,
            server.accessToken
        )
        return MediaEntry(
            id = item.id,
            title = item.name,
            playUri = fallbackUri,
            sourceType = SourceType.EMBY,
            durationMs = item.durationMs,
            mimeType = "video/*",
            posterUrl = EmbyApi.buildImageUrl(base, item.posterPath(300), server.accessToken),
            backdropUrl = EmbyApi.buildImageUrl(base, item.backdropPath(780), server.accessToken),
            overview = item.overview,
            year = item.productionYear,
            rating = item.communityRating,
            embyItemId = item.id
        )
    }

    /** 拼接 api_key（若尚未包含），并补全相对地址 */
    private fun appendAuth(url: String, baseUrl: String, token: String): String {
        var u = url
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = baseUrl.trimEnd('/') + (if (u.startsWith("/")) u else "/$u")
        }
        if (token.isNotEmpty() && !u.contains("api_key=")) {
            u += if (u.contains("?")) "&api_key=$token" else "?api_key=$token"
        }
        return u
    }

    /** 保证播放会话 ID 与流 URL 关联，Emby 才能把进度归到同一次播放。 */
    private fun appendPlaySessionId(url: String, playSessionId: String): String {
        if (url.contains("PlaySessionId=", ignoreCase = true)) return url
        return url + if (url.contains("?")) "&PlaySessionId=$playSessionId" else "?PlaySessionId=$playSessionId"
    }

    private fun hostOf(url: String): String =
        url.removePrefix("http://").removePrefix("https://").substringBefore("/")
}
