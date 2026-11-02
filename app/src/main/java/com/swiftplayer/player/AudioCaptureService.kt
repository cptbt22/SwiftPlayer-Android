package com.swiftplayer.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

@RequiresApi(Build.VERSION_CODES.Q)
class AudioCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "audio_capture"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val EXTRA_RESULT_CODE = "result_code"

        var isRunning = false
            private set

        var audioRecordCallback: ((ByteArray) -> Unit)? = null

        private var cachedResultCode: Int = -1
        private var cachedResultData: Intent? = null

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            cachedResultCode = resultCode
            cachedResultData = resultData
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var isCapturing = false

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = cachedResultData

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    startForeground(NOTIFICATION_ID, createNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } else {
                    startForeground(NOTIFICATION_ID, createNotification())
                }

                isRunning = true
                if (resultData != null) {
                    startCapture(resultCode, resultData)
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(resultCode: Int, resultData: Intent) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: run { stopSelf(); return }

            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build())
                .setBufferSizeInBytes(bufferSize)
                .build()

            captureThread = HandlerThread("AudioCapture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            audioRecord?.startRecording()
            isCapturing = true

            captureHandler?.post(this::captureLoop)
        } catch (e: Exception) {
            e.printStackTrace()
            stopCapture()
            stopSelf()
        }
    }

    private fun captureLoop() {
        if (!isCapturing) return

        val buffer = ShortArray(1024)
        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0

        if (read > 0) {
            val bytes = ByteArray(read * 2)
            for (i in 0 until read) {
                val sample = buffer[i]
                bytes[i * 2] = (sample.toInt() and 0xFF).toByte()
                bytes[i * 2 + 1] = (sample.toInt() shr 8 and 0xFF).toByte()
            }
            audioRecordCallback?.invoke(bytes)
        }

        if (isCapturing) {
            captureHandler?.postDelayed(this::captureLoop, 10)
        }
    }

    private fun stopCapture() {
        isCapturing = false
        isRunning = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null

        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        cachedResultData = null
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "音频捕获",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于语音转字幕功能"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SwiftPlayer")
            .setContentText("正在语音转字幕...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
