package com.swiftplayer.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class SpeechToSubtitleManager(private val context: Context) {

    interface SubtitleCallback {
        fun onSubtitleReady(text: String, isPartial: Boolean)
        fun onRecognitionError(error: String)
        fun onStatusChanged(status: Status)
    }

    enum class Status {
        IDLE, LISTENING, PROCESSING, ERROR
    }

    var callback: SubtitleCallback? = null
    var locale: Locale = Locale.JAPANESE
        set(value) {
            field = value
            recreateRecognizer()
        }

    private var speechRecognizer: SpeechRecognizer? = null
    private var isActive = false
    private var consecutiveErrors = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun start() {
        if (!isAvailable()) {
            callback?.onRecognitionError("此设备不支持语音识别")
            return
        }

        if (isActive) return
        isActive = true
        consecutiveErrors = 0

        recreateRecognizer()
        startListening()
        callback?.onStatusChanged(Status.LISTENING)
    }

    fun stop() {
        isActive = false
        cancelPendingRestart()
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        callback?.onStatusChanged(Status.IDLE)
    }

    private fun cancelPendingRestart() {
        restartRunnable?.let { mainHandler.removeCallbacks(it) }
        restartRunnable = null
    }

    private fun recreateRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    consecutiveErrors = 0
                    callback?.onStatusChanged(Status.LISTENING)
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    if (isActive) {
                        callback?.onStatusChanged(Status.PROCESSING)
                    }
                }

                override fun onError(error: Int) {
                    if (!isActive) return

                    consecutiveErrors++

                    val message = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "无法识别"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        else -> "识别错误($error)"
                    }

                    val isRecoverable = error == SpeechRecognizer.ERROR_NO_MATCH ||
                            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                    if (isRecoverable) {
                        scheduleRestart()
                    } else if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        callback?.onRecognitionError("多次识别失败，请检查网络或语音包设置")
                        scheduleRestart(backoffMs = 5000L)
                    } else {
                        callback?.onRecognitionError(message)
                        scheduleRestart(backoffMs = 1000L)
                    }
                }

                override fun onResults(results: Bundle?) {
                    if (!isActive) return

                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        consecutiveErrors = 0
                        callback?.onSubtitleReady(text, false)
                    }
                    scheduleRestart()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    if (text.isNotEmpty()) {
                        callback?.onSubtitleReady(text, true)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            callback?.onRecognitionError("启动语音识别失败: ${e.message}")
        }
    }

    private fun scheduleRestart(backoffMs: Long = 300L) {
        if (!isActive) return
        cancelPendingRestart()

        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}

        restartRunnable = Runnable {
            if (isActive) {
                startListening()
            }
        }
        mainHandler.postDelayed(restartRunnable!!, backoffMs)
    }

    fun getLanguageDisplayName(): String {
        return locale.displayName
    }

    companion object {
        private const val MAX_CONSECUTIVE_ERRORS = 5

        val SUPPORTED_LOCALES: List<Pair<Locale, String>> = listOf(
            Pair(Locale.JAPANESE, "日本語"),
            Pair(Locale.KOREAN, "한국어"),
            Pair(Locale.SIMPLIFIED_CHINESE, "简体中文"),
            Pair(Locale.TRADITIONAL_CHINESE, "繁體中文"),
            Pair(Locale.ENGLISH, "English"),
            Pair(Locale.FRENCH, "Français"),
            Pair(Locale.GERMAN, "Deutsch"),
            Pair(Locale("es"), "Español")
        )
    }
}
