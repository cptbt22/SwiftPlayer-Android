package com.swiftplayer.data.emby

import com.swiftplayer.BuildConfig
import com.swiftplayer.data.net.DnsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Emby 请求异常（携带面向用户的中文文案） */
class EmbyApiException(message: String) : Exception(message)

/**
 * Emby HTTP 客户端（OkHttp 4.12 + Android 内置 org.json）
 *
 * - 统一超时：连接 10s / 读 15s / 写 10s（PRD §6.3）
 * - 请求头注入 X-Emby-Authorization（设备稳定）+ X-Emby-Token（业务请求）
 * - 所有网络调用在 Dispatchers.IO，返回 Result<T>
 * - 错误映射：IOException→地址不可达；401→账号错误；非预期 JSON/404→非 Emby 服务器
 */
class EmbyApi(private val deviceId: String) {

    private val client = OkHttpClient.Builder()
        .dns(DnsProvider.smart)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 规范化地址：无 scheme 补 http://，去尾部 / */
    fun normalize(url: String): String {
        var u = url.trim()
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://$u"
        }
        while (u.endsWith("/")) u = u.dropLast(1)
        return u
    }

    private fun authHeaderValue(): String =
        "MediaBrowser Client=\"SwiftPlayer\", Device=\"Android\", DeviceId=\"$deviceId\", Version=\"${BuildConfig.VERSION_NAME}\""

    /**
     * 连接诊断：把 DNS / TCP / HTTP 各层实测结果拼成可读文本。
     * 供「测试连接」失败时展示，避免所有网络异常都被压成一句「检查地址与网络」。
     */
    suspend fun diagnose(baseUrl: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val normalized = normalize(baseUrl)
        sb.append("地址  ").append(normalized).append('\n')

        val uri = try {
            URI(normalized)
        } catch (e: Exception) {
            sb.append("地址格式错误: ").append(e.message).append('\n')
            return@withContext sb.toString()
        }
        val host = uri.host?.removeSurrounding("[", "]")
        if (host.isNullOrEmpty()) {
            sb.append("无法从地址中解析出主机名").append('\n')
            return@withContext sb.toString()
        }
        val port = if (uri.port > 0) uri.port else if (normalized.startsWith("https")) 443 else 8096
        sb.append("主机  ").append(host).append("    端口  ").append(port).append('\n')

        var addrs: List<InetAddress> = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            sb.append("系统 DNS 失败  [").append(e.javaClass.simpleName).append("] ").append(e.message).append('\n')
            emptyList()
        }

        if (addrs.isEmpty()) {
            sb.append("系统 DNS 无结果，尝试 DoH (alidns) …").append('\n')
            addrs = try {
                DnsProvider.lookupOverDoh(host)
            } catch (e: Exception) {
                sb.append("DoH 解析失败  [").append(e.javaClass.simpleName).append("] ").append(e.message).append('\n')
                emptyList()
            }
            if (addrs.isNotEmpty()) {
                sb.append("DoH 解析成功  ")
                    .append(addrs.joinToString(", ") { it.hostAddress ?: "?" }).append('\n')
                sb.append("→ 系统 DNS 不可用，app 已自动改用 DoH 解析。").append('\n')
            }
        }

        val v4 = addrs.filter { it is Inet4Address }
        val v6 = addrs.filter { it is Inet6Address }
        sb.append("A (IPv4)      ")
            .append(if (v4.isEmpty()) "无" else v4.joinToString(", ") { it.hostAddress ?: "?" }).append('\n')
        sb.append("AAAA (IPv6)   ")
            .append(if (v6.isEmpty()) "无" else v6.joinToString(", ") { it.hostAddress ?: "?" }).append('\n')

        var tcpOk = false
        for (a in addrs) {
            val kind = if (a is Inet6Address) "IPv6" else "IPv4"
            val begin = System.currentTimeMillis()
            val res = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(a, port), 4000)
                    tcpOk = true
                    "成功"
                }
            } catch (e: Exception) {
                "失败 [" + e.javaClass.simpleName + "] " + e.message
            }
            sb.append("TCP ").append(kind).append(' ').append(a.hostAddress).append(':').append(port)
                .append("  → ").append(res)
                .append("  (").append(System.currentTimeMillis() - begin).append("ms)").append('\n')
        }

        val begin = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url("$normalized/System/Info/Public")
                .header("X-Emby-Authorization", authHeaderValue())
                .build()
            client.newCall(request).execute().use { resp ->
                sb.append("HTTP  ").append(resp.code)
                    .append("  (").append(System.currentTimeMillis() - begin).append("ms)").append('\n')
                sb.append("结论  ")
                    .append(if (resp.isSuccessful) "服务器可达，链路正常" else "端口可达但响应异常")
                    .append('\n')
            }
        } catch (e: Exception) {
            sb.append("HTTP  请求失败 [").append(e.javaClass.simpleName).append("] ").append(e.message).append('\n')
            val advice = when (e) {
                is UnknownHostException -> "域名解析失败，检查 DNS 或该域名是否仅支持 IPv6"
                is SocketTimeoutException -> "TCP 超时，地址解析到了却连不上：多为 IPv6 无出口、防火墙拦截或端口未放行"
                is ConnectException -> "连接被拒绝，端口未开放"
                is IOException -> "网络不可达，检查当前网络是否支持 IPv6"
                else -> "未知错误"
            }
            sb.append("结论  ").append(advice).append('\n')
        }

        if (!tcpOk && addrs.isNotEmpty()) {
            sb.append("提示  所有地址的 TCP 均失败，当前网络到 NAS 的通路不通。").append('\n')
        }
        sb.toString()
    }

    /** 把网络异常翻译成能指向具体环节的话术，不再一律「检查地址与网络」 */
    private fun networkErrorMessage(e: Exception): String {
        val detail = e.message ?: e.javaClass.simpleName
        return when (e) {
            is UnknownHostException -> "域名解析失败（$detail）—— 该域名可能只有 IPv6 记录，确认当前网络能解析它"
            is SocketTimeoutException -> "连接超时（$detail）—— 地址解析到了但没有响应，多为 IPv6 无出口或端口被拦"
            is ConnectException -> "连接被拒绝（$detail）—— 端口未开放"
            else -> "网络错误 ${e.javaClass.simpleName}：$detail"
        }
    }

    /**
     * 快速可达性探测（短超时）：供「局域网地址优选」使用。
     * 不抛异常、不区分错误类型，仅返回可达与否。
     */
    suspend fun pingFast(baseUrl: String, timeoutMs: Long = 1500L): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val fastClient = client.newBuilder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder()
                    .url("${normalize(baseUrl)}/System/Info/Public")
                    .header("X-Emby-Authorization", authHeaderValue())
                    .build()
                fastClient.newCall(request).execute().use { resp -> resp.isSuccessful }
            } catch (_: Exception) {
                false
            }
        }

    /** 测试地址可达性（命中 /System/Info/Public） */
    suspend fun ping(baseUrl: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${normalize(baseUrl)}/System/Info/Public")
                .header("X-Emby-Authorization", authHeaderValue())
                .build()
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(EmbyApiException("该地址不是有效的 Emby 服务器"))
                }
            }
        } catch (e: IOException) {
            Result.failure(EmbyApiException(networkErrorMessage(e)))
        } catch (e: Exception) {
            Result.failure(EmbyApiException(e.message ?: "未知错误"))
        }
    }

    /** 账号认证 */
    suspend fun authenticate(
        baseUrl: String,
        username: String,
        password: String
    ): Result<EmbySession> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("Username", username)
                put("Pw", password)
            }.toString().toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("${normalize(baseUrl)}/Users/AuthenticateByName")
                .header("X-Emby-Authorization", authHeaderValue())
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 401 -> Result.failure(EmbyApiException("用户名或密码错误"))
                    resp.code == 404 -> Result.failure(EmbyApiException("该地址不是有效的 Emby 服务器"))
                    !resp.isSuccessful -> Result.failure(EmbyApiException("登录失败：HTTP ${resp.code}"))
                    else -> {
                        val obj = try {
                            JSONObject(text)
                        } catch (_: Exception) {
                            return@withContext Result.failure(EmbyApiException("该地址不是有效的 Emby 服务器"))
                        }
                        val token = obj.optString("AccessToken")
                        val user = obj.optJSONObject("User")
                        val userId = user?.optString("Id") ?: obj.optString("UserId")
                        val userName = user?.optString("Name") ?: username
                        if (token.isEmpty() || userId.isEmpty()) {
                            Result.failure(EmbyApiException("该地址不是有效的 Emby 服务器"))
                        } else {
                            Result.success(EmbySession(userId, token, userName))
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Result.failure(EmbyApiException(networkErrorMessage(e)))
        } catch (e: Exception) {
            Result.failure(EmbyApiException(e.message ?: "未知错误"))
        }
    }

    /** 媒体库视图列表 */
    suspend fun getViews(baseUrl: String, token: String, userId: String): Result<List<EmbyView>> =
        withContext(Dispatchers.IO) {
            requestJson("${normalize(baseUrl)}/Users/$userId/Views", token).map { obj ->
                val arr = obj.optJSONArray("Items") ?: JSONArray()
                val list = mutableListOf<EmbyView>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("Id")
                    if (id.isNotEmpty()) {
                        list.add(EmbyView(id, o.optString("Name")))
                    }
                }
                list
            }
        }

    /** 某媒体库下的影片/剧集 */
    suspend fun getItems(
        baseUrl: String,
        token: String,
        userId: String,
        parentId: String
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val fields = "Overview,RunTimeTicks,ProductionYear,CommunityRating,ImageTags"
        val url = "${normalize(baseUrl)}/Users/$userId/Items" +
                "?ParentId=$parentId" +
                "&IncludeItemTypes=Movie,Episode" +
                "&Recursive=true" +
                "&Fields=$fields" +
                "&ImageTypeLimit=1" +
                "&SortBy=SortName"
        requestJson(url, token).map { obj ->
            val arr = obj.optJSONArray("Items") ?: JSONArray()
            val list = mutableListOf<EmbyItem>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { list.add(parseItem(it)) }
            }
            list
        }
    }

    /** 影片详情 */
    suspend fun getItemDetail(
        baseUrl: String,
        token: String,
        userId: String,
        itemId: String
    ): Result<EmbyItem> = withContext(Dispatchers.IO) {
        requestJson("${normalize(baseUrl)}/Users/$userId/Items/$itemId", token).map { parseItem(it) }
    }

    /** PlaybackInfo（用于 Direct Play 决策 + 外挂字幕流解析） */
    suspend fun getPlaybackInfo(
        baseUrl: String,
        token: String,
        userId: String,
        itemId: String
    ): Result<PlaybackInfo> = withContext(Dispatchers.IO) {
        val url = "${normalize(baseUrl)}/Items/$itemId/PlaybackInfo?UserId=$userId"
        requestJson(url, token).map { obj ->
            val src = obj.optJSONArray("MediaSources")?.optJSONObject(0)
            val sessionId = obj.optString("PlaySessionId")
                .ifEmpty { src?.optString("PlaySessionId").orEmpty() }
                .ifEmpty { null }
            PlaybackInfo(
                directStreamUrl = src?.optString("DirectStreamUrl")?.ifEmpty { null },
                transcodingUrl = src?.optString("TranscodingUrl")?.ifEmpty { null },
                playSessionId = sessionId,
                path = src?.optString("Path")?.ifEmpty { null },
                mediaSourceId = src?.optString("Id")?.ifEmpty { null },
                subtitleStreams = parseSubtitleStreams(src)
            )
        }
    }

    /**
     * 解析 MediaStreams 中「可 side-load 的外挂文本字幕流」：
     * 仅取 `Type==Subtitle && IsTextSubtitleStream==true && IsExternal==true`——
     * 内嵌字幕由 ExoPlayer 直接读容器、转码流由服务端出字幕，避免重复字幕轨。
     */
    private fun parseSubtitleStreams(src: JSONObject?): List<EmbySubtitleStream> {
        val arr = src?.optJSONArray("MediaStreams") ?: return emptyList()
        val list = mutableListOf<EmbySubtitleStream>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            if (s.optString("Type") != "Subtitle") continue
            if (!s.optBoolean("IsTextSubtitleStream", false)) continue
            if (!s.optBoolean("IsExternal", false)) continue
            val index = s.optInt("Index", -1)
            if (index < 0) continue
            val language = s.optString("Language").ifEmpty { null }
            val label = s.optString("DisplayTitle")
                .ifEmpty { s.optString("Language") }
                .ifEmpty { "字幕 ${list.size + 1}" }
            list.add(
                EmbySubtitleStream(
                    index = index,
                    label = label,
                    language = language,
                    isDefault = s.optBoolean("IsDefault", false)
                )
            )
        }
        return list
    }

    /**
     * 收藏状态切换：favorite=true → POST，false → DELETE
     * `{base}/Users/{userId}/FavoriteItems/{itemId}`（成功 200/204）。
     */
    suspend fun setFavorite(
        baseUrl: String,
        token: String,
        userId: String,
        itemId: String,
        favorite: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url("${normalize(baseUrl)}/Users/$userId/FavoriteItems/$itemId")
                .header("X-Emby-Authorization", authHeaderValue())
                .header("X-Emby-Token", token)
            if (favorite) {
                builder.post("".toRequestBody(null))
            } else {
                builder.delete()
            }
            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.success(Unit) // 200 / 204
                    resp.code == 401 -> Result.failure(EmbyApiException("登录已失效，请重新登录"))
                    resp.code == 403 -> Result.failure(EmbyApiException("当前账号无收藏权限"))
                    resp.code == 404 -> Result.failure(EmbyApiException("影片不存在或已被删除"))
                    else -> Result.failure(EmbyApiException("收藏操作失败：HTTP ${resp.code}"))
                }
            }
        } catch (e: IOException) {
            Result.failure(EmbyApiException(networkErrorMessage(e)))
        } catch (e: Exception) {
            Result.failure(EmbyApiException(e.message ?: "未知错误"))
        }
    }

    /** 收藏夹列表：`{base}/Users/{uid}/Items?Filters=IsFavorite...` */
    suspend fun getFavoriteItems(
        baseUrl: String,
        token: String,
        userId: String,
        limit: Int = 100
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val fields = "Overview,RunTimeTicks,ProductionYear,CommunityRating,ImageTags,UserData"
        val url = "${normalize(baseUrl)}/Users/$userId/Items" +
                "?Filters=IsFavorite" +
                "&Recursive=true" +
                "&IncludeItemTypes=Movie,Episode" +
                "&Fields=$fields" +
                "&ImageTypeLimit=1" +
                "&Limit=$limit"
        requestJson(url, token).map { obj -> parseItems(obj.optJSONArray("Items")) }
    }

    /**
     * 继续观看（播放历史，v2.5.2）：`Filters=IsResumable`——
     * 服务器侧筛选「已开始且未看完」的影片，按最近播放排序；
     * UserData.PlayedPercentage 直接用于海报进度条。
     */
    suspend fun getResumableItems(
        baseUrl: String,
        token: String,
        userId: String,
        limit: Int = 30
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val fields = "Overview,RunTimeTicks,ProductionYear,CommunityRating,ImageTags,UserData"
        val url = "${normalize(baseUrl)}/Users/$userId/Items" +
                "?Filters=IsResumable" +
                "&Recursive=true" +
                "&IncludeItemTypes=Movie,Episode" +
                "&Fields=$fields" +
                "&ImageTypeLimit=1" +
                "&SortBy=DatePlayed" +
                "&SortOrder=Descending" +
                "&Limit=$limit"
        requestJson(url, token).map { obj -> parseItems(obj.optJSONArray("Items")) }
    }

    /** 相似影片推荐：`{base}/Items/{itemId}/Similar?UserId={uid}&Limit=12`（返回 {Items:[...]}） */
    suspend fun getSimilar(
        baseUrl: String,
        token: String,
        userId: String,
        itemId: String,
        limit: Int = 12
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val url = "${normalize(baseUrl)}/Items/$itemId/Similar?UserId=$userId&Limit=$limit"
        requestJson(url, token).map { obj -> parseItems(obj.optJSONArray("Items")) }
    }

    /**
     * 搜索影片/剧集：
     * `{base}/Items?SearchTerm={q}&UserId={uid}&IncludeItemTypes=Movie,Episode&Recursive=true&...`
     */
    suspend fun search(
        baseUrl: String,
        token: String,
        userId: String,
        query: String,
        limit: Int = 60
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val fields = "Overview,RunTimeTicks,ProductionYear,CommunityRating,ImageTags,UserData"
        val term = encodeQuery(query)
        val url = "${normalize(baseUrl)}/Items" +
                "?SearchTerm=$term" +
                "&UserId=$userId" +
                "&IncludeItemTypes=Movie,Episode" +
                "&Recursive=true" +
                "&Fields=$fields" +
                "&ImageTypeLimit=1" +
                "&Limit=$limit"
        requestJson(url, token).map { obj -> parseItems(obj.optJSONArray("Items")) }
    }

    /**
     * 指定演员的影片/剧集：`PersonIds={personId}` 服务端过滤。
     * personId 来自影片 People 数组的聚合（v2.5.1），天然限定现有影片关联人物。
     */
    suspend fun getItemsByPerson(
        baseUrl: String,
        token: String,
        userId: String,
        personId: String
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val fields = "Overview,RunTimeTicks,ProductionYear,CommunityRating,ImageTags,UserData"
        val url = "${normalize(baseUrl)}/Users/$userId/Items" +
                "?PersonIds=$personId" +
                "&IncludeItemTypes=Movie,Episode" +
                "&Recursive=true" +
                "&Fields=$fields" +
                "&ImageTypeLimit=1" +
                "&SortBy=SortName"
        requestJson(url, token).map { obj -> parseItems(obj.optJSONArray("Items")) }
    }

    /**
     * 全库影片/剧集（v2.5.1 扩展字段）：
     * 拉取一次后在客户端聚合「类型（Genres）/ 标签（Tags）/ 人物（People）」，
     * 避免依赖 Emby /Tags、/Genres 端点与 Person 全库列表的版本差异——
     * 全库 Person 列表会混入已删影片残留与导演等非演员，People 聚合则天然限定在现有影片内。
     * 个人媒体库规模（数百~数千条）下一次拉取可控。
     */
    suspend fun getItemsWithTags(
        baseUrl: String,
        token: String,
        userId: String,
        limit: Int = 2000
    ): Result<List<EmbyItem>> = withContext(Dispatchers.IO) {
        val fields = "Overview,RunTimeTicks,ProductionYear,CommunityRating,ImageTags,UserData,Tags,Genres,People"
        val url = "${normalize(baseUrl)}/Users/$userId/Items" +
                "?IncludeItemTypes=Movie,Episode" +
                "&Recursive=true" +
                "&Fields=$fields" +
                "&ImageTypeLimit=1" +
                "&Limit=$limit" +
                "&SortBy=SortName"
        requestJson(url, token).map { obj -> parseItems(obj.optJSONArray("Items")) }
    }

    /** URL 编码查询词（空格转 %20，兼容 Emby 查询参数） */
    private fun encodeQuery(query: String): String = try {
        java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        query
    }

    /**
     * 删除影片（Emby 官方接口 `DELETE /Items/{ItemId}`：
     * "Deletes an item from the library and file system"——库条目、媒体文件与刮削文件一并删除）。
     * 需要管理员账号（普通账号会返回 403）。
     */
    suspend fun deleteItem(
        baseUrl: String,
        token: String,
        itemId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${normalize(baseUrl)}/Items/$itemId")
                .header("X-Emby-Authorization", authHeaderValue())
                .header("X-Emby-Token", token)
                .delete()
                .build()
            client.newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.success(Unit) // 204/200
                    resp.code == 401 -> Result.failure(EmbyApiException("登录已失效，请重新登录"))
                    resp.code == 403 -> Result.failure(EmbyApiException("当前账号无删除权限，请使用管理员账号"))
                    resp.code == 404 -> Result.failure(EmbyApiException("影片不存在或已被删除"))
                    else -> Result.failure(EmbyApiException("删除失败：HTTP ${resp.code}"))
                }
            }
        } catch (e: IOException) {
            Result.failure(EmbyApiException(networkErrorMessage(e)))
        } catch (e: Exception) {
            Result.failure(EmbyApiException(e.message ?: "未知错误"))
        }
    }

    private fun parseItems(arr: JSONArray?): List<EmbyItem> {
        if (arr == null) return emptyList()
        val list = mutableListOf<EmbyItem>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { list.add(parseItem(it)) }
        }
        return list
    }

    private fun parseItem(o: JSONObject): EmbyItem {
        val tags = o.optJSONObject("ImageTags")
        val imageTag = tags?.optString("Primary")?.ifEmpty { null }
        val userData = o.optJSONObject("UserData")

        fun stringArray(key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val t = arr.optString(i)
                if (t.isNotEmpty()) list.add(t)
            }
            return list
        }

        val peopleArr = o.optJSONArray("People")
        val people = mutableListOf<EmbyPerson>()
        if (peopleArr != null) {
            for (i in 0 until peopleArr.length()) {
                val p = peopleArr.optJSONObject(i) ?: continue
                val pid = p.optString("Id")
                val pname = p.optString("Name")
                if (pid.isEmpty() || pname.isEmpty()) continue
                people.add(
                    EmbyPerson(
                        id = pid,
                        name = pname,
                        role = p.optString("Role"),
                        type = p.optString("Type"),
                        imageTag = p.optString("PrimaryImageTag").ifEmpty { null }
                    )
                )
            }
        }

        return EmbyItem(
            id = o.optString("Id"),
            name = o.optString("Name"),
            runTimeTicks = o.optLong("RunTimeTicks", 0L),
            productionYear = o.optInt("ProductionYear", 0),
            overview = o.optString("Overview").ifEmpty { null },
            communityRating = o.optDouble("CommunityRating", 0.0),
            imageTag = imageTag,
            isFavorite = userData?.optBoolean("IsFavorite") ?: false,
            playbackPositionTicks = userData?.optLong("PlaybackPositionTicks", 0L) ?: 0L,
            playedPercentage = userData?.optDouble("PlayedPercentage", 0.0) ?: 0.0,
            tags = stringArray("Tags"),
            genres = stringArray("Genres"),
            people = people
        )
    }

    /** 阻塞式 JSON 请求（须在 IO 线程调用），统一错误映射 */
    private fun requestJson(url: String, token: String?): Result<JSONObject> {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("X-Emby-Authorization", authHeaderValue())
            if (!token.isNullOrEmpty()) {
                builder.header("X-Emby-Token", token)
            }
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 401 -> Result.failure(EmbyApiException("用户名或密码错误"))
                    resp.code == 404 -> Result.failure(EmbyApiException("该地址不是有效的 Emby 服务器"))
                    !resp.isSuccessful -> Result.failure(EmbyApiException("请求失败：HTTP ${resp.code}"))
                    else -> parseOrFail(text)
                }
            }
        } catch (e: IOException) {
            Result.failure(EmbyApiException(networkErrorMessage(e)))
        } catch (e: Exception) {
            Result.failure(EmbyApiException(e.message ?: "未知错误"))
        }
    }

    private fun parseOrFail(text: String): Result<JSONObject> = try {
        Result.success(JSONObject(text))
    } catch (_: Exception) {
        Result.failure(EmbyApiException("该地址不是有效的 Emby 服务器"))
    }

    companion object {
        /**
         * 构造图片完整 URL（统一拼接 api_key，R7/§9）
         * @param path 相对路径，如 /Items/{id}/Images/Primary?maxWidth=300
         */
        fun buildImageUrl(baseUrl: String, path: String, token: String): String {
            var url = if (path.startsWith("http://") || path.startsWith("https://")) {
                path
            } else {
                baseUrl.trimEnd('/') + (if (path.startsWith("/")) path else "/$path")
            }
            if (token.isNotEmpty() && !url.contains("api_key=")) {
                url += if (url.contains("?")) "&api_key=$token" else "?api_key=$token"
            }
            return url
        }
    }
}
