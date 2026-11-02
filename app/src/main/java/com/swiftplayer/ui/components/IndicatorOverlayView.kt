package com.swiftplayer.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.swiftplayer.R

class IndicatorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Type { BRIGHTNESS, VOLUME }

    private val iconView: ImageView
    private val progressBar: ProgressBar
    private val percentText: TextView

    var indicatorType: Type = Type.BRIGHTNESS
        set(value) {
            field = value
            updateIcon()
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        val padding = (14 * dp).toInt()
        setPadding(padding, padding, padding, padding)
        setBackgroundResource(R.drawable.bg_gesture_indicator)

        iconView = ImageView(context).apply {
            val size = (28 * dp).toInt()
            layoutParams = LayoutParams(size, size)
            setImageResource(R.drawable.ic_brightness)
            setColorFilter(0xFFFFFFFF.toInt())
        }
        addView(iconView)

        val barWidth = (4 * dp).toInt()
        val barHeight = (120 * dp).toInt()
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LayoutParams(barWidth, barHeight).apply {
                topMargin = (8 * dp).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            max = 100
            progress = 50
            pivotX = barWidth / 2f
            pivotY = barHeight / 2f
            rotation = 270f
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF8B5CF6.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x338B5CF6.toInt())
        }
        addView(progressBar)

        percentText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        addView(percentText)

        updateIcon()
    }

    private fun updateIcon() {
        when (indicatorType) {
            Type.BRIGHTNESS -> iconView.setImageResource(R.drawable.ic_brightness)
            Type.VOLUME -> iconView.setImageResource(R.drawable.ic_volume)
        }
    }

    fun setPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        progressBar.progress = clamped
        percentText.text = "$clamped%"
    }

    fun setTypeWithPercent(type: Type, percent: Int) {
        indicatorType = type
        setPercent(percent)
    }
}
