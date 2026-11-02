package com.swiftplayer.smb

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.swiftplayer.data.model.SmbServer
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SMB 随机读取数据源。
 *
 * 关键点：
 * - 真正的 `DataSource` 实现，支持 ExoPlayer 按 position/length 发起区间读取；
 * - SMB 连接/会话按播放生命周期复用，seek 只重开文件句柄，不重建 TCP + 认证；
 * - 关闭文件在 IO 读取路径上同步执行，但连接保持到 [SmbConnectionProvider.release]。
 */
class SmbDataSource private constructor(
    private val provider: SmbConnectionProvider
) : BaseDataSource(true) {

    private var openedFile: File? = null
    private var filePosition = 0L
    private var bytesRemaining = 0L
    private var currentUri: Uri? = null
    private var transferStarted = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        closeQuietly()

        val (shareName, path) = parseSmbUri(dataSpec.uri)
        if (shareName.isBlank() || path.isBlank()) {
            throw IOException("Invalid SMB uri: ${dataSpec.uri}")
        }

        currentUri = dataSpec.uri
        val handle = provider.openFile(shareName, path)
        openedFile = handle.file
        filePosition = dataSpec.position
        if (filePosition > handle.length) {
            provider.closeFile(handle.file)
            openedFile = null
            throw IOException("SMB read position out of range: $filePosition / ${handle.length}")
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length.coerceAtMost(handle.length - filePosition)
        } else {
            handle.length - filePosition
        }
        transferStarted = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val file = openedFile ?: throw IOException("SMB file is not open")
        val toRead = length.toLong().coerceAtMost(bytesRemaining).toInt()
        val read = file.read(buffer, filePosition, offset, toRead)
        if (read <= 0) return C.RESULT_END_OF_INPUT
        filePosition += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        closeQuietly()
        if (transferStarted) {
            transferStarted = false
            transferEnded()
        }
    }

    private fun closeQuietly() {
        val file = openedFile
        openedFile = null
        if (file != null) provider.closeFile(file)
    }

    private fun parseSmbUri(uri: Uri): Pair<String, String> {
        val host = uri.host?.let { Uri.decode(it) }
        val rawPath = uri.path ?: ""
        if (!host.isNullOrBlank() && rawPath.isNotBlank()) {
            return host to rawPath.trim('/')
        }
        val text = uri.toString().removePrefix("smb://")
        val share = Uri.decode(text.substringBefore('/'))
        val path = Uri.decode(text.substringAfter('/', ""))
        return share to path.trim('/')
    }

    class Factory(private val provider: SmbConnectionProvider) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(provider)
    }
}

/**
 * SMB 连接/会话/共享句柄池，按一次播放生命周期持有。
 * ExoPlayer 每次区间读取会创建/关闭 [File]，但认证连接只建立一次。
 */
class SmbConnectionProvider(private val server: SmbServer) {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = ConcurrentHashMap<String, DiskShare>()
    private var released = false

    @Synchronized
    fun openFile(shareName: String, path: String): SmbFileHandle {
        if (released) throw IOException("SMB connection is released")
        ensureConnected()
        val share = shares.getOrPut(shareName) {
            session?.connectShare(shareName) as DiskShare
        }
        // ★ v2.5.0 修复：smbj 要求 `\` 分隔符；uri 里的 `/` 路径必须转换，否则服务端按无效名处理
        val unc = SmbPaths.toUncPath(path)
        val info = share.getFileInformation(unc)
        val file = share.openFile(
            unc,
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            EnumSet.of(
                SMB2ShareAccess.FILE_SHARE_READ,
                SMB2ShareAccess.FILE_SHARE_WRITE
            ),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        return SmbFileHandle(file, info.standardInformation.endOfFile)
    }

    @Synchronized
    fun closeFile(file: File) {
        runCatching { file.close() }
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        shares.values.forEach { share -> runCatching { share.close() } }
        shares.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null
        connection = null
        client = null
    }

    private fun ensureConnected() {
        if (session != null && connection?.isConnected == true) return

        shares.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        connection = null
        session = null

        val config = SmbConfig.builder()
            .withReadBufferSize(1024 * 1024)
            .withSoTimeout(8000)
            .withReadTimeout(12, TimeUnit.SECONDS)
            .build()
        client = SMBClient(config)
        val (host, port) = parseHostPort(server.address)
        connection = client!!.connect(host, port)
        val auth = if (server.username.isNotEmpty()) {
            AuthenticationContext(
                server.username,
                server.password.toCharArray(),
                server.domain
            )
        } else {
            AuthenticationContext.anonymous()
        }
        session = connection?.authenticate(auth)
    }

    private fun parseHostPort(address: String): Pair<String, Int> {
        val cleaned = address.removePrefix("smb://").removeSuffix("/")
        val parts = cleaned.split(":")
        return if (parts.size == 2) {
            parts[0] to (parts[1].toIntOrNull() ?: DEFAULT_SMB_PORT)
        } else {
            cleaned to DEFAULT_SMB_PORT
        }
    }

    companion object {
        private const val DEFAULT_SMB_PORT = 445
    }
}

data class SmbFileHandle(
    val file: File,
    val length: Long
)
