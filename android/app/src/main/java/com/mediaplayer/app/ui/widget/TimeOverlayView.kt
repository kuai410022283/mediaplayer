/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.ui.widget

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.mediaplayer.app.Prefs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val timeFormatAlways = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val timeFormatSeconds = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("MM/dd EEE", Locale.getDefault())
    
    private var currentMode = Prefs.TIME_SHOW_MODE_HIDDEN

    var forceShowByOsd: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                updateTimeDisplay()
            }
        }

    private val tvDate: TextView
    private val tvTime: TextView

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTimeDisplay()
            handler?.postDelayed(this, 1000)
        }
    }

    init {
        orientation = VERTICAL
        gravity = Gravity.END

        tvDate = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#80000000"))
        }

        tvTime = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 42f)
            setTypeface(null, Typeface.BOLD)
            setShadowLayer(2f, 1f, 1f, Color.parseColor("#80000000"))
        }

        addView(tvDate, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = (context.resources.displayMetrics.density * -4).toInt() // 轻微负边距让日期和时间靠得更近
        })
        addView(tvTime, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        refreshMode()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler?.post(updateRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler?.removeCallbacks(updateRunnable)
    }

    fun refreshMode() {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        currentMode = prefs.getInt(Prefs.KEY_TIME_SHOW_MODE, Prefs.TIME_SHOW_MODE_HIDDEN)
        updateTimeDisplay()
    }

    private fun updateTimeDisplay() {
        if (currentMode == Prefs.TIME_SHOW_MODE_HIDDEN) {
            visibility = View.GONE
            return
        }

        val timestamp = System.currentTimeMillis()
        var isVisible = false
        var timeStr = ""

        when (currentMode) {
            Prefs.TIME_SHOW_MODE_ALWAYS -> {
                isVisible = true
                timeStr = timeFormatAlways.format(timestamp)
            }
            Prefs.TIME_SHOW_MODE_EVERY_HOUR -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = timestamp
                val minute = calendar.get(Calendar.MINUTE)
                val second = calendar.get(Calendar.SECOND)
                
                if ((minute == 59 && second >= 30) || (minute == 0 && second <= 30) || forceShowByOsd) {
                    isVisible = true
                    timeStr = timeFormatSeconds.format(timestamp)
                }
            }
            Prefs.TIME_SHOW_MODE_HALF_HOUR -> {
                val calendar = Calendar.getInstance()
                calendar.timeInMillis = timestamp
                val minute = calendar.get(Calendar.MINUTE)
                val second = calendar.get(Calendar.SECOND)
                
                if ((minute == 29 && second >= 30) || (minute == 30 && second <= 30) || forceShowByOsd) {
                    isVisible = true
                    timeStr = timeFormatSeconds.format(timestamp)
                }
            }
        }

        if (isVisible) {
            visibility = View.VISIBLE
            tvDate.text = dateFormat.format(timestamp)
            tvTime.text = timeStr
        } else {
            visibility = View.GONE
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        pivotX = w.toFloat()
        pivotY = 0f
    }

    fun onPipModeChanged(isPip: Boolean, config: android.content.res.Configuration?) {
        if (!isPip || config == null) {
            scaleX = 1.0f
            scaleY = 1.0f
            translationX = 0f
            translationY = 0f
        } else {
            // Reference width for a standard TV screen in DP is usually around 960
            val tvWidthDp = 960f
            // 乘以 1.4 让画中画里的时间显示相对大一点，避免太小看不清
            val scale = ((config.screenWidthDp / tvWidthDp) * 1.4f).coerceIn(0.1f, 1.0f)
            
            scaleX = scale
            scaleY = scale
            
            // Adjust margin based on scale (original margin is 32dp)
            val density = context.resources.displayMetrics.density
            val marginPx = 32 * density
            val marginDiff = marginPx * (1f - scale)
            
            translationX = marginDiff
            translationY = -marginDiff
        }
    }
}
