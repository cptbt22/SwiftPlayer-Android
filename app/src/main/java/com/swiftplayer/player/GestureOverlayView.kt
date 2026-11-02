package com.swiftplayer.player

import android.content.Context
import android.media.AudioManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sign

/**
 * 播放手势状态机。
 *
 * - 横拖：直接预览 seek，松手只提交一次；CANCEL 不提交。
 * - 长按：2x；长按期间横拖按档位调节 2x/3x/4x，松手恢复原倍速。
 * - 左右双击：±10s；中部双击：播放/暂停。
 * - 左/右半屏竖拖：亮度/音量。
 *
 * seek 始终以 ACTION_DOWN 时的位置和位移为锚点，避免播放推进导致目标抖动。
 */
class GestureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class GState { IDLE, PRESSED, LONG_PRESS, SEEK_DRAG, BRIGHTNESS, VOLUME }

    interface GestureCallback {
        fun onSingleTap()
        fun onDoubleTap()
        fun onSkip(deltaMs: Long)
        fun onLongPressStart()
        fun onLongPressSpeedChange(speed: Float)
        fun onLongPressEnd()
        fun onSeekPreview(targetMs: Long, deltaMs: Long, durationMs: Long)
        fun onSeekCommit(targetMs: Long)
        fun onBrightnessChange(brightness: Float)
        fun onVolumeChange(volumePercent: Int)
        fun onGetCurrentPosition(): Long
        fun onGestureEnd()
    }

    var callback: GestureCallback? = null
    var durationMs: Long = 0L

    private val density = resources.displayMetrics.density
    private val longPressDelay = 300L
    private val classifyMove = 12 * density
    private val brightnessStep = 20 * density
    private val verticalEdgeGuard = 50 * density
    private val horizontalEdgeGuard = 24 * density
    private val speedStep = 64 * density
    private val speedStart = 24 * density

    var state: GState = GState.IDLE
        private set

    private var downX = 0f
    private var downY = 0f
    private var downPosition = 0L
    private var seekTarget = 0L
    private var longPressActive = false
    private var verticalHandled = false
    private var movedFlag = false

    private var brightnessData = -1f
    private var gestureDownVolume = 0
    private var lastEmittedSpeed = 1.0f

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume: Int = audioManager.getStreamVolumeMax()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private val gestureDetector = GestureDetectorCompat(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!longPressActive && !verticalHandled && !movedFlag) {
                    callback?.onSingleTap()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                when {
                    e.x < width * 0.33f -> callback?.onSkip(-10_000L)
                    e.x > width * 0.67f -> callback?.onSkip(10_000L)
                    else -> callback?.onDoubleTap()
                }
                return true
            }

            override fun onDown(e: MotionEvent): Boolean = true
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event.x, event.y)
            MotionEvent.ACTION_MOVE -> onMove(event.x, event.y)
            MotionEvent.ACTION_UP -> onUp(canceled = false)
            MotionEvent.ACTION_CANCEL -> onUp(canceled = true)
        }
        return true
    }

    private fun onDown(x: Float, y: Float) {
        downX = x
        downY = y
        downPosition = callback?.onGetCurrentPosition() ?: 0L
        seekTarget = downPosition
        longPressActive = false
        verticalHandled = false
        movedFlag = false
        brightnessData = -1f
        lastEmittedSpeed = 1.0f
        state = GState.PRESSED

        cancelPendingLongPress()
        longPressRunnable = Runnable {
            if (state == GState.PRESSED) {
                state = GState.LONG_PRESS
                longPressActive = true
                movedFlag = true
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                callback?.onLongPressStart()
            }
        }
        handler.postDelayed(longPressRunnable!!, longPressDelay)
    }

    private fun onMove(x: Float, y: Float) {
        val dx = x - downX
        val dy = y - downY
        when (state) {
            GState.PRESSED -> classifyPressMove(dx, dy, x, y)
            GState.LONG_PRESS -> handleLongPressSpeed(dx)
            GState.SEEK_DRAG -> emitSeek(dx)
            GState.BRIGHTNESS -> handleBrightness(dy, y)
            GState.VOLUME -> handleVolume(dy)
            GState.IDLE -> Unit
        }
    }

    private fun classifyPressMove(dx: Float, dy: Float, x: Float, y: Float) {
        if (hypot(dx, dy) <= classifyMove) return

        cancelPendingLongPress()
        movedFlag = true
        when {
            abs(dx) > abs(dy) * 1.1f && !inHorizontalEdgeGuard(downX) -> {
                startSeek()
                emitSeek(dx)
            }
            abs(dy) >= abs(dx) && !inVerticalEdgeGuard(downY) -> classifyVertical()
            else -> state = GState.IDLE
        }
        // 亮度手势使用增量位移，避免首帧重复累计。
        if (state == GState.BRIGHTNESS) handleBrightness(y - downY, y)
    }

    private fun startSeek() {
        if (state == GState.SEEK_DRAG) return
        if (longPressActive) {
            longPressActive = false
            callback?.onLongPressEnd()
        }
        state = GState.SEEK_DRAG
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** 长按加速期间横拖：右侧升档 2x→3x→4x，左侧退回 2x；松手恢复原倍速。 */
    private fun handleLongPressSpeed(dx: Float) {
        if (abs(dx) < speedStart) return
        val speed = when {
            dx >= speedStart + speedStep * 2 -> 4.0f
            dx >= speedStart + speedStep -> 3.0f
            else -> 2.0f
        }
        if (speed != lastEmittedSpeed) {
            lastEmittedSpeed = speed
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            callback?.onLongPressSpeedChange(speed)
        }
    }

    private fun onUp(canceled: Boolean) {
        cancelPendingLongPress()
        if (state == GState.SEEK_DRAG && !canceled) {
            callback?.onSeekCommit(seekTarget)
        }
        if (longPressActive) {
            longPressActive = false
            callback?.onLongPressEnd()
        }
        state = GState.IDLE
        callback?.onGestureEnd()
    }

    private fun classifyVertical() {
        if (downX < width * 0.5f) {
            state = GState.BRIGHTNESS
            brightnessData = -1f
        } else {
            state = GState.VOLUME
            gestureDownVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
        verticalHandled = true
    }

    private fun emitSeek(dx: Float) {
        if (width <= 0 || durationMs <= 0L) return
        val target = SeekGesturePolicy.calculateTarget(
            durationMs = durationMs,
            anchorMs = downPosition,
            deltaPx = dx,
            widthPx = width.toFloat()
        )
        seekTarget = target
        callback?.onSeekPreview(target, target - downPosition, durationMs)
    }

    private fun handleVolume(dy: Float) {
        if (height <= 0) return
        val deltaV = (maxVolume * (-dy) * 3 / height).toInt()
        val newVolume = (gestureDownVolume + deltaV).coerceIn(0, maxVolume)
        callback?.onVolumeChange(newVolume * 100 / maxVolume)
    }

    private fun handleBrightness(dy: Float, y: Float) {
        if (abs(dy) < brightnessStep || height <= 0) return
        if (brightnessData < 0f) brightnessData = getBrightness()
        brightnessData = (brightnessData - dy / height).coerceIn(0.01f, 1.0f)
        callback?.onBrightnessChange(brightnessData)
        downY = y
    }

    private fun inVerticalEdgeGuard(y: Float): Boolean {
        return y <= verticalEdgeGuard || height - y <= verticalEdgeGuard
    }

    private fun inHorizontalEdgeGuard(x: Float): Boolean {
        return x <= horizontalEdgeGuard || width - x <= horizontalEdgeGuard
    }

    private fun cancelPendingLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    override fun onDetachedFromWindow() {
        cancelPendingLongPress()
        if (longPressActive) {
            longPressActive = false
            callback?.onLongPressEnd()
        }
        super.onDetachedFromWindow()
    }

    private fun getBrightness(): Float {
        val value = (context as? android.app.Activity)?.window?.attributes?.screenBrightness ?: -1f
        return if (value <= 0f) 0.5f else value
    }

    private fun AudioManager.getStreamVolumeMax(): Int =
        getStreamMaxVolume(AudioManager.STREAM_MUSIC).let { if (it > 0) it else 1 }
}

/**
 * 纯计算策略（v2.5.0 渐进式加速）：
 *
 * - 基础步长：单屏 = 6% 片长（下限 20s，上限 10min，原 180s 上限对长片过小）。
 * - 渐进加速：归一化位移经幂函数 x^1.7 映射——小幅拖动比线性更精细（微调不跳），
 *   拖得越远单位像素跳转的时长越大（以 2h 电影为例：0.1 屏≈9s、0.5 屏≈2.2min、
 *   1 屏=7.2min、2 屏≈23.5min），符合「拖的幅度大时快进比例更大」的手感预期。
 * - 位移上限 ±2.2 屏，防止手指大幅甩动后映射爆炸。
 */
object SeekGesturePolicy {
    private const val SEEK_SENSITIVITY = 0.06f
    private const val MIN_PER_SCREEN_MS = 20_000L
    private const val MAX_PER_SCREEN_MS = 600_000L
    private const val ACCEL_EXPONENT = 1.7f
    private const val MAX_SCREENS = 2.2f

    fun calculateTarget(durationMs: Long, anchorMs: Long, deltaPx: Float, widthPx: Float): Long {
        if (durationMs <= 0L || widthPx <= 0f) return anchorMs.coerceAtLeast(0L)
        val perScreen = (durationMs * SEEK_SENSITIVITY).toLong()
            .coerceIn(MIN_PER_SCREEN_MS, MAX_PER_SCREEN_MS)
        val screens = (deltaPx / widthPx).coerceIn(-MAX_SCREENS, MAX_SCREENS)
        val accelerated = abs(screens).pow(ACCEL_EXPONENT) * sign(screens)
        return (anchorMs + accelerated * perScreen).toLong().coerceIn(0L, durationMs)
    }
}
