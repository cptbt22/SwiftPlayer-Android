package com.swiftplayer.data.model

data class SmbServer(
    val id: Long = 0,
    val name: String,
    val address: String,
    val shareName: String,
    val username: String = "",
    val password: String = "",
    val domain: String = ""
) {
    val displayAddress: String
        get() = if (shareName.isNotEmpty()) "$address/$shareName" else address
}
