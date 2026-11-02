package com.swiftplayer.data.emby

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Emby 服务器与 token 的持久化（SharedPreferences: emby_store）
 *
 * Key 约定（§7.1）：
 * - servers_json      String(JSON Array)  默认 "[]"
 * - current_server_id String              默认 ""
 * - device_id         String(UUID)        首次生成
 */
class EmbyAuthManager(context: Context) {

    private val prefs = context.getSharedPreferences("emby_store", Context.MODE_PRIVATE)

    fun getServers(): List<EmbyServer> {
        val raw = prefs.getString(KEY_SERVERS, "[]") ?: "[]"
        val list = mutableListOf<EmbyServer>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    EmbyServer(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        baseUrl = o.optString("baseUrl"),
                        lanBaseUrl = o.optString("lanBaseUrl"),
                        userId = o.optString("userId"),
                        accessToken = o.optString("accessToken"),
                        lastConnected = o.optLong("lastConnected", 0L)
                    )
                )
            }
        } catch (_: Exception) {
            // 解析失败时回退为空列表
        }
        return list
    }

    fun upsert(server: EmbyServer) {
        val list = getServers().toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) {
            list[idx] = server
        } else {
            list.add(server)
        }
        saveServers(list)
    }

    fun remove(serverId: String) {
        val list = getServers().filterNot { it.id == serverId }
        saveServers(list)
        if (prefs.getString(KEY_CURRENT, "") == serverId) {
            prefs.edit().putString(KEY_CURRENT, "").apply()
        }
    }

    fun current(): EmbyServer? {
        val id = prefs.getString(KEY_CURRENT, "") ?: ""
        if (id.isEmpty()) return null
        return getServers().firstOrNull { it.id == id }
    }

    fun setCurrent(serverId: String) {
        prefs.edit().putString(KEY_CURRENT, serverId).apply()
    }

    fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrEmpty()) return existing
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    private fun saveServers(list: List<EmbyServer>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("baseUrl", s.baseUrl)
                put("lanBaseUrl", s.lanBaseUrl)
                put("userId", s.userId)
                put("accessToken", s.accessToken)
                put("lastConnected", s.lastConnected)
            })
        }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_SERVERS = "servers_json"
        private const val KEY_CURRENT = "current_server_id"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
