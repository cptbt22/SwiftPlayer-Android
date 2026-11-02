package com.swiftplayer.data.local

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * 播放历史记录 - 保存/恢复每个视频的播放位置
 */
class PlaybackHistory(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("playback_history", Context.MODE_PRIVATE)

    /**
     * 保存播放位置
     */
    fun savePosition(uri: Uri, positionMs: Long, durationMs: Long) {
        val key = uriToKey(uri)
        prefs.edit()
            .putLong("${key}_pos", positionMs)
            .putLong("${key}_dur", durationMs)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * 获取上次播放位置，0 表示未记录或已播完
     */
    fun getPosition(uri: Uri): Long {
        val key = uriToKey(uri)
        val pos = prefs.getLong("${key}_pos", 0)
        val dur = prefs.getLong("${key}_dur", 0)
        // 如果距离结尾不到 5 秒，认为已播完，从头开始
        if (dur > 0 && pos > dur - 5000) return 0
        return pos
    }

    /**
     * 保存播放位置（String key 重载，供 Emby itemId 复用，key 形如 "emby_<itemId>"）
     */
    fun savePosition(key: String, positionMs: Long, durationMs: Long) {
        prefs.edit()
            .putLong("${key}_pos", positionMs)
            .putLong("${key}_dur", durationMs)
            .putLong("${key}_time", System.currentTimeMillis())
            .apply()
    }

    /**
     * 获取上次播放位置（String key 重载），0 表示未记录或已播完
     */
    fun getPosition(key: String): Long {
        val pos = prefs.getLong("${key}_pos", 0)
        val dur = prefs.getLong("${key}_dur", 0)
        if (dur > 0 && pos > dur - 5000) return 0
        return pos
    }

    /**
     * 清除某 key 的记录（String key 重载）
     */
    fun clear(key: String) {
        prefs.edit()
            .remove("${key}_pos")
            .remove("${key}_dur")
            .remove("${key}_time")
            .apply()
    }

    /**
     * 清除某视频的记录
     */
    fun clear(uri: Uri) {
        val key = uriToKey(uri)
        prefs.edit()
            .remove("${key}_pos")
            .remove("${key}_dur")
            .remove("${key}_time")
            .apply()
    }

    /**
     * 获取最近播放列表
     */
    fun getRecentList(limit: Int = 20): List<RecentItem> {
        val items = mutableListOf<RecentItem>()
        // 按 key 前缀分组
        val keys = prefs.all.keys
            .filter { it.endsWith("_time") }
            .map { it.removeSuffix("_time") }

        for (key in keys) {
            val pos = prefs.getLong("${key}_pos", 0)
            val dur = prefs.getLong("${key}_dur", 0)
            val time = prefs.getLong("${key}_time", 0)
            if (pos > 0 && dur > 0) {
                items.add(RecentItem(key, pos, dur, time))
            }
        }

        return items.sortedByDescending { it.timestamp }.take(limit)
    }

    private fun uriToKey(uri: Uri): String {
        // 使用 URI 的 hashCode 作为 key，避免 SharedPreferences key 过长
        return "vid_${uri.toString().hashCode().toUInt()}"
    }

    data class RecentItem(
        val key: String,
        val positionMs: Long,
        val durationMs: Long,
        val timestamp: Long
    )
}
