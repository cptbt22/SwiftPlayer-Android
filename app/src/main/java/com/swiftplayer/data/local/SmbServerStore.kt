package com.swiftplayer.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.swiftplayer.data.model.SmbServer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 最近一次连接成功的 SMB 服务器。
 *
 * 密码使用 Android Keystore 内的不可导出 AES 密钥加密后再写入私有
 * SharedPreferences；卸载应用后密钥与数据一并失效。
 */
class SmbServerStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = SmbCredentialCipher()

    fun load(): SmbServer? {
        val address = prefs.getString(KEY_ADDRESS, null).orEmpty()
        if (address.isEmpty()) return null
        return SmbServer(
            name = prefs.getString(KEY_NAME, address).orEmpty().ifEmpty { address },
            address = address,
            shareName = prefs.getString(KEY_SHARE, "").orEmpty(),
            username = prefs.getString(KEY_USERNAME, "").orEmpty(),
            password = cipher.decrypt(prefs.getString(KEY_PASSWORD, "").orEmpty()),
            domain = prefs.getString(KEY_DOMAIN, "").orEmpty()
        )
    }

    fun save(server: SmbServer) {
        prefs.edit()
            .putString(KEY_NAME, server.name)
            .putString(KEY_ADDRESS, server.address)
            .putString(KEY_SHARE, server.shareName)
            .putString(KEY_USERNAME, server.username)
            .putString(KEY_PASSWORD, cipher.encrypt(server.password))
            .putString(KEY_DOMAIN, server.domain)
            .apply()
    }

    private class SmbCredentialCipher {
        fun encrypt(value: String): String {
            if (value.isEmpty()) return ""
            return runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
                Base64.encodeToString(payload, Base64.NO_WRAP)
            }.getOrDefault("")
        }

        fun decrypt(value: String): String {
            if (value.isEmpty()) return ""
            return runCatching {
                val payload = Base64.decode(value, Base64.NO_WRAP)
                if (payload.size <= IV_SIZE) {
                    ""
                } else {
                    val cipher = Cipher.getInstance(TRANSFORMATION)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        getOrCreateKey(),
                        GCMParameterSpec(TAG_SIZE_BITS, payload, 0, IV_SIZE)
                    )
                    String(
                        cipher.doFinal(payload, IV_SIZE, payload.size - IV_SIZE),
                        Charsets.UTF_8
                    )
                }
            }.getOrDefault("")
        }

        private fun getOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
        }
    }

    companion object {
        private const val PREFS_NAME = "smb_store"
        private const val KEY_NAME = "name"
        private const val KEY_ADDRESS = "address"
        private const val KEY_SHARE = "share"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DOMAIN = "domain"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "swiftplayer_smb_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_SIZE_BITS = 128
    }
}
