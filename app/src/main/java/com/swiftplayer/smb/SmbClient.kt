package com.swiftplayer.smb

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.swiftplayer.data.model.SmbServer
import com.swiftplayer.data.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * SMB 浏览客户端（v2.5.0 重构）。
 *
 * 相对 v2.4.0 的修复：
 * - **路径分隔符**：smbj 要求 `\`（官方 issue #296），调用前统一经 [SmbPaths.toUncPath] 转换——
 *   此前浏览传 "/"、播放传 "/dir/file"，服务端按无效名处理，表现为「找不到影片」。
 * - **错误归因**：connect/list 不再吞异常返回 false/空列表，而是分类为中文提示
 *   （服务未开启 / IP 错 / 账号密码错 / 无权限 / 共享不存在 / 超时）。
 * - **共享自动识别**：share 留空时按候选名探测（mov 在首位——飞牛媒体盘惯例命名）。
 * - **目录浏览**：子目录作为条目返回，可逐级进入；返回键逐级回退。
 * - **显式超时**：默认 config 无连接超时，错 IP 会挂 60s+。
 */
class SmbClient {

    /** 携带面向用户中文文案的 SMB 异常 */
    class SmbException(message: String) : Exception(message)

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null

    /** 建立 TCP + NTLM 认证；成功后再用 [resolveShare] 确定共享名 */
    suspend fun connect(server: SmbServer): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            disconnect()
            val config = SmbConfig.builder()
                .withTimeout(20, TimeUnit.SECONDS)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .build()
            val newClient = SMBClient(config)
            client = newClient

            val (host, port) = parseHostPort(server.address)
            val conn = newClient.connect(host, port)
            connection = conn
            if (!conn.isConnected) {
                throw SmbException("无法建立连接，请检查地址")
            }

            val authContext = if (server.username.isNotEmpty()) {
                AuthenticationContext(
                    server.username,
                    server.password.toCharArray(),
                    server.domain
                )
            } else {
                AuthenticationContext.anonymous()
            }
            session = conn.authenticate(authContext)
            Result.success(Unit)
        } catch (e: Exception) {
            disconnect()
            Result.failure(classifyError(e, server.address, server.shareName))
        }
    }

    /**
     * 解析实际可用的共享名：
     * - 用户填写了 share → 验证其存在（不存在给出明确提示）；
     * - 留空 → 按候选名逐一探测，命中即返回。
     */
    suspend fun resolveShare(server: SmbServer): Result<String> = withContext(Dispatchers.IO) {
        val sess = session
            ?: return@withContext Result.failure(SmbException("尚未连接服务器，请先连接"))
        try {
            val requested = server.shareName.trim().trim('/')
            if (requested.isNotEmpty()) {
                if (tryShare(sess, requested)) {
                    return@withContext Result.success(requested)
                }
                return@withContext Result.failure(
                    SmbException("共享「$requested」不存在或无权访问，请核对飞牛「文件管理 → SMB」中的共享名称")
                )
            }
            for (candidate in SHARE_CANDIDATES) {
                if (tryShare(sess, candidate)) {
                    return@withContext Result.success(candidate)
                }
            }
            Result.failure(SmbException(SHARE_NOT_FOUND_MSG))
        } catch (e: Exception) {
            Result.failure(classifyError(e, server.address, ""))
        }
    }

    /** 探测共享是否可连（连接即验证，用完即关，不保留句柄） */
    private fun tryShare(sess: Session, name: String): Boolean {
        var share: DiskShare? = null
        return try {
            share = sess.connectShare(name) as? DiskShare ?: return false
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                share?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 列出目录内容：子目录 + 视频文件（目录在前、名称排序）。
     * @param path `/` 风格的 share 内路径，"" 或 "/" 表示根目录
     */
    suspend fun listDirectory(shareName: String, path: String): Result<List<VideoItem>> =
        withContext(Dispatchers.IO) {
            var share: DiskShare? = null
            try {
                val sess = session
                    ?: return@withContext Result.failure(SmbException("尚未连接服务器，请先连接"))
                share = sess.connectShare(shareName) as? DiskShare
                    ?: return@withContext Result.failure(
                        SmbException("共享「$shareName」不可访问，请核对共享名称与权限")
                    )

                // ★ 关键修复：smbj 路径必须用 `\`，根目录传空串
                val unc = SmbPaths.toUncPath(path)
                val base = SmbPaths.toUriPath(path)

                val items = mutableListOf<VideoItem>()
                for (entry in share.list(unc)) {
                    val name = entry.fileName
                    if (name == "." || name == "..") continue

                    val isDir = (entry.fileAttributes and 0x10L) != 0L
                    val uriPath = if (base.isEmpty()) "/$name" else "$base/$name"
                    if (!isDir && !isVideoFile(name)) continue

                    items.add(
                        VideoItem(
                            id = ("${if (isDir) "dir" else "vid"}:$shareName$uriPath").hashCode().toLong(),
                            title = name,
                            path = uriPath,
                            uri = android.net.Uri.parse("smb://$shareName$uriPath"),
                            duration = 0,
                            size = if (isDir) 0 else entry.endOfFile,
                            isDirectory = isDir
                        )
                    )
                }
                items.sortWith(compareBy({ !it.isDirectory }, { it.title.lowercase() }))
                Result.success(items)
            } catch (e: Exception) {
                Result.failure(classifyError(e, shareName, path))
            } finally {
                try {
                    share?.close()
                } catch (_: Exception) {
                }
            }
        }

    fun disconnect() {
        try {
            session?.close()
            connection?.close()
            client?.close()
        } catch (_: Exception) {
        }
        session = null
        connection = null
        client = null
    }

    // ---- 错误分类（中文归因） ----

    private fun classifyError(e: Exception, target: String, share: String): Exception {
        return when (e) {
            is SmbException -> e
            is UnknownHostException -> SmbException("找不到主机：$target（请检查 IP 是否正确）")
            is SocketTimeoutException -> SmbException(TIMEOUT_MSG)
            is SMBApiException -> when (e.statusCode) {
                STATUS_LOGON_FAILURE,
                STATUS_ACCOUNT_DISABLED,
                STATUS_PASSWORD_EXPIRED,
                STATUS_ACCOUNT_LOCKED_OUT -> SmbException(AUTH_MSG)
                STATUS_ACCESS_DENIED,
                STATUS_NETWORK_ACCESS_DENIED -> SmbException(DENIED_MSG)
                STATUS_BAD_NETWORK_NAME -> SmbException(
                    if (share.isEmpty()) SHARE_NOT_FOUND_MSG else "共享「$share」不存在，请核对飞牛中的共享名称"
                )
                STATUS_OBJECT_PATH_NOT_FOUND,
                STATUS_OBJECT_NAME_NOT_FOUND -> SmbException("路径不存在：$share（目录可能已移动或被删除）")
                else -> SmbException("SMB 错误：${shortMessage(e.message)}")
            }
            is TransportException -> {
                val raw = (e.cause ?: e).message.orEmpty()
                when {
                    raw.contains("refused", ignoreCase = true) ->
                        SmbException("无法连接 $target：SMB 服务未开启或端口不对（飞牛 NAS：文件管理 → SMB，需开启服务并勾选共享目录）")
                    raw.contains("timed out", ignoreCase = true) ||
                        raw.contains("timeout", ignoreCase = true) -> SmbException(TIMEOUT_MSG)
                    raw.contains("unreachable", ignoreCase = true) ->
                        SmbException("网络不可达：请确认设备与 NAS 在同一局域网，且 IP 正确")
                    else -> SmbException("连接失败：${shortMessage(raw)}")
                }
            }
            else -> SmbException("连接失败：${shortMessage(e.message)}")
        }
    }

    private fun shortMessage(message: String?): String =
        message.orEmpty().replace("\n", " ").take(140).ifEmpty { "未知错误" }

    private fun parseHostPort(address: String): Pair<String, Int> {
        val cleaned = address.removePrefix("smb://").removeSuffix("/")
        val host = cleaned.substringBefore(':').ifEmpty { cleaned }
        val port = cleaned.substringAfter(':', "").toIntOrNull() ?: DEFAULT_SMB_PORT
        return host to port
    }

    companion object {
        private const val DEFAULT_SMB_PORT = 445

        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "ts", "3gp",
            "wmv", "asf", "ogv", "m4v", "mpg", "mpeg", "vob"
        )

        /** 共享名探测候选：首位即飞牛媒体盘惯例名 */
        private val SHARE_CANDIDATES = listOf(
            "mov", "media", "movies", "movie", "video", "videos", "film", "films",
            "tv", "share", "public", "data", "common", "downloads", "nas",
            "hdd", "storage", "vol1", "vol2", "sata1", "usb1",
            "媒体", "影片", "电影", "视频", "共享"
        )

        private const val TIMEOUT_MSG =
            "连接超时：请检查 IP 与网络（设备与 NAS 需在同一局域网）"
        private const val AUTH_MSG =
            "账号或密码错误（飞牛 NAS：需使用系统用户名密码，且该用户已在 SMB 共享中被授权）"
        private const val DENIED_MSG =
            "账号无访问权限：请在飞牛 SMB 设置中授权该用户读写共享目录"
        private const val SHARE_NOT_FOUND_MSG =
            "未能识别可访问的共享：请在飞牛「文件管理 → SMB」确认已共享目录，或手动填写共享名称"

        // NTSTATUS（与 NtStatus 枚举值一致；用常量避免跨版本 API 差异）
        private const val STATUS_LOGON_FAILURE = 0xC0000064L
        private const val STATUS_ACCOUNT_LOCKED_OUT = 0xC0000234L
        private const val STATUS_ACCOUNT_DISABLED = 0xC0000072L
        private const val STATUS_PASSWORD_EXPIRED = 0xC0000071L
        private const val STATUS_ACCESS_DENIED = 0xC0000022L
        private const val STATUS_NETWORK_ACCESS_DENIED = 0xC00000CAL
        private const val STATUS_BAD_NETWORK_NAME = 0xC00000CCL
        private const val STATUS_OBJECT_PATH_NOT_FOUND = 0xC000003AL
        private const val STATUS_OBJECT_NAME_NOT_FOUND = 0xC0000034L

        fun isVideoFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }
}
