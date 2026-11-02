package com.swiftplayer.player

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class SpeedIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val speedText: TextView
    private val hintText: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        val horizontal = (24 * dp).toInt()
        val vertical = (16 * dp).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        setBackgroundResource(com.swiftplayer.R.drawable.bg_speed_indicator)

        speedText = TextView(context).apply {
            textSize = 22f
            setTextColor(0xFFA78BFA.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
        }
        addView(speedText)

        hintText = TextView(context).apply {
            textSize = 11f
            setTextColor(0xFFA0A0B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        addView(hintText)
    }

    fun setSpeed(speed: Float, showHint: Boolean = true) {
        speedText.text = String.format("%.0fx  ▶▶", speed)
        hintText.text = if (showHint) "左右拖动调节倍速" else ""
        hintText.visibility = if (showHint) VISIBLE else GONE
    }
}
