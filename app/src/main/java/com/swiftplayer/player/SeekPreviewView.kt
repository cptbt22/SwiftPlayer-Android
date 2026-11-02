package com.swiftplayer.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.swiftplayer.R
import com.swiftplayer.data.model.VideoItem
import kotlin.math.abs

/**
 * 快进拖动 HUD（屏幕中心）
 * - 目标时间大字：`目标时间 / 总时长`
 * - 差值：`+MM:SS`（青绿）/ `-MM:SS`（橙）
 * - 缩略图 160×90dp（未就绪显示占位）
 * - 水平进度条（target/duration 占比）
 */
class SeekPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val timeText: TextView
    private val deltaText: TextView
    private val thumbnailImage: ImageView
    private val progressBar: ProgressBar

    private val colorForward = ContextCompat.getColor(context, R.color.seek_delta_forward)
    private val colorBackward = ContextCompat.getColor(context, R.color.seek_delta_backward)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val dp = resources.displayMetrics.density
        val padding = (16 * dp).toInt()
        setPadding(padding, padding, padding, padding)
        setBackgroundResource(R.drawable.bg_gesture_indicator)

        timeText = TextView(context).apply {
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.02f
        }
        addView(timeText)

        deltaText = TextView(context).apply {
            textSize = 14f
            setTextColor(colorForward)
            gravity = Gravity.CENTER
            setPadding(0, (4 * dp).toInt(), 0, 0)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        addView(deltaText)

        thumbnailImage = ImageView(context).apply {
            val w = (160 * dp).toInt()
            val h = (90 * dp).toInt()
            layoutParams = LayoutParams(w, h).apply {
                topMargin = (8 * dp).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ContextCompat.getColor(context, R.color.poster_placeholder))
        }
        addView(thumbnailImage)

        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            val w = (160 * dp).toInt()
            val h = (4 * dp).toInt()
            layoutParams = LayoutParams(w, h).apply {
                topMargin = (8 * dp).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary))
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        addView(progressBar)
    }

    /**
     * 更新 HUD
     * @param currentTimeMs 目标时间（拖动预览时即 target）
     * @param durationMs 总时长
     * @param deltaMs 相对按下位置的差值（可为负）
     * @param thumbnail 目标帧缩略图，可为空
     */
    fun update(
        currentTimeMs: Long,
        durationMs: Long,
        deltaMs: Long,
        thumbnail: Bitmap? = null
    ) {
        timeText.text = "${VideoItem.formatDuration(currentTimeMs)} / ${VideoItem.formatDuration(durationMs)}"

        deltaText.text = formatDelta(deltaMs)
        deltaText.setTextColor(if (deltaMs >= 0) colorForward else colorBackward)

        progressBar.progress = if (durationMs > 0) {
            (currentTimeMs * 1000 / durationMs).toInt().coerceIn(0, 1000)
        } else {
            0
        }

        thumbnail?.let {
            thumbnailImage.setImageBitmap(it)
        } ?: run {
            thumbnailImage.setImageDrawable(null)
        }
    }

    fun setThumbnail(bitmap: Bitmap?) {
        if (bitmap != null) {
            thumbnailImage.setImageBitmap(bitmap)
        } else {
            thumbnailImage.setImageDrawable(null)
        }
    }

    /** 网络流不显示空白缩略图占位，让 HUD 更紧凑。 */
    fun setThumbnailVisible(visible: Boolean) {
        thumbnailImage.visibility = if (visible) VISIBLE else GONE
        if (!visible) thumbnailImage.setImageDrawable(null)
    }

    fun showBoundaryHint(text: String) {
        deltaText.text = text
    }

    private fun formatDelta(deltaMs: Long): String {
        val sign = if (deltaMs >= 0) "+" else "-"
        return "$sign${VideoItem.formatDuration(abs(deltaMs))}"
    }
}
