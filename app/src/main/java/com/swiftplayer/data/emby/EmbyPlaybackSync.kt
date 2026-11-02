package com.swiftplayer.data.emby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Emby 播放进度同步器。
 *
 * 负责把本端播放状态上报到 Emby 服务器：
 * - 播放开始：POST /Sessions/Playing
 * - 播放/暂停/seek：POST /Sessions/Playing/Progress
 * - 退出播放：POST /Sessions/Playing/Stopped
 *
 * 设计约束：
 * - 全部静默失败，进度上报绝不阻塞或打断播放。
 * - 普通进度按 5 秒/5% 阈值节流；seek 立即上报。
 */
class EmbyPlaybackSync(
    private val baseUrl: String,
    private val token: String,
    private val itemId: String,
    private val playSessionId: String,
    private val mediaSourceId: String = ""
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    @Volatile private var started = false
    @Volatile private var stopped = false
    @Volatile private var released = false
    @Volatile private var lastReportedPosition = -1L
    @Volatile private var lastReportedAt = 0L
    @Volatile private var playMethod = "DirectPlay"

    val isActive: Boolean
        get() = token.isNotEmpty() && itemId.isNotEmpty() && !released

    fun reportStart(positionMs: Long, durationMs: Long, isTranscoding: Boolean) {
        if (!isActive || started) return
        started = true
        playMethod = if (isTranscoding) "Transcode" else "DirectPlay"
        lastReportedPosition = positionMs
        lastReportedAt = System.currentTimeMillis()
        post("/Sessions/Playing") {
            it.put("ItemId", itemId)
                .put("PlaySessionId", playSessionId)
                .put("PositionTicks", positionMs.toTicks())
                .put("RunTimeTicks", durationMs.toTicks())
                .put("PlayMethod", if (isTranscoding) "Transcode" else "DirectPlay")
                .put("CanSeek", true)
                .put("IsPaused", false)
                .putOptional("MediaSourceId", mediaSourceId)
        }
    }

    fun reportProgress(
        positionMs: Long,
        durationMs: Long,
        isPaused: Boolean,
        force: Boolean = false,
        eventName: String = if (isPaused) "Pause" else "TimeUpdate"
    ) {
        if (!isActive || !started) return
        val now = System.currentTimeMillis()
        val positionDelta = kotlin.math.abs(positionMs - lastReportedPosition)
        val durationDelta = if (durationMs > 0) durationMs / 20 else 5_000L
        if (!force &&
            positionDelta < durationDelta.coerceAtLeast(5_000L) &&
            now - lastReportedAt < REPORT_INTERVAL_MS
        ) {
            return
        }
        lastReportedPosition = positionMs
        lastReportedAt = now
        post("/Sessions/Playing/Progress") {
            it.put("ItemId", itemId)
                .put("PlaySessionId", playSessionId)
                .put("PositionTicks", positionMs.toTicks())
                .put("RunTimeTicks", durationMs.toTicks())
                .put("IsPaused", isPaused)
                .put("CanSeek", true)
                .put("EventName", eventName)
                .putOptional("MediaSourceId", mediaSourceId)
        }
    }

    fun reportStop(positionMs: Long, durationMs: Long) {
        if (!isActive || stopped) return
        stopped = true
        post("/Sessions/Playing/Stopped") {
            it.put("ItemId", itemId)
                .put("PlaySessionId", playSessionId)
                .put("PositionTicks", positionMs.toTicks())
                .put("RunTimeTicks", durationMs.toTicks())
                .put("PlayMethod", playMethod)
                .putOptional("MediaSourceId", mediaSourceId)
        }
    }

    fun release(positionMs: Long, durationMs: Long) {
        if (released) return
        // 不在主线程同步等待；上报协程在 IO 线程完成后自然结束。
        reportStop(positionMs, durationMs)
        released = true
    }

    private fun post(path: String, buildBody: (JSONObject) -> JSONObject) {
        scope.launch {
            runCatching {
                val body = buildBody(JSONObject()).toString().toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url(baseUrl.trimEnd('/') + path)
                    .header("X-Emby-Token", token)
                    .header(
                        "X-Emby-Authorization",
                        "MediaBrowser Client=\"SwiftPlayer\", Device=\"Android\", DeviceId=\"swiftplayer-android\", Version=\"2.4.0\""
                    )
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    response.close()
                }
            }
        }
    }

    private fun Long.toTicks(): Long = this * 10_000L

    private fun JSONObject.putOptional(key: String, value: String): JSONObject {
        if (value.isNotEmpty()) put(key, value)
        return this
    }

    companion object {
        private const val REPORT_INTERVAL_MS = 5_000L
    }
}
