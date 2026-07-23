/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.ui.widget

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageView
import com.mediaplayer.app.R
import com.mediaplayer.app.util.RemoteLogger

class OsdOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val layoutOsd: LinearLayout
    private val tvOsdChannelNum: TextView
    private val tvOsdChannelName: TextView
    private val tvOsdLineInfo: TextView
    private val tvOsdInfo: TextView
    private val tvOsdEpg: TextView
    private val tvOsdNextEpg: TextView
    private val progressEpg: ProgressBar
    private val layoutVodControl: LinearLayout
    private val tvVodIcon: ImageView
    private val tvVodCurrentTime: TextView
    private val tvVodTotalTime: TextView
    private val tvBtnAudio: TextView
    private val tvBtnSubtitle: TextView
    private val seekBarVod: SeekBar

    // VOD 模式状态
    private var isVodMode = false
    private var isVodPlaying = true
    private var vodDuration = 0L
    private var vodSeekListener: ((Long) -> Unit)? = null
    private var vodPositionProvider: (() -> Long)? = null
    private var vodDurationProvider: (() -> Long)? = null
    private val progressUpdateHandler = Handler(Looper.getMainLooper())
    private var isUserDraggingSeekBar = false
    // 快进/快退进行中，暂停自动进度刷新；恢复时自动重启进度更新器
    var isVodSeeking = false
        set(value) {
            field = value
            if (!value && isVodMode && !isUserDraggingSeekBar) {
                restartVodProgressUpdater()
            }
        }

    private val uiHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        hideOsd()
    }

    var onOsdVisibilityChanged: ((Boolean) -> Unit)? = null

    // 音轨/字幕按钮点击回调
    private var trackButtonListener: ((type: String) -> Unit)? = null

    fun setTrackButtonListener(listener: (type: String) -> Unit) {
        trackButtonListener = listener
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_osd_overlay, this, true)

        layoutOsd = findViewById(R.id.layoutOsd)
        tvOsdChannelNum = findViewById(R.id.tvOsdChannelNum)
        tvOsdChannelName = findViewById(R.id.tvOsdChannelName)
        tvOsdLineInfo = findViewById(R.id.tvOsdLineInfo)
        tvOsdInfo = findViewById(R.id.tvOsdInfo)
        tvOsdEpg = findViewById(R.id.tvOsdEpg)
        tvOsdNextEpg = findViewById(R.id.tvOsdNextEpg)
        progressEpg = findViewById(R.id.progressEpg)
        layoutVodControl = findViewById(R.id.layoutVodControl)
        tvVodIcon = findViewById(R.id.tvVodIcon)
        tvVodCurrentTime = findViewById(R.id.tvVodCurrentTime)
        tvVodTotalTime = findViewById(R.id.tvVodTotalTime)
        tvBtnAudio = findViewById(R.id.tvBtnAudio)
        tvBtnSubtitle = findViewById(R.id.tvBtnSubtitle)

        // 音轨/字幕按钮点击 → 通知 MainActivity 打开选择面板
        tvBtnAudio.setOnClickListener {
            trackButtonListener?.invoke("audio")
        }
        tvBtnSubtitle.setOnClickListener {
            trackButtonListener?.invoke("subtitle")
        }

        val accentColor = androidx.core.content.ContextCompat.getColor(context, R.color.accent)

        // 修复跑马灯中断：按钮获取焦点时，强制重置跑马灯的选中状态
        val trackFocusListener = OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                tvOsdChannelName.isSelected = true
                tvOsdEpg.isSelected = true
                (view as TextView).setTextColor(accentColor)
            } else {
                (view as TextView).setTextColor(android.graphics.Color.WHITE)
            }
        }
        tvBtnAudio.onFocusChangeListener = trackFocusListener
        tvBtnSubtitle.onFocusChangeListener = trackFocusListener

        val iconFocusListener = OnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                (view as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(accentColor)
            } else {
                (view as ImageView).imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            }
        }
        tvVodIcon.onFocusChangeListener = iconFocusListener

        seekBarVod = findViewById(R.id.seekBarVod)

        // VOD SeekBar 拖动监听
        seekBarVod.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isVodMode) {
                    val seekMs = progress.toLong() * vodDuration / 1000
                    tvVodIcon.setImageResource(if (isVodPlaying) R.drawable.ic_vod_play else R.drawable.ic_vod_pause)
                    tvVodCurrentTime.text = formatTime(seekMs)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                isUserDraggingSeekBar = true
                stopVodProgressUpdater()
                removeCallbacks()
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                isUserDraggingSeekBar = false
                val seekMs = (sb?.progress ?: 0).toLong() * vodDuration / 1000
                vodSeekListener?.invoke(seekMs)
                restartVodProgressUpdater()
                showOsd()
            }
        })
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacks(hideOsdRunnable)
        progressUpdateHandler.removeCallbacks(vodProgressRunnable)
    }

    fun showOsd() {
        val wasHidden = layoutOsd.visibility != View.VISIBLE
        layoutOsd.visibility = View.VISIBLE
        
        // Ensure marquee works by selecting the views programmatically
        tvOsdChannelName.isSelected = true
        tvOsdEpg.isSelected = true
        tvOsdNextEpg.isSelected = true

        if (wasHidden && isVodMode && !isUserDraggingSeekBar) {
            tvVodIcon.requestFocus()
        }

        uiHandler.removeCallbacks(hideOsdRunnable)
        uiHandler.postDelayed(hideOsdRunnable, 10000)
        
        onOsdVisibilityChanged?.invoke(true)
    }

    fun hideOsd() {
        layoutOsd.visibility = View.GONE
        RemoteLogger.i("PanelTrace", "OSD GONE")
        onOsdVisibilityChanged?.invoke(false)
    }

    fun isOsdVisible(): Boolean {
        return layoutOsd.visibility == View.VISIBLE
    }
    
    fun removeCallbacks() {
        uiHandler.removeCallbacks(hideOsdRunnable)
    }

    // Setters for MainActivity to update text
    fun setChannelNum(numStr: String) {
        tvOsdChannelNum.text = numStr
    }

    fun setChannelName(nameStr: String) {
        tvOsdChannelName.text = nameStr
        tvOsdChannelName.isSelected = true
    }

    fun setLineInfo(lineStr: String) {
        tvOsdLineInfo.text = lineStr
    }

    fun setInfoText(infoStr: String) {
        tvOsdInfo.text = infoStr
    }

    fun setEpgText(epgStr: String) {
        tvOsdEpg.text = epgStr
        tvOsdEpg.isSelected = true
    }

    fun setNextEpgText(nextEpgStr: String) {
        if (nextEpgStr.isNotEmpty()) {
            tvOsdNextEpg.text = nextEpgStr
            tvOsdNextEpg.visibility = View.VISIBLE
            tvOsdNextEpg.isSelected = true
        } else {
            tvOsdNextEpg.text = ""
            tvOsdNextEpg.visibility = View.GONE
        }
    }

    fun setEpgProgress(progress: Int) {
        if (progress in 1..100) {
            progressEpg.progress = progress
            progressEpg.visibility = View.VISIBLE
        } else {
            progressEpg.progress = 0
            progressEpg.visibility = View.GONE
        }
    }

    fun getInfoText(): String {
        return tvOsdInfo.text.toString()
    }

    // ── VOD 模式控制 ──────────────────────────────────

    fun setVodMode(enabled: Boolean) {
        isVodMode = enabled
        if (enabled) {
            tvOsdEpg.visibility = View.GONE
            tvOsdNextEpg.visibility = View.GONE
            progressEpg.visibility = View.GONE
            layoutVodControl.visibility = View.VISIBLE
            seekBarVod.visibility = View.VISIBLE
        } else {
            tvOsdEpg.visibility = View.VISIBLE
            tvOsdNextEpg.visibility = View.VISIBLE
            progressEpg.visibility = View.VISIBLE
            layoutVodControl.visibility = View.GONE
            seekBarVod.visibility = View.GONE
            stopVodProgressUpdater()
        }
    }

    fun setVodPlaying(playing: Boolean) {
        isVodPlaying = playing
        if (isVodMode) {
            tvVodIcon.setImageResource(if (playing) R.drawable.ic_vod_play else R.drawable.ic_vod_pause)
            updateVodTimeDisplay()
        }
    }

    fun setVodSeekListener(listener: (Long) -> Unit) {
        vodSeekListener = listener
    }

    fun startVodProgressUpdater(positionProvider: () -> Long, durationProvider: () -> Long) {
        vodPositionProvider = positionProvider
        vodDurationProvider = durationProvider
        stopVodProgressUpdater()
        vodProgressRunnable.run()
    }

    /** 使用已注册的 provider 重新启动进度刷新（用于 SeekBar 拖动结束后恢复） */
    private fun restartVodProgressUpdater() {
        stopVodProgressUpdater()
        if (vodPositionProvider != null && vodDurationProvider != null) {
            vodProgressRunnable.run()
        }
    }

    fun stopVodProgressUpdater() {
        progressUpdateHandler.removeCallbacks(vodProgressRunnable)
    }

    private val vodProgressRunnable = object : Runnable {
        override fun run() {
            if (!isVodMode || isUserDraggingSeekBar || isVodSeeking) return
            val currentMs = vodPositionProvider?.invoke() ?: 0L
            val totalMs = vodDurationProvider?.invoke() ?: 0L
            if (totalMs > 0) {
                vodDuration = totalMs
                seekBarVod.progress = (currentMs * 1000 / totalMs).toInt().coerceIn(0, 1000)
                tvVodIcon.setImageResource(if (isVodPlaying) R.drawable.ic_vod_play else R.drawable.ic_vod_pause)
                tvVodCurrentTime.text = formatTime(currentMs)
                tvVodTotalTime.text = formatTime(totalMs)
            }
            progressUpdateHandler.postDelayed(this, 500)
        }
    }

    private fun updateVodTimeDisplay() {
        val currentMs = vodPositionProvider?.invoke() ?: 0L
        tvVodIcon.setImageResource(if (isVodPlaying) R.drawable.ic_vod_play else R.drawable.ic_vod_pause)
        tvVodCurrentTime.text = formatTime(currentMs)
        tvVodTotalTime.text = formatTime(vodDuration)
    }

    /** 外部直接传入位置和时长，用于平滑 seek 动画（跳过 provider 回调） */
    fun updateVodProgress(positionMs: Long, durationMs: Long) {
        if (!isVodMode) return
        vodDuration = durationMs
        if (durationMs > 0) {
            seekBarVod.progress = (positionMs * 1000 / durationMs).toInt().coerceIn(0, 1000)
        }
        tvVodIcon.setImageResource(R.drawable.ic_vod_forward)
        tvVodCurrentTime.text = formatTime(positionMs)
        tvVodTotalTime.text = formatTime(durationMs)
    }

    /** 显示倍速状态（快进/快退） */
    fun setVodSpeed(speed: Float) {
        if (!isVodMode) return
        if (speed > 1.0f) {
            val speedText = if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "%.1fx".format(speed)
            tvVodIcon.setImageResource(R.drawable.ic_vod_forward)
            tvVodCurrentTime.text = speedText
        } else if (speed < 1.0f && speed > 0f) {
            val speedText = "%.1fx".format(speed)
            tvVodIcon.setImageResource(R.drawable.ic_vod_rewind)
            tvVodCurrentTime.text = speedText
        } else {
            updateVodTimeDisplay()
        }
    }

    /** 显示快退 seek 位置 */
    fun setVodSeekBackward(positionMs: Long, durationMs: Long) {
        if (!isVodMode) return
        if (durationMs > 0) {
            seekBarVod.progress = (positionMs * 1000 / durationMs).toInt().coerceIn(0, 1000)
        }
        tvVodIcon.setImageResource(R.drawable.ic_vod_rewind)
        tvVodCurrentTime.text = formatTime(positionMs)
        tvVodTotalTime.text = formatTime(durationMs)
    }

    // ── 音轨/字幕按钮控制（由 MainActivity.updateTrackButtonVisibility 调用） ──

    fun updateAudioButton(label: String) {
        tvBtnAudio.text = label
        tvBtnAudio.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
    }

    fun updateSubtitleButton(label: String) {
        tvBtnSubtitle.text = label
        tvBtnSubtitle.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setTrackButtonsEnabled(enabled: Boolean) {
        tvBtnAudio.isFocusable = enabled
        tvBtnSubtitle.isFocusable = enabled
        tvBtnAudio.setTextColor(if (enabled) -0x1 else -0x555555) // WHITE or GRAY
        tvBtnSubtitle.setTextColor(if (enabled) -0x1 else -0x555555)
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
}
