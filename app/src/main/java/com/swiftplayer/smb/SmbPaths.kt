package com.swiftplayer.smb

/**
 * SMB 路径工具（v2.5.0）。
 *
 * smbj 官方口径：SMB 路径必须用 `\` 作为分隔符，使用 `/` 会报错或得到错误结果
 * （hierynomus/smbj#296）。应用内部统一用 `/`（Uri 友好），调用 smbj 前经此转换。
 */
object SmbPaths {

    /**
     * 转为 smbj 可用的 share 内相对路径。
     * 根目录返回 ""；多级目录 "dir/sub/file.mkv" → "dir\sub\file.mkv"。
     */
    fun toUncPath(path: String): String {
        val trimmed = path.trim().trim('/', '\\')
        if (trimmed.isEmpty()) return ""
        return trimmed.replace('/', '\\')
    }

    /** 供 UI/Uri 拼接用的 `/` 风格路径（保证以 / 开头或为空） */
    fun toUriPath(path: String): String {
        val trimmed = path.trim().trim('/', '\\')
        return if (trimmed.isEmpty()) "" else "/${trimmed.replace('\\', '/')}"
    }

    /** 父目录（`/` 风格）；根目录返回 "" */
    fun parentOf(path: String): String {
        val trimmed = path.trim('/')
        if (trimmed.isEmpty()) return ""
        val parent = trimmed.substringBeforeLast('/', "")
        return if (parent.isEmpty()) "/" else "/$parent"
    }
}

/** 浏览与播放共用的 SMB 服务器地址，避免两套解析逻辑产生差异。 */
data class SmbEndpoint(val host: String, val port: Int)

object SmbAddresses {
    const val DEFAULT_PORT = 445

    /**
     * 接受 `192.168.1.2`、`192.168.1.2:445`、`smb://host:445` 及带路径的输入。
     * IPv6 可使用 `[::1]:445`；未加方括号时按纯 IPv6 地址处理并使用默认端口。
     */
    fun parse(address: String): SmbEndpoint {
        var value = address.trim()
        require(value.isNotEmpty()) { "请输入 SMB 服务器地址" }
        if (value.contains("://")) {
            require(value.startsWith("smb://", ignoreCase = true)) { "仅支持 smb:// 地址" }
            value = value.substringAfter("://")
        }
        value = value.substringBefore('/').substringBefore('?').substringBefore('#').trim()
        require(value.isNotEmpty()) { "SMB 服务器主机不能为空" }

        if (value.startsWith("[")) {
            val closingBracket = value.indexOf(']')
            require(closingBracket > 1) { "IPv6 地址格式不正确" }
            val host = value.substring(1, closingBracket)
            val suffix = value.substring(closingBracket + 1)
            val port = if (suffix.isEmpty()) {
                DEFAULT_PORT
            } else {
                require(suffix.startsWith(":")) { "SMB 端口格式不正确" }
                parsePort(suffix.substring(1))
            }
            return SmbEndpoint(host, port)
        }

        // 单个冒号才是 host:port；多个冒号按未指定端口的 IPv6 地址处理。
        if (value.count { it == ':' } == 1) {
            val host = value.substringBefore(':').trim()
            require(host.isNotEmpty()) { "SMB 服务器主机不能为空" }
            return SmbEndpoint(host, parsePort(value.substringAfter(':')))
        }
        return SmbEndpoint(value, DEFAULT_PORT)
    }

    private fun parsePort(value: String): Int {
        val port = value.toIntOrNull()
        require(port != null && port in 1..65535) { "SMB 端口必须是 1–65535 的数字" }
        return port
    }
}
