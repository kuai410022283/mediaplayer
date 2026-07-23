/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import com.mediaplayer.app.Prefs

class IjkPlayerHelper(
    private val context: Context,
    private val videoLayout: ViewGroup,
    private val listener: IPlayerHelper.PlayerListener
) : IPlayerHelper {

    private var ijkPlayer: IjkMediaPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var isPlayerPlaying = false
    private var lastResolution = ""
    private var surfaceCreated = false

    private var currentCacheMs: Int = 0
    private var currentDecoderMode: Int = Prefs.DECODER_MODE_AUTO
    private var currentScaleMode: Int = Prefs.SCALE_MODE_DEFAULT

    private var lastBuiltCacheMs: Int = -1
    private var lastBuiltDecoderMode: Int = -1

    init {
        try {
            IjkMediaPlayer.loadLibrariesOnce(null)
            IjkMediaPlayer.native_profileBegin("libijkplayer.so")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        currentCacheMs = prefs.getInt(Prefs.KEY_NETWORK_CACHE, Prefs.DEFAULT_NETWORK_CACHE)
        currentDecoderMode = (context as? com.mediaplayer.app.ui.home.MainActivity)?.currentDecoderMode ?: prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
        currentScaleMode = prefs.getInt(Prefs.KEY_SCALE_MODE, Prefs.SCALE_MODE_DEFAULT)
        
        initSurfaceView()
    }

    private fun initSurfaceView() {
        surfaceView = SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceCreated = true
                    ijkPlayer?.let { player ->
                        player.setDisplay(holder)
                        // 如果 player 已经 prepare 好了但没有 surface，立刻重设显示
                    }
                }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceCreated = false
                    ijkPlayer?.let { player ->
                        try {
                            player.setDisplay(null)
                        } catch (_: Exception) {}
                    }
                }
            })
        }
        videoLayout.addView(surfaceView)
    }

    override fun play(url: String, userAgent: String, customHeaders: String) {
        if (ijkPlayer == null || currentCacheMs != lastBuiltCacheMs || currentDecoderMode != lastBuiltDecoderMode) {
            buildPlayer()
        }

        isPlayerPlaying = false
        ijkPlayer?.reset()
        applyPlayerOptions(ijkPlayer!!)
        applyScaleMode()
        applyDataSource(ijkPlayer!!, url, userAgent, customHeaders)
    }

    private fun buildPlayer() {
        releasePlayer()
        lastBuiltCacheMs = currentCacheMs
        lastBuiltDecoderMode = currentDecoderMode
        ijkPlayer = IjkMediaPlayer()
        applyPlayerOptions(ijkPlayer!!)
        if (surfaceCreated) {
            ijkPlayer?.setDisplay(surfaceView?.holder)
        }
        setupPlayerListeners(ijkPlayer!!)
        applyScaleMode()
    }

    private fun applyPlayerOptions(player: IjkMediaPlayer) {
        IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_ERROR)

        val enableHw = currentDecoderMode == Prefs.DECODER_MODE_HARDWARE || currentDecoderMode == Prefs.DECODER_MODE_AUTO
        if (enableHw) {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1)
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as android.app.UiModeManager
            val isTv = uiModeManager.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
            if (isTv) {
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0)
            } else {
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
            }
        } else {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)
        }

        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "allowed_extensions", "ALL")
        // HTTPS 协议白名单 + TLS 配置
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "crypto,file,http,https,tcp,tls")
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "tls_verify", "0")
        // 允许 DNS 缓存，提升起播速度
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 0)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", -1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0)
        // 降低极限丢帧率，防止画面变成幻灯片 (优化至 5)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5L)
        
        // 纯解码性能优化（对 Go 后端绝对安全）
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec_mpeg4", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "fast", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", 30000000L) // 30秒超时 (单位为微秒)

        // 加入底层的 HTTP 断开重连机制，提升弱网抗性
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_at_eof", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 5) // 最大重连延迟5秒

        if (currentCacheMs <= 0) {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer")
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", 10L)
        } else {
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1L)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 0L)
            val minFrames = (currentCacheMs / 50).coerceIn(5, 60)
            player.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "min-frames", minFrames.toLong())
        }

        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L) 
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2000000L) // 2秒嗅探时长 (单位为微秒)
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 512L * 1024L) // 512KB 探针大小，配合 FCC 缓存瞬间出画
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", 50L * 1024L * 1024L)

        // 强制 RTSP 使用 TCP 传输，防止组播流被路由器拦截
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
        player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp")
    }

    private var videoWidth = 0
    private var videoHeight = 0

    private fun setupPlayerListeners(player: IjkMediaPlayer) {
        player.setOnPreparedListener {
            if (surfaceCreated) {
                it.setDisplay(surfaceView?.holder)
            }
            isPlayerPlaying = true
            val audioCodec = ijkPlayer?.mediaInfo?.mAudioDecoder ?: ""
            val videoCodec = ijkPlayer?.mediaInfo?.mVideoDecoder ?: ""
            val info = buildString {
                if (lastResolution.isNotEmpty()) append(lastResolution)
                if (videoCodec.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(videoCodec.uppercase())
                }
                if (audioCodec.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(audioCodec.uppercase())
                }
            }
            listener.onPlaying(if (info.isNotEmpty()) info else lastResolution)
            applyScaleMode()
        }
        player.setOnInfoListener { _, what, _ ->
            if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                listener.onBuffering(0f)
            } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END || what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                listener.onBuffering(100f)
            }
            false
        }
        player.setOnErrorListener { _, what, extra ->
            com.mediaplayer.app.util.RemoteLogger.e("IJKPlayer", "Playback error. what: $what, extra: $extra")
            listener.onError()
            true
        }
        player.setOnCompletionListener {
            com.mediaplayer.app.util.RemoteLogger.i("IJKPlayer", "Playback completed.")
            listener.onPlaybackCompleted()
        }
        player.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            if (width > 0 && height > 0) {
                videoWidth = width
                videoHeight = height
                val audioCodec = ijkPlayer?.mediaInfo?.mAudioDecoder ?: ""
                val videoCodec = ijkPlayer?.mediaInfo?.mVideoDecoder ?: ""
                lastResolution = "${width}x${height}"
                val info = buildString {
                    append(lastResolution)
                    if (videoCodec.isNotEmpty()) append(" | ").append(videoCodec.uppercase())
                    if (audioCodec.isNotEmpty()) append(" | ").append(audioCodec.uppercase())
                }
                if (isPlayerPlaying) {
                    listener.onPlaying(info)
                }
                applyScaleMode()
            }
        }
    }

    private fun applyDataSource(player: IjkMediaPlayer, url: String, userAgent: String, customHeaders: String) {
        try {
            val allHeaders = HashMap<String, String>()
            if (userAgent.isNotEmpty()) {
                player.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "user_agent", userAgent)
            }
            if (customHeaders.isNotEmpty()) {
                try {
                    val json = org.json.JSONObject(customHeaders)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        allHeaders[key] = json.getString(key)
                    }
                } catch (e: Exception) {}
            }

            // 动态添加系统 Token，防止 Token 在地址栏暴露
            val serverUrl = com.mediaplayer.app.data.api.ApiClient.getServerUrl()
            if (url.startsWith(serverUrl)) {
                val token = com.mediaplayer.app.data.api.ApiClient.accessToken
                if (!token.isNullOrEmpty()) {
                    allHeaders["Authorization"] = "Bearer $token"
                }
            }

            if (allHeaders.isNotEmpty()) {
                player.setDataSource(url, allHeaders)
            } else {
                player.dataSource = url
            }
            player.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
            listener.onError()
        }
    }

    override fun setAspectRatio(scaleMode: Int) {
        this.currentScaleMode = scaleMode
        applyScaleMode()
    }

    private fun applyScaleMode() {
        val parent = videoLayout
        val sv = surfaceView ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        
        if (parentWidth == 0 || parentHeight == 0) {
            parent.post { applyScaleMode() }
            return
        }

        var targetWidth = ViewGroup.LayoutParams.MATCH_PARENT
        var targetHeight = ViewGroup.LayoutParams.MATCH_PARENT

        when (currentScaleMode) {
            Prefs.SCALE_MODE_DEFAULT -> {
                if (videoWidth > 0 && videoHeight > 0) {
                    val ratio = videoWidth.toFloat() / videoHeight.toFloat()
                    val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
                    if (ratio > parentRatio) {
                        targetWidth = parentWidth
                        targetHeight = (parentWidth / ratio).toInt()
                    } else {
                        targetHeight = parentHeight
                        targetWidth = (parentHeight * ratio).toInt()
                    }
                }
            }
            Prefs.SCALE_MODE_STRETCH -> {
                val ratio = 16f / 9f
                val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
                if (ratio > parentRatio) {
                    targetWidth = parentWidth
                    targetHeight = (parentWidth / ratio).toInt()
                } else {
                    targetHeight = parentHeight
                    targetWidth = (parentHeight * ratio).toInt()
                }
            }
            Prefs.SCALE_MODE_16_10 -> {
                val ratio = 16f / 10f
                val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
                if (ratio > parentRatio) {
                    targetWidth = parentWidth
                    targetHeight = (parentWidth / ratio).toInt()
                } else {
                    targetHeight = parentHeight
                    targetWidth = (parentHeight * ratio).toInt()
                }
            }
            Prefs.SCALE_MODE_4_3 -> {
                val ratio = 4f / 3f
                val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
                if (ratio > parentRatio) {
                    targetWidth = parentWidth
                    targetHeight = (parentWidth / ratio).toInt()
                } else {
                    targetHeight = parentHeight
                    targetWidth = (parentHeight * ratio).toInt()
                }
            }
            Prefs.SCALE_MODE_FILL -> {
                targetWidth = ViewGroup.LayoutParams.MATCH_PARENT
                targetHeight = ViewGroup.LayoutParams.MATCH_PARENT
            }
            Prefs.SCALE_MODE_CROP -> {
                if (videoWidth > 0 && videoHeight > 0) {
                    val ratio = videoWidth.toFloat() / videoHeight.toFloat()
                    val parentRatio = parentWidth.toFloat() / parentHeight.toFloat()
                    if (ratio > parentRatio) {
                        targetHeight = parentHeight
                        targetWidth = (parentHeight * ratio).toInt()
                    } else {
                        targetWidth = parentWidth
                        targetHeight = (parentWidth / ratio).toInt()
                    }
                }
            }
        }

        val lp = sv.layoutParams
        if (lp is android.widget.FrameLayout.LayoutParams) {
            lp.width = targetWidth
            lp.height = targetHeight
            lp.gravity = android.view.Gravity.CENTER
            sv.layoutParams = lp
        } else {
            lp.width = targetWidth
            lp.height = targetHeight
            sv.layoutParams = lp
        }
    }

    override fun setDecoderMode(mode: Int) {
        this.currentDecoderMode = mode
    }

    override fun setCacheDuration(cacheMs: Int) {
        this.currentCacheMs = cacheMs
    }

    override fun pause() {
        ijkPlayer?.pause()
    }

    override fun resume() {
        ijkPlayer?.start()
    }
    
    override fun stop() {
        ijkPlayer?.stop()
    }

    override fun isPlaying(): Boolean {
        return ijkPlayer?.isPlaying ?: false
    }

    override fun getTime(): Long {
        return ijkPlayer?.currentPosition ?: 0L
    }

    override fun getDuration(): Long {
        return ijkPlayer?.duration ?: 0L
    }

    override fun setTime(timeMs: Long) {
        ijkPlayer?.seekTo(timeMs)
    }

    override fun setRate(rate: Float) {
        ijkPlayer?.setSpeed(rate)
    }

    // ── 音轨/字幕接口（IJK 不支持，全部降级为空实现） ──

    override fun getAudioTracks(): List<AudioTrackInfo> = emptyList()
    override fun selectAudioTrack(index: Int) {}
    override fun getSubtitleTracks(): List<SubtitleTrackInfo> = emptyList()
    override fun selectSubtitleTrack(index: Int) {}
    override fun disableSubtitle() {}
    override fun loadExternalSubtitle(uri: Uri, mimeType: String): Boolean = false

    override fun release() {
        releasePlayer()
        videoLayout.removeView(surfaceView)
        surfaceView = null
        
        try {
            // Do not call native_profileEnd() here as it shuts down FFmpeg globally and causes black screen for subsequent players
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private fun releasePlayer() {
        val player = ijkPlayer
        ijkPlayer = null
        isPlayerPlaying = false
        lastResolution = ""
        player?.apply {
            try {
                pause()
            } catch (_: Exception) {}
            try {
                stop()
            } catch (_: Exception) {}
            try {
                setDisplay(null)
            } catch (_: Exception) {}
            setOnPreparedListener(null)
            setOnVideoSizeChangedListener(null)
            setOnErrorListener(null)
            setOnInfoListener(null)
            setOnBufferingUpdateListener(null)
            try {
                reset()
            } catch (_: Exception) {}
            try {
                release()
            } catch (_: Exception) {}
        }
    }
}
