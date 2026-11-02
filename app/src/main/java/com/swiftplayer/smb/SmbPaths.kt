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
