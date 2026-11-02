package com.swiftplayer.ui.player

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.swiftplayer.R
import com.swiftplayer.data.emby.EmbyPlaybackSync
import com.swiftplayer.data.local.PlaybackHistory
import com.swiftplayer.data.model.SmbServer
import com.swiftplayer.databinding.ActivityPlayerBinding
import com.swiftplayer.player.AudioCaptureService
import com.swiftplayer.player.GestureOverlayView
import com.swiftplayer.player.LiveSubtitleView
import com.swiftplayer.player.PlayerEngine
import com.swiftplayer.player.PlayerSettingsHelper
import com.swiftplayer.player.SpeechToSubtitleManager
import com.swiftplayer.player.SubtitleHelper
import com.swiftplayer.player.ThumbnailExtractor
import com.swiftplayer.ui.components.IndicatorOverlayView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray

class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_APP = "app_settings"
        private const val KEY_ORIENTATION = "player_orientation"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_FILE_SIZE = "extra_file_size"
        const val EXTRA_SOURCE_TYPE = "extra_source_type"
        const val EXTRA_EMBY_ITEM_ID = "extra_emby_item_id"
        const val EXTRA_EMBY_SUBTITLES = "extra_emby_subtitles"
        const val EXTRA_EMBY_BASE_URL = "extra_emby_base_url"
        const val EXTRA_EMBY_TOKEN = "extra_emby_token"
        const val EXTRA_EMBY_PLAY_SESSION_ID = "extra_emby_play_session_id"
        const val EXTRA_EMBY_MEDIA_SOURCE_ID = "extra_emby_media_source_id"
        const val EXTRA_RESUME_POSITION = "extra_resume_position"
        const val EXTRA_SMB_ADDRESS = "extra_smb_address"
        const val EXTRA_SMB_SHARE = "extra_smb_share"
        const val EXTRA_SMB_USERNAME = "extra_smb_username"
        const val EXTRA_SMB_PASSWORD = "extra_smb_password"
        const val EXTRA_SMB_DOMAIN = "extra_smb_domain"

        const val SOURCE_LOCAL = "local"
        const val SOURCE_SMB = "smb"
        const val SOURCE_EMBY = "emby"

        private const val THUMB_DEBOUNCE_MS = 80L
        private const val SAVE_INTERVAL_MS = 3000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerEngine: PlayerEngine
    private lateinit var thumbnailExtractor: ThumbnailExtractor
    private lateinit var playbackHistory: PlaybackHistory
    private lateinit var subtitleHelper: SubtitleHelper
    private lateinit var settingsHelper: PlayerSettingsHelper
    private lateinit var controlsController: PlayerControlsController
    private lateinit var controlsRoot: View
    private var exoPlayer: ExoPlayer? = null

    /** 屏幕方向模式（v2.5.2）：AUTO 跟随视频比例，可循环切换并持久化 */
    private enum class OrientationMode { AUTO, LANDSCAPE, PORTRAIT }
    private var orientationMode = OrientationMode.AUTO

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var savedSpeed = 1f
    private var longPressSpeedActive = false
    private var currentUri: Uri? = null
    private var currentTitle = ""
    private var currentFileSize = 0L
    private var currentSourceType = SOURCE_LOCAL
    private var embyItemId: String? = null
    private var resumePosition = 0L
    private var hasResumed = false
    private var embySync: EmbyPlaybackSync? = null
    private var smbServer: SmbServer? = null
    private var seekPendingForUi = false
    private var thumbGeneration = 0

    private lateinit var audioManager: AudioManager
    private var maxVolume = 15

    private val thumbHandler = Handler(Looper.getMainLooper())
    private val savePositionRunnable = Runnable { saveCurrentPosition() }
    private val hideBufferingRunnable = Runnable {
        if (exoPlayer?.playbackState == Player.STATE_READY) {
            seekPendingForUi = false
            showBuffering(false)
        }
    }
    private val hideSkipRunnable = Runnable {
        binding.skipHint.animate().cancel()
        binding.skipHint.animate().alpha(0f).setDuration(180L).withEndAction {
            binding.skipHint.visibility = View.GONE
            binding.skipHint.alpha = 1f
        }.start()
    }
    private var thumbRunnable: Runnable? = null

    private var speechToSubtitle: SpeechToSubtitleManager? = null
    private var isSpeechSubtitleActive = false
    private var mediaProjectionResultCode = -1
    private var mediaProjectionResultData: Intent? = null

    private val requestAudioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestMediaProjectionLaunch()
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用语音转字幕", Toast.LENGTH_SHORT).show()
        }
    }

    private val launchMediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            mediaProjectionResultCode = result.resultCode
            mediaProjectionResultData = result.data
            startSpeechSubtitle()
        } else {
            Toast.makeText(this, "需要屏幕音频捕获权限", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).let {
            if (it > 0) it else 1
        }

        playbackHistory = PlaybackHistory(this)
        subtitleHelper = SubtitleHelper(this)

        controlsRoot = findViewById(R.id.controlsLayer)

        setupFullScreen()
        setupPlayer()
        settingsHelper = PlayerSettingsHelper(this, binding.playerView, playerEngine)
        setupControls()
        initOrientation()
        setupGestures()
        setupLockOverlay()
        setupSpeechToSubtitle()
        handleIntent()
    }

    private fun setupFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, binding.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---- 屏幕方向（v2.5.2，逻辑参考主流播放器三态循环） ----

    /** 应用方向模式：读视频尺寸（AUTO）→ requestedOrientation，同时持久化与刷新图标 */
    private fun applyOrientationMode(mode: OrientationMode, showToast: Boolean = true) {
        orientationMode = mode
        getSharedPreferences(PREFS_APP, MODE_PRIVATE).edit()
            .putString(KEY_ORIENTATION, mode.name)
            .apply()
        if (::controlsController.isInitialized) {
            controlsController.setOrientationIcon(
                when (mode) {
                    OrientationMode.AUTO -> R.drawable.ic_screen_rotation
                    OrientationMode.LANDSCAPE -> R.drawable.ic_stay_current_landscape
                    OrientationMode.PORTRAIT -> R.drawable.ic_stay_current_portrait
                }
            )
        }
        requestedOrientation = when (mode) {
            OrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.AUTO -> {
                val vs = exoPlayer?.videoSize
                if (vs != null && vs.width > 0 && vs.height > 0 && vs.height > vs.width) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
        if (showToast) {
            val label = when (mode) {
                OrientationMode.AUTO -> "自动（跟随视频）"
                OrientationMode.LANDSCAPE -> "横屏"
                OrientationMode.PORTRAIT -> "竖屏"
            }
            Toast.makeText(this, "方向: $label", Toast.LENGTH_SHORT).show()
        }
    }

    /** 恢复上次的方向模式（controlsController 就绪后调用） */
    private fun initOrientation() {
        val saved = getSharedPreferences(PREFS_APP, MODE_PRIVATE)
            .getString(KEY_ORIENTATION, OrientationMode.AUTO.name) ?: OrientationMode.AUTO.name
        val mode = try {
            OrientationMode.valueOf(saved)
        } catch (_: Exception) {
            OrientationMode.AUTO
        }
        applyOrientationMode(mode, showToast = false)
    }

    private fun setupPlayer() {
        playerEngine = PlayerEngine(this)
        exoPlayer = playerEngine.create()

        binding.playerView.player = exoPlayer
        binding.playerView.useController = false

        thumbnailExtractor = ThumbnailExtractor(this)

        playerEngine.setListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                // AUTO 模式下跟随视频宽高比：竖屏视频竖屏全屏，横屏视频横屏全屏
                if (orientationMode == OrientationMode.AUTO) {
                    applyOrientationMode(OrientationMode.AUTO, showToast = false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (!seekPendingForUi) {
                            showBuffering(true, getString(R.string.buffering))
                        }
                    }
                    Player.STATE_READY -> {
                        seekPendingForUi = false
                        handler.removeCallbacks(hideBufferingRunnable)
                        showBuffering(false)
                        binding.gestureOverlay.durationMs = exoPlayer?.duration ?: 0L
                        // 仅本地流可低延迟抽取缩略图；SMB/Emby 不做阻塞式抽帧
                        if (currentSourceType == SOURCE_LOCAL) {
                            currentUri?.let { thumbnailExtractor.setDataSource(it) }
                        }
                        if (!hasResumed && resumePosition > 0) {
                            exoPlayer?.seekTo(resumePosition)
                            hasResumed = true
                        }
                        embySync?.reportStart(
                            positionMs = exoPlayer?.currentPosition ?: 0L,
                            durationMs = exoPlayer?.duration ?: 0L,
                            isTranscoding = currentUri?.toString()?.contains(".m3u8") == true
                        )
                    }
                    Player.STATE_ENDED -> {
                        saveCurrentPosition()
                        stopSpeechSubtitle()
                        controlsController.show()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startSaveTimer()
                    embySync?.reportProgress(
                        positionMs = exoPlayer?.currentPosition ?: 0L,
                        durationMs = exoPlayer?.duration ?: 0L,
                        isPaused = false,
                        force = true,
                        eventName = "Unpause"
                    )
                } else {
                    saveCurrentPosition()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                seekPendingForUi = false
                showBuffering(false)
                Toast.makeText(this@PlayerActivity, "播放失败：${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun setupControls() {
        controlsController = PlayerControlsController(
            this,
            controlsRoot,
            { exoPlayer },
            object : PlayerControlsController.Listener {
                override fun onBack() {
                    finish()
                }

                override fun onPlayPause() {
                    playerEngine.playPause()
                }

                override fun onSeekPreview(toMs: Long, fromMs: Long) {
                    showSeekPreview(toMs, toMs - fromMs, exoPlayer?.duration ?: 0L)
                }

                override fun onSeek(toMs: Long) {
                    seekTo(toMs)
                }

                override fun onSeekCancel() {
                    hideSeekPreview()
                    controlsController.updateProgressNow()
                }

                override fun onRewind10() {
                    seekRelative(-10_000L)
                }

                override fun onForward10() {
                    seekRelative(10_000L)
                }

                override fun onSpeed() {
                    showSpeedDialog()
                }

                override fun onSubtitle() {
                    showSubtitleDialog()
                }

                override fun onAudioTrack() {
                    showAudioTrackDialog()
                }

                override fun onSpeechSubtitle() {
                    toggleSpeechSubtitle()
                }

                override fun onZoom() {
                    val label = settingsHelper.cycleZoomMode()
                    Toast.makeText(this@PlayerActivity, "画面: $label", Toast.LENGTH_SHORT).show()
                }

                override fun onOrientationCycle() {
                    if (settingsHelper.isLocked) return
                    val next = when (orientationMode) {
                        OrientationMode.AUTO -> OrientationMode.LANDSCAPE
                        OrientationMode.LANDSCAPE -> OrientationMode.PORTRAIT
                        OrientationMode.PORTRAIT -> OrientationMode.AUTO
                    }
                    applyOrientationMode(next)
                }

                override fun onScreenshot() {
                    currentUri?.let { uri ->
                        settingsHelper.captureScreenshot(uri, currentTitle)
                    }
                }

                override fun onLock() {
                    toggleLock()
                }

                override fun onInfo() {
                    currentUri?.let { uri ->
                        val info = settingsHelper.getVideoInfo(uri, currentTitle, currentFileSize)
                        settingsHelper.showVideoInfo(info)
                    }
                }
            }
        )
        controlsController.setSpeedLabel(playerEngine.playbackSpeed)
    }

    private fun setupGestures() {
        binding.gestureOverlay.callback = object : GestureOverlayView.GestureCallback {

            override fun onSingleTap() {
                if (settingsHelper.isLocked) return
                controlsController.toggle()
            }

            override fun onDoubleTap() {
                if (settingsHelper.isLocked) return
                playerEngine.playPause()
            }

            override fun onSkip(deltaMs: Long) {
                if (settingsHelper.isLocked) return
                seekRelative(deltaMs)
                showSkipHint(deltaMs)
            }

            override fun onLongPressStart() {
                if (settingsHelper.isLocked) return
                savedSpeed = playerEngine.playbackSpeed
                longPressSpeedActive = true
                playerEngine.setSpeed(2.0f)
                binding.speedIndicator.setSpeed(2.0f)
                binding.speedIndicator.visibility = View.VISIBLE
            }

            override fun onLongPressSpeedChange(speed: Float) {
                if (settingsHelper.isLocked || !longPressSpeedActive) return
                playerEngine.setSpeed(speed)
                binding.speedIndicator.setSpeed(speed)
            }

            override fun onLongPressEnd() {
                if (!longPressSpeedActive) return
                longPressSpeedActive = false
                playerEngine.setSpeed(savedSpeed)
                binding.speedIndicator.visibility = View.GONE
            }

            override fun onGetCurrentPosition(): Long {
                return exoPlayer?.currentPosition ?: 0L
            }

            override fun onSeekPreview(targetMs: Long, deltaMs: Long, durationMs: Long) {
                if (settingsHelper.isLocked) return
                binding.gestureOverlay.durationMs = durationMs
                showSeekPreview(targetMs, deltaMs, durationMs)
            }

            override fun onSeekCommit(targetMs: Long) {
                hideSeekPreview()
                seekTo(targetMs)
            }

            override fun onBrightnessChange(brightness: Float) {
                if (settingsHelper.isLocked) return
                window.attributes = window.attributes.apply {
                    screenBrightness = brightness
                }
                binding.brightnessIndicator.setTypeWithPercent(
                    IndicatorOverlayView.Type.BRIGHTNESS,
                    (brightness * 100).toInt()
                )
                binding.brightnessIndicator.visibility = View.VISIBLE
            }

            override fun onVolumeChange(volumePercent: Int) {
                if (settingsHelper.isLocked) return
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    volumePercent * maxVolume / 100,
                    0
                )
                binding.volumeIndicator.setTypeWithPercent(
                    IndicatorOverlayView.Type.VOLUME,
                    volumePercent
                )
                binding.volumeIndicator.visibility = View.VISIBLE
            }

            override fun onGestureEnd() {
                binding.brightnessIndicator.visibility = View.GONE
                binding.volumeIndicator.visibility = View.GONE
                binding.seekPreview.visibility = View.GONE
                binding.speedIndicator.visibility = View.GONE
            }
        }
    }

    private fun showSeekPreview(targetMs: Long, deltaMs: Long, durationMs: Long) {
        binding.seekPreview.visibility = View.VISIBLE
        binding.seekPreview.setThumbnailVisible(currentSourceType == SOURCE_LOCAL)
        binding.seekPreview.update(targetMs, durationMs, deltaMs)

        thumbRunnable?.let { thumbHandler.removeCallbacks(it) }
        val generation = ++thumbGeneration
        thumbRunnable = Runnable {
            scope.launch {
                val bitmap = if (currentSourceType == SOURCE_LOCAL && !isDestroyed) {
                    thumbnailExtractor.getFrameAtTime(targetMs)
                } else {
                    null
                }
                if (!isDestroyed && generation == thumbGeneration) {
                    binding.seekPreview.setThumbnail(bitmap)
                }
            }
        }
        thumbHandler.postDelayed(thumbRunnable!!, THUMB_DEBOUNCE_MS)
    }

    private fun hideSeekPreview() {
        thumbGeneration++
        thumbRunnable?.let { thumbHandler.removeCallbacks(it) }
        thumbRunnable = null
        binding.seekPreview.visibility = View.GONE
    }

    private fun seekTo(targetMs: Long) {
        val player = exoPlayer ?: return
        val dur = player.duration
        val target = if (dur > 0) targetMs.coerceIn(0, dur) else targetMs.coerceAtLeast(0)
        val showNetworkFeedback = currentSourceType != SOURCE_LOCAL
        seekPendingForUi = showNetworkFeedback
        if (showNetworkFeedback) showBuffering(true, getString(R.string.seeking))
        playerEngine.seekTo(target)
        embySync?.reportProgress(
            positionMs = target,
            durationMs = dur,
            isPaused = !player.isPlaying,
            force = true
        )
        controlsController.updateProgressNow()
        handler.removeCallbacks(hideBufferingRunnable)
        if (showNetworkFeedback) {
            handler.postDelayed(hideBufferingRunnable, 700L)
        }
    }

    private fun seekRelative(deltaMs: Long) {
        val player = exoPlayer ?: return
        val current = player.currentPosition
        val dur = player.duration
        val target = if (dur > 0) {
            (current + deltaMs).coerceIn(0L, dur)
        } else {
            (current + deltaMs).coerceAtLeast(0L)
        }
        seekTo(target)
    }

    private fun showSkipHint(deltaMs: Long) {
        handler.removeCallbacks(hideSkipRunnable)
        binding.skipHint.animate().cancel()
        binding.skipHint.alpha = 1f
        binding.skipHint.text = if (deltaMs >= 0) "快进 10 秒  ▶▶" else "◀◀  快退 10 秒"
        binding.skipHint.visibility = View.VISIBLE
        handler.postDelayed(hideSkipRunnable, 550L)
    }

    private fun showBuffering(visible: Boolean, text: String = getString(R.string.buffering)) {
        binding.bufferingOverlay.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) binding.bufferingText.text = text
    }

    private fun sourceLabel(): String = when (currentSourceType) {
        SOURCE_SMB -> "局域网 · SMB"
        SOURCE_EMBY -> "Emby"
        else -> "本地"
    }

    private fun setupLockOverlay() {
        binding.btnUnlock.setOnClickListener { toggleLock() }
    }

    private fun showSpeedDialog() {
        settingsHelper.showSpeedSelector(playerEngine.playbackSpeed) { speed ->
            playerEngine.setSpeed(speed)
            controlsController.setSpeedLabel(speed)
        }
    }

    private fun setupSpeechToSubtitle() {
        speechToSubtitle = SpeechToSubtitleManager(this).apply {
            locale = java.util.Locale.JAPANESE
            callback = object : SpeechToSubtitleManager.SubtitleCallback {
                override fun onSubtitleReady(text: String, isPartial: Boolean) {
                    binding.liveSubtitle.setSubtitle(text, isPartial)
                    binding.liveSubtitle.visibility = View.VISIBLE
                }

                override fun onRecognitionError(error: String) {
                    binding.liveSubtitle.setStatus(SpeechToSubtitleManager.Status.ERROR)
                }

                override fun onStatusChanged(status: SpeechToSubtitleManager.Status) {
                    binding.liveSubtitle.setStatus(status)
                }
            }
        }
    }

    private fun toggleSpeechSubtitle() {
        if (isSpeechSubtitleActive) {
            stopSpeechSubtitle()
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Toast.makeText(this, "语音转字幕需要 Android 10+", Toast.LENGTH_SHORT).show()
                return
            }

            if (!speechToSubtitle?.isAvailable()!!) {
                Toast.makeText(this, "此设备不支持语音识别，请安装日语离线语音包", Toast.LENGTH_LONG).show()
                return
            }

            checkAndRequestAudioPermission()
        }
    }

    private fun checkAndRequestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            requestMediaProjectionLaunch()
        } else {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestMediaProjectionLaunch() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        launchMediaProjection.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun startSpeechSubtitle() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        isSpeechSubtitleActive = true

        mediaProjectionResultData?.let { data ->
            AudioCaptureService.start(this, mediaProjectionResultCode, data)
            AudioCaptureService.audioRecordCallback = { _ -> }
        }

        speechToSubtitle?.start()

        binding.liveSubtitle.visibility = View.VISIBLE
        binding.liveSubtitle.isActive = true

        controlsRoot.findViewById<ImageButton>(R.id.btn_speech_subtitle)
            ?.setImageResource(R.drawable.ic_mic_active)
        Toast.makeText(this, "语音转字幕已开启 (${speechToSubtitle?.getLanguageDisplayName()})", Toast.LENGTH_SHORT).show()
    }

    private fun stopSpeechSubtitle() {
        val wasActive = isSpeechSubtitleActive
        isSpeechSubtitleActive = false

        speechToSubtitle?.stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && AudioCaptureService.isRunning) {
            AudioCaptureService.stop(this)
        }
        AudioCaptureService.audioRecordCallback = null

        binding.liveSubtitle.clearSubtitle()
        binding.liveSubtitle.visibility = View.GONE
        binding.liveSubtitle.isActive = false

        if (wasActive) {
            controlsRoot.findViewById<ImageButton>(R.id.btn_speech_subtitle)
                ?.setImageResource(R.drawable.ic_mic)
        }
    }

    private fun showSubtitleDialog() {
        val player = exoPlayer ?: return
        val tracks = subtitleHelper.getSubtitleTracks(player)
        val items = mutableListOf("关闭字幕", "语音转字幕 (日语)")
        tracks.forEach { items.add(it.label) }

        android.app.AlertDialog.Builder(this)
            .setTitle("字幕")
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        subtitleHelper.selectSubtitleTrack(player, null)
                        stopSpeechSubtitle()
                    }
                    which == 1 -> toggleSpeechSubtitle()
                    else -> {
                        stopSpeechSubtitle()
                        subtitleHelper.selectSubtitleTrack(player, tracks[which - 2])
                    }
                }
            }
            .show()
    }

    private fun showAudioTrackDialog() {
        val exo = exoPlayer ?: return
        val audioTracks = mutableListOf<Pair<String, Pair<Int, androidx.media3.common.Tracks.Group>>>()

        for (group in exo.currentTracks.groups) {
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label ?: format.language ?: "音轨 ${trackIndex + 1}"
                    audioTracks.add(label to (trackIndex to group))
                }
            }
        }

        if (audioTracks.isEmpty()) {
            Toast.makeText(this, "无可用音轨", Toast.LENGTH_SHORT).show()
            return
        }

        val items = audioTracks.map { it.first }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("音轨")
            .setItems(items) { _, which ->
                val (trackIndex, group) = audioTracks[which].second
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        androidx.media3.common.TrackSelectionOverride(
                            group.mediaTrackGroup,
                            listOf(trackIndex)
                        )
                    )
                    .build()
            }
            .show()
    }

    private fun toggleLock() {
        val locked = settingsHelper.toggleLock()

        if (locked) {
            controlsController.disableForLock()
            binding.lockOverlay.visibility = View.VISIBLE
            Toast.makeText(this, "屏幕已锁定", Toast.LENGTH_SHORT).show()
        } else {
            controlsController.enableFromLock()
            binding.lockOverlay.visibility = View.GONE
            Toast.makeText(this, "屏幕已解锁", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIntent() {
        currentUri = intent.data ?: return
        currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        currentFileSize = intent.getLongExtra(EXTRA_FILE_SIZE, 0L)
        currentSourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: SOURCE_LOCAL
        embyItemId = intent.getStringExtra(EXTRA_EMBY_ITEM_ID)

        controlsController.setTitle(currentTitle)
        controlsController.setSourceLabel(sourceLabel())

        if (currentSourceType == SOURCE_SMB) {
            val address = intent.getStringExtra(EXTRA_SMB_ADDRESS).orEmpty()
            val share = intent.getStringExtra(EXTRA_SMB_SHARE).orEmpty()
            smbServer = SmbServer(
                name = share.ifEmpty { address },
                address = address,
                shareName = share,
                username = intent.getStringExtra(EXTRA_SMB_USERNAME).orEmpty(),
                password = intent.getStringExtra(EXTRA_SMB_PASSWORD).orEmpty(),
                domain = intent.getStringExtra(EXTRA_SMB_DOMAIN).orEmpty()
            )
            playerEngine.setSmbServer(smbServer)
        } else {
            playerEngine.setSmbServer(null)
        }

        if (currentSourceType == SOURCE_EMBY) {
            val baseUrl = intent.getStringExtra(EXTRA_EMBY_BASE_URL).orEmpty()
            val token = intent.getStringExtra(EXTRA_EMBY_TOKEN).orEmpty()
            val playSessionId = intent.getStringExtra(EXTRA_EMBY_PLAY_SESSION_ID)
                ?.ifEmpty { java.util.UUID.randomUUID().toString() }
                ?: java.util.UUID.randomUUID().toString()
            val itemId = embyItemId
            if (baseUrl.isNotEmpty() && token.isNotEmpty() && !itemId.isNullOrEmpty()) {
                val mediaSourceId = intent.getStringExtra(EXTRA_EMBY_MEDIA_SOURCE_ID).orEmpty()
                embySync = EmbyPlaybackSync(
                    baseUrl,
                    token,
                    itemId,
                    playSessionId,
                    mediaSourceId
                )
            }
        }

        val serverResume = intent.getLongExtra(EXTRA_RESUME_POSITION, 0L)
        resumePosition = maxOf(loadResumePosition(), serverResume.coerceAtLeast(0L))

        val uri = currentUri ?: return
        if (currentSourceType == SOURCE_EMBY) {
            // Emby 网络流：api_key 已拼入 URL；若有外挂字幕流则 side-load 后播放
            val subtitleConfigs = buildEmbySubtitleConfigs(intent.getStringExtra(EXTRA_EMBY_SUBTITLES))
            if (subtitleConfigs.isNotEmpty()) {
                playerEngine.play(uri, currentTitle, subtitleConfigs = subtitleConfigs)
            } else {
                playerEngine.play(uri, currentTitle)
            }
        } else if (currentSourceType == SOURCE_SMB) {
            playerEngine.play(uri, currentTitle)
        } else {
            val subtitles = subtitleHelper.findExternalSubtitles(uri)
            if (subtitles.isNotEmpty()) {
                val mediaItem = subtitleHelper.buildMediaItemWithSubtitles(uri, currentTitle, subtitles)
                exoPlayer?.apply {
                    setMediaItem(mediaItem)
                    prepare()
                    play()
                }
                Toast.makeText(this, "已加载 ${subtitles.size} 个字幕文件", Toast.LENGTH_SHORT).show()
            } else {
                playerEngine.play(uri, currentTitle)
            }
        }
    }

    /**
     * 解析 Emby 外挂字幕流 extra（JSON 数组）并构建 ExoPlayer side-load 配置。
     * 容错：非法/空 JSON 返回空列表，播放不受影响。
     * 统一以 SRT 处理（Emby 已按 .srt 输出，服务端完成格式转换）。
     */
    private fun buildEmbySubtitleConfigs(json: String?): List<MediaItem.SubtitleConfiguration> {
        if (json.isNullOrEmpty()) return emptyList()
        val result = mutableListOf<MediaItem.SubtitleConfiguration>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val url = o.optString("url")
                if (url.isEmpty()) continue
                val language = o.optString("language").ifEmpty { null }
                val label = o.optString("label").ifEmpty { language ?: "字幕 ${i + 1}" }
                val isDefault = o.optBoolean("default", false)
                result.add(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage(language)
                        .setLabel(label)
                        .setSelectionFlags(if (isDefault) C.SELECTION_FLAG_DEFAULT else 0)
                        .build()
                )
            }
        } catch (_: Exception) {
            // 忽略解析异常，返回已解析部分
        }
        return result
    }

    private fun loadResumePosition(): Long {
        val id = embyItemId
        return if (id != null) {
            playbackHistory.getPosition("emby_$id")
        } else {
            currentUri?.let { playbackHistory.getPosition(it) } ?: 0L
        }
    }

    private fun startSaveTimer() {
        handler.removeCallbacks(savePositionRunnable)
        handler.postDelayed(savePositionRunnable, SAVE_INTERVAL_MS)
    }

    private fun saveCurrentPosition() {
        val uri = currentUri ?: return
        val pos = exoPlayer?.currentPosition ?: 0
        val dur = exoPlayer?.duration ?: 0
        if (pos > 0 && dur > 0) {
            val id = embyItemId
            if (id != null) {
                playbackHistory.savePosition("emby_$id", pos, dur)
            } else {
                playbackHistory.savePosition(uri, pos, dur)
            }
            embySync?.reportProgress(
                positionMs = pos,
                durationMs = dur,
                isPaused = exoPlayer?.isPlaying != true
            )
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
        saveCurrentPosition()
        embySync?.reportProgress(
            positionMs = exoPlayer?.currentPosition ?: 0L,
            durationMs = exoPlayer?.duration ?: 0L,
            isPaused = true,
            force = true,
            eventName = "Pause"
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSpeechSubtitle()
        handler.removeCallbacksAndMessages(null)
        thumbHandler.removeCallbacksAndMessages(null)
        embySync?.release(
            positionMs = exoPlayer?.currentPosition ?: 0L,
            durationMs = exoPlayer?.duration ?: 0L
        )
        handler.removeCallbacks(hideBufferingRunnable)
        handler.removeCallbacks(hideSkipRunnable)
        scope.cancel()
        thumbnailExtractor.release()
        controlsController.release()
        playerEngine.release()
    }
}
