package com.swiftplayer.ui.player

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.swiftplayer.R
import com.swiftplayer.data.model.VideoItem

/**
 * 自研播放控制层（D 方案：PlayerView.useController = false）
 *
 * 职责：
 * - 显隐状态机 HIDDEN/VISIBLE，alpha 属性动画 200ms 淡入淡出
 * - 自动隐藏：VISIBLE 且播放中 5s 无操作 → 隐藏；暂停时不调度隐藏
 * - 交互重置计时：按钮点击 / 拖动进度条 → onUserInteraction()
 * - 进度刷新：Handler 每 500ms 更新 exo_position/exo_duration/DefaultTimeBar
 * - 按钮绑定：通过 [Listener] 回调暴露给 Activity
 *
 * 注意：因 useController=false，PlayerView 不再自动驱动进度条；
 * DefaultTimeBar 仅实现 TimeBar 接口、无 setPlayer，故进度显示完全由
 * 500ms 轮询 setPosition/setDuration/setBufferedPosition 驱动（R9）。
 */
class PlayerControlsController(
    private val activity: Activity,
    private val root: View,
    private val playerProvider: () -> Player?,
    private val listener: Listener
) {

    interface Listener {
        fun onBack()
        fun onPlayPause()
        fun onSeekPreview(toMs: Long, fromMs: Long)
        fun onSeek(toMs: Long)
        fun onSeekCancel()
        fun onRewind10()
        fun onForward10()
        fun onSpeed()
        fun onSubtitle()
        fun onAudioTrack()
        fun onSpeechSubtitle()
        fun onZoom()
        fun onScreenshot()
        fun onLock()
        fun onInfo()
    }

    enum class Visibility { HIDDEN, VISIBLE }

    companion object {
        const val CONTROLS_TIMEOUT = 5000L
        private const val FADE_MS = 200L
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val SEEK_SNAP_GUARD_MS = 1_200L
    }

    var isLocked: Boolean = false
        private set

    private var visibility = Visibility.HIDDEN

    /** 用户正在拖动进度条：暂停轮询刷新，避免与手指位置打架。 */
    private var isScrubbing = false
    private var scrubAnchorMs = 0L
    private var pendingSeekMs: Long? = null
    private var pendingSeekStartedAt = 0L

    private val timeBar: DefaultTimeBar = root.findViewById(R.id.exo_progress)
    private val positionText: TextView = root.findViewById(R.id.exo_position)
    private val durationText: TextView = root.findViewById(R.id.exo_duration)
    private val titleText: TextView = root.findViewById(R.id.exo_title)
    private val sourceText: TextView = root.findViewById(R.id.exo_source)
    private val playPauseBtn: ImageButton = root.findViewById(R.id.exo_play_pause)
    private val rewindBtn: ImageButton = root.findViewById(R.id.exo_rew)
    private val forwardBtn: ImageButton = root.findViewById(R.id.exo_ffwd)
    private val backBtn: ImageButton = root.findViewById(R.id.exo_back)
    private val speedBtn: TextView = root.findViewById(R.id.btn_speed)
    private val subtitleBtn: ImageButton = root.findViewById(R.id.btn_subtitle)
    private val audioBtn: ImageButton = root.findViewById(R.id.btn_audio_track)
    private val speechBtn: ImageButton = root.findViewById(R.id.btn_speech_subtitle)
    private val zoomBtn: ImageButton = root.findViewById(R.id.btn_zoom)
    private val screenshotBtn: ImageButton = root.findViewById(R.id.btn_screenshot)
    private val lockBtn: ImageButton = root.findViewById(R.id.btn_lock)
    private val infoBtn: ImageButton = root.findViewById(R.id.btn_info)
    private val centerPlayBtn: ImageButton = root.findViewById(R.id.btn_center_play)

    private val handler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!isScrubbing) {
                updateProgressNow()
            }
            handler.postDelayed(this, PROGRESS_INTERVAL_MS)
        }
    }

    private val hideRunnable = Runnable { hide() }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshPlayPause()
            if (isPlaying) {
                if (visibility == Visibility.VISIBLE) scheduleHide()
            } else {
                // 暂停不隐藏：取消自动隐藏计时
                handler.removeCallbacks(hideRunnable)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            refreshPlayPause()
            updateProgressNow()
            if (playbackState == Player.STATE_BUFFERING) {
                handler.removeCallbacks(hideRunnable)
            } else if (playbackState == Player.STATE_READY) {
                onUserInteraction()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            pendingSeekMs = null
            if (!isScrubbing) updateProgressNow()
        }
    }

    init {
        bindButtons()
        // 注意：DefaultTimeBar 无 setPlayer（仅实现 TimeBar 接口），
        // 进度显示完全由 500ms 轮询 setPosition/setDuration/setBufferedPosition 驱动（R9）。
        playerProvider()?.addListener(playerListener)
        refreshPlayPause()
        // 初始：纯净态
        root.visibility = View.GONE
        root.alpha = 0f
        startProgressLoop()
    }

    private fun bindButtons() {
        backBtn.setOnClickListener { onInteract(); listener.onBack() }
        playPauseBtn.setOnClickListener { onInteract(); listener.onPlayPause() }
        rewindBtn.setOnClickListener { onInteract(); listener.onRewind10() }
        forwardBtn.setOnClickListener { onInteract(); listener.onForward10() }
        speedBtn.setOnClickListener { onInteract(); listener.onSpeed() }
        subtitleBtn.setOnClickListener { onInteract(); listener.onSubtitle() }
        audioBtn.setOnClickListener { onInteract(); listener.onAudioTrack() }
        speechBtn.setOnClickListener { onInteract(); listener.onSpeechSubtitle() }
        zoomBtn.setOnClickListener { onInteract(); listener.onZoom() }
        screenshotBtn.setOnClickListener { onInteract(); listener.onScreenshot() }
        lockBtn.setOnClickListener { onInteract(); listener.onLock() }
        infoBtn.setOnClickListener { onInteract(); listener.onInfo() }
        // 中央大按钮：与底部播放/暂停按钮同一行为
        centerPlayBtn.setOnClickListener { onInteract(); listener.onPlayPause() }

        timeBar.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) {
                isScrubbing = true
                scrubAnchorMs = playerProvider()?.currentPosition ?: position
                handler.removeCallbacks(hideRunnable)
                renderProgressAt(position, playerProvider()?.duration ?: 0L)
                listener.onSeekPreview(position, scrubAnchorMs)
            }

            override fun onScrubMove(timeBar: TimeBar, position: Long) {
                renderProgressAt(position, playerProvider()?.duration ?: 0L)
                listener.onSeekPreview(position, scrubAnchorMs)
            }

            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
                isScrubbing = false
                if (canceled) {
                    pendingSeekMs = null
                    listener.onSeekCancel()
                } else {
                    pendingSeekMs = position
                    pendingSeekStartedAt = System.currentTimeMillis()
                    listener.onSeek(position)
                }
                updateProgressNow()
                onInteract()
            }
        })
    }

    fun setTitle(title: String) {
        titleText.text = title
    }

    fun setSourceLabel(label: String) {
        sourceText.text = label
    }

    fun setSpeedLabel(speed: Float) {
        speedBtn.text = "${speed}x"
    }

    fun setLockButtonLocked(locked: Boolean) {
        lockBtn.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
    }

    fun show() {
        if (isLocked) return
        visibility = Visibility.VISIBLE
        root.visibility = View.VISIBLE
        root.animate().cancel()
        root.animate().alpha(1f).setDuration(FADE_MS).start()
        if (playerProvider()?.isPlaying == true) {
            scheduleHide()
        }
    }

    fun hide() {
        if (visibility == Visibility.HIDDEN || isScrubbing) return
        visibility = Visibility.HIDDEN
        handler.removeCallbacks(hideRunnable)
        root.animate().cancel()
        root.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
            if (visibility == Visibility.HIDDEN) {
                root.visibility = View.GONE
            }
        }.start()
    }

    fun toggle() {
        if (visibility == Visibility.VISIBLE) hide() else show()
    }

    /** 锁屏：隐藏并屏蔽控制层 */
    fun disableForLock() {
        isLocked = true
        handler.removeCallbacks(hideRunnable)
        root.animate().cancel()
        root.visibility = View.GONE
        root.alpha = 0f
        visibility = Visibility.HIDDEN
        setLockButtonLocked(true)
    }

    /** 解锁：恢复可显示状态 */
    fun enableFromLock() {
        isLocked = false
        setLockButtonLocked(false)
    }

    fun isVisible(): Boolean = visibility == Visibility.VISIBLE

    /** 用户交互：重置自动隐藏计时（仅播放中） */
    fun onUserInteraction() {
        if (!isScrubbing && visibility == Visibility.VISIBLE && playerProvider()?.isPlaying == true) {
            scheduleHide()
        }
    }

    fun refreshPlayPause() {
        val playing = playerProvider()?.isPlaying == true
        val icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
        playPauseBtn.setImageResource(icon)
        centerPlayBtn.setImageResource(icon)
        centerPlayBtn.visibility = if (playing) View.GONE else View.VISIBLE
    }

    /** 立即刷新；seek 后短暂锁定目标位置，避免旧播放位置把滑块抢回去。 */
    fun updateProgressNow() {
        if (isScrubbing) return
        val player = playerProvider() ?: return
        val actualPosition = player.currentPosition
        val pending = pendingSeekMs
        val displayPosition = if (pending != null) {
            val caughtUp = kotlin.math.abs(actualPosition - pending) <= 500L
            val expired = System.currentTimeMillis() - pendingSeekStartedAt > SEEK_SNAP_GUARD_MS
            if (caughtUp || expired) {
                pendingSeekMs = null
                actualPosition
            } else {
                pending
            }
        } else {
            actualPosition
        }
        val dur = player.duration.let { if (it > 0) it else 0L }
        positionText.text = VideoItem.formatDuration(displayPosition)
        durationText.text = VideoItem.formatDuration(dur)
        timeBar.setPosition(displayPosition)
        timeBar.setDuration(dur)
        timeBar.setBufferedPosition(player.bufferedPosition)
    }

    /** 拖动进度条时渲染指定位置（不改变实际播放，松手后才 seek） */
    private fun renderProgressAt(positionMs: Long, durationMs: Long) {
        val dur = if (durationMs > 0) durationMs else 0L
        val pos = positionMs.coerceIn(0L, if (dur > 0) dur else positionMs.coerceAtLeast(0L))
        positionText.text = VideoItem.formatDuration(pos)
        durationText.text = VideoItem.formatDuration(dur)
        timeBar.setPosition(pos)
        timeBar.setDuration(dur)
    }

    private fun onInteract() {
        onUserInteraction()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, CONTROLS_TIMEOUT)
    }

    private fun startProgressLoop() {
        handler.removeCallbacks(progressRunnable)
        handler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerProvider()?.removeListener(playerListener)
    }
}
