package com.swiftplayer.player

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class LiveSubtitleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val subtitleText: TextView
    private val statusText: TextView

    var isActive: Boolean = false
        set(value) {
            field = value
            statusText.visibility = if (value) VISIBLE else GONE
        }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density

        val padding = (12 * dp).toInt()
        setPadding(padding, padding, padding, padding)

        subtitleText = TextView(context).apply {
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(4f * dp, 1f)
            setShadowLayer(4f, 1f, 1f, 0xFF000000.toInt())
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(subtitleText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            val maxWidthPx = (280 * dp).toInt()
            width = LayoutParams.MATCH_PARENT
            val screenWidth = resources.displayMetrics.widthPixels
            if (screenWidth > 0 && screenWidth < maxWidthPx + 64 * dp.toInt()) {
                marginStart = (24 * dp).toInt()
                marginEnd = (24 * dp).toInt()
            }
        })

        statusText = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFFBB86FC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, (6 * dp).toInt(), 0, 0)
            text = "语音识别中..."
            visibility = GONE
        }
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setSubtitle(text: String, isPartial: Boolean) {
        subtitleText.text = text
        subtitleText.alpha = if (isPartial) 0.7f else 1.0f
        statusText.text = if (isPartial) "识别中..." else "已识别"
    }

    fun clearSubtitle() {
        subtitleText.text = ""
        statusText.text = "语音识别中..."
    }

    fun setStatus(status: SpeechToSubtitleManager.Status) {
        when (status) {
            SpeechToSubtitleManager.Status.IDLE -> {
                statusText.text = ""
                statusText.visibility = GONE
            }
            SpeechToSubtitleManager.Status.LISTENING -> {
                statusText.text = "正在聆听..."
                statusText.visibility = VISIBLE
            }
            SpeechToSubtitleManager.Status.PROCESSING -> {
                statusText.text = "处理中..."
                statusText.visibility = VISIBLE
            }
            SpeechToSubtitleManager.Status.ERROR -> {
                statusText.text = "识别出错"
                statusText.visibility = VISIBLE
            }
        }
    }
}
