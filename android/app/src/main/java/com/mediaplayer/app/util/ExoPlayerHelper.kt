/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as media3R
import com.mediaplayer.app.Prefs

@UnstableApi
class ExoPlayerHelper(
    private val context: Context,
    private val videoLayout: ViewGroup,
    private val listener: IPlayerHelper.PlayerListener
) : IPlayerHelper {

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    
    private var isPlayerPlaying = false
    private var lastResolution = ""
    
    // Config state
    private var currentCacheMs: Int = 0
    private var currentDecoderMode: Int = Prefs.DECODER_MODE_AUTO
    private var currentScaleMode: Int = Prefs.SCALE_MODE_DEFAULT

    private var lastBuiltCacheMs: Int = -1
    private var lastBuiltDecoderMode: Int = -1

    // 复用 MediaSourceFactory（保留认证头等配置，用于外挂字幕加载）
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    init {
        initPlayerView()
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        currentCacheMs = prefs.getInt(Prefs.KEY_NETWORK_CACHE, Prefs.DEFAULT_NETWORK_CACHE)
        currentDecoderMode = (context as? com.mediaplayer.app.ui.home.MainActivity)?.currentDecoderMode ?: prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
        currentScaleMode = prefs.getInt(Prefs.KEY_SCALE_MODE, Prefs.SCALE_MODE_DEFAULT)
    }

    private fun initPlayerView() {
        playerView = PlayerView(context).apply {
            useController = false
            setKeepContentOnPlayerReset(true)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        videoLayout.addView(playerView)
    }

    // Playback state for retry
    private var currentUrl: String = ""
    private var currentUserAgent: String = ""
    private var currentHeaders: String = ""
    private var isMimeTypeFallback: Boolean = false
    
    // Circuit breaker for Behind Live Window
    private var behindLiveWindowCount: Int = 0
    private var behindLiveWindowLastTime: Long = 0L

    // Circuit breaker for Audio/Video Decoder Failures
    private var criticalErrorCount: Int = 0
    private var criticalErrorLastTime: Long = 0L

    override fun play(url: String, userAgent: String, customHeaders: String) {
        currentUrl = url
        currentUserAgent = userAgent
        currentHeaders = customHeaders
        isMimeTypeFallback = false
        behindLiveWindowCount = 0
        behindLiveWindowLastTime = 0L
        criticalErrorCount = 0
        criticalErrorLastTime = 0L
        
        playInternal(url, userAgent, customHeaders, null)
    }

    private fun playInternal(url: String, userAgent: String, customHeaders: String, mimeType: String?) {
        val isLiveStream = url.lowercase().run { 
            startsWith("udp://") || startsWith("rtsp://") || startsWith("rtp://") || 
            contains("/udp/") || contains("/rtp/") || contains(".ts") || contains(".flv") 
        }

        // 每次起播前探测一下当前电视/盒子的 HDR 体质
        HdrCapabilitiesHelper.printHdrInfo(context)

        if (exoPlayer == null || currentCacheMs != lastBuiltCacheMs || currentDecoderMode != lastBuiltDecoderMode) {
            buildPlayer()
        }

        isPlayerPlaying = false
        exoPlayer?.stop()

        applyScaleMode()

        val okHttpClient = com.mediaplayer.app.util.PlayerNetworkHelper.getPlayerOkHttpClient()
        val okHttpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient)
        
        if (userAgent.isNotEmpty()) {
            okHttpDataSourceFactory.setUserAgent(userAgent)
        }
        
        val headers = HashMap<String, String>()
        if (customHeaders.isNotEmpty()) {
            try {
                val json = org.json.JSONObject(customHeaders)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    headers[key] = json.getString(key)
                }
            } catch (e: Exception) {}
        }
        
        // 动态添加系统 Token，防止 Token 在地址栏暴露
        val serverUrl = com.mediaplayer.app.data.api.ApiClient.getServerUrl()
        if (url.startsWith(serverUrl)) {
            val token = com.mediaplayer.app.data.api.ApiClient.accessToken
            if (!token.isNullOrEmpty()) {
                headers["Authorization"] = "Bearer $token"
            }
        }

        if (headers.isNotEmpty()) {
            okHttpDataSourceFactory.setDefaultRequestProperties(headers)
        }

        // 使用 DefaultDataSource.Factory 包装 OkHttpDataSource，
        // 这样不仅能对 HTTP/HTTPS 注入自定义头，还能完美向下兼容 file://、asset:// 等本地视频播放，防止负优化！
        val defaultDataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, okHttpDataSourceFactory)

        // 注入自定义提取器，降低对 TS 流解析的严苛度（允许非 IDR 关键帧起播，增强容错）
        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setTsExtractorFlags(androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(defaultDataSourceFactory)
        this.mediaSourceFactory = mediaSourceFactory

        val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(url))
        if (mimeType != null) {
            mediaItemBuilder.setMimeType(mimeType)
        }
        
        // 针对 UDP 或 RTSP 直播源，注入 LiveConfiguration 以优化时钟同步
        if (isLiveStream) {
            val liveConfig = MediaItem.LiveConfiguration.Builder()
                .setMaxPlaybackSpeed(1.02f)
                .build()
            mediaItemBuilder.setLiveConfiguration(liveConfig)
        }
        
        val mediaItem = mediaItemBuilder.build()
        
        // RTSP 流需要使用 RtspMediaSource，而非默认的 ProgressiveMediaSource
        val isRtsp = url.lowercase().startsWith("rtsp://")
        val mediaSource = try {
            if (isRtsp) {
                com.mediaplayer.app.util.RemoteLogger.i("ExoPlayer", "Creating RtspMediaSource for: $url")
                androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .setTimeoutMs(15000)
                    .setDebugLoggingEnabled(true)
                    .createMediaSource(mediaItem)
            } else {
                mediaSourceFactory.createMediaSource(mediaItem)
            }
        } catch (e: Exception) {
            com.mediaplayer.app.util.RemoteLogger.e("ExoPlayer", "Failed to create media source for: $url", e)
            listener.onError()
            return
        }
        
        exoPlayer?.setMediaSource(mediaSource)
        exoPlayer?.prepare()
        exoPlayer?.play()
    }

    private fun buildPlayer() {
        releasePlayer()

        lastBuiltCacheMs = currentCacheMs
        lastBuiltDecoderMode = currentDecoderMode

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setEnableDecoderFallback(true) // 开启解码器容错回退机制
            setExtensionRendererMode(
                when (currentDecoderMode) {
                    Prefs.DECODER_MODE_SOFTWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    Prefs.DECODER_MODE_HARDWARE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                }
            )
        }

        val loadControlBuilder = DefaultLoadControl.Builder()
        if (currentCacheMs > 0) {
            loadControlBuilder.setBufferDurationsMs(
                currentCacheMs * 2,
                currentCacheMs * 4,
                currentCacheMs,
                currentCacheMs
            )
        } else {
            loadControlBuilder.setBufferDurationsMs(15000, 30000, 30, 3000)
        }
        val loadControl = loadControlBuilder.build()

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                // 允许自动切换视频分辨率以匹配电视面板
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
            )
        }

        val prefs = context.getSharedPreferences(com.mediaplayer.app.Prefs.FILE, Context.MODE_PRIVATE)
        val isPassthroughEnabled = prefs.getBoolean(com.mediaplayer.app.Prefs.KEY_AUDIO_PASSTHROUGH, false)

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .setSpatializationBehavior(
                if (isPassthroughEnabled) androidx.media3.common.C.SPATIALIZATION_BEHAVIOR_AUTO
                else androidx.media3.common.C.SPATIALIZATION_BEHAVIOR_NEVER
            )
            .build()

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()
            
        playerView?.player = exoPlayer
        
        applyScaleMode()

        exoPlayer?.addListener(exoPlayerListener)
    }

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    listener.onBuffering(0f)
                }
                Player.STATE_READY -> {
                    listener.onBuffering(100f)
                    if (exoPlayer?.playWhenReady == true) {
                        if (!isPlayerPlaying) {
                            isPlayerPlaying = true
                            // 在 STATE_READY 时主动上报可用信息
                            // （onVideoSizeChanged 可能因流未上报尺寸而不触发）
                            val videoFormat = exoPlayer?.videoFormat
                            val audioFormat = exoPlayer?.audioFormat
                            val info = buildString {
                                if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
                                    append("${videoFormat.width}x${videoFormat.height}")
                                }
                                val videoMime = videoFormat?.sampleMimeType?.substringAfter("/")?.uppercase()
                                val audioMime = audioFormat?.sampleMimeType?.substringAfter("/")?.uppercase()
                                if (!videoMime.isNullOrEmpty()) {
                                    if (isNotEmpty()) append(" | ")
                                    append(videoMime)
                                }
                                if (!audioMime.isNullOrEmpty()) {
                                    if (isNotEmpty()) append(" | ")
                                    append(audioMime)
                                }
                            }
                            listener.onPlaying(info)

                            val vMime = videoFormat?.sampleMimeType ?: ""
                            val aMime = audioFormat?.sampleMimeType ?: ""
                            val colorInfo = videoFormat?.colorInfo
                            
                            val isHdr10 = colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084
                            val isHlg = colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG
                            val isDolbyVision = vMime == "video/dolby-vision" || videoFormat?.codecs?.contains("dvh1") == true || videoFormat?.codecs?.contains("dvhe") == true
                            val isDolbyAtmos = aMime == "audio/eac3-joc" || audioFormat?.codecs?.contains("joc") == true
                            val isDolbyAudio = !isDolbyAtmos && (aMime.contains("ac3") || aMime.contains("eac3") || aMime == "audio/true-hd" || audioFormat?.codecs?.contains("ac-3", ignoreCase = true) == true)
                            val isDts = aMime.contains("dts") || audioFormat?.codecs?.contains("dts", ignoreCase = true) == true
                            
                            val badgeInfo = com.mediaplayer.app.util.StreamBadgeInfo(
                                isDolbyVision = isDolbyVision,
                                isHdr10 = isHdr10,
                                isHlg = isHlg,
                                isDolbyAtmos = isDolbyAtmos,
                                isDolbyAudio = isDolbyAudio,
                                isDts = isDts,
                                audioCodec = aMime.substringAfter("/").uppercase(),
                                videoCodec = vMime.substringAfter("/").uppercase()
                            )
                            listener.onMediaInfoReady(badgeInfo)

                            // 通知轨道列表变化
                            listener.onTracksChanged(
                                audioTracks = getAudioTracks(),
                                subtitleTracks = getSubtitleTracks()
                            )
                        }
                    }
                }
                Player.STATE_ENDED -> {
                    listener.onPlaybackCompleted()
                }
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            com.mediaplayer.app.util.RemoteLogger.e("ExoPlayer", "Playback error code: ${error.errorCode}", error)
            
            // 1. 直播流滑窗越界自愈 (带熔断机制)
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                val now = System.currentTimeMillis()
                if (now - behindLiveWindowLastTime > 60000) {
                    behindLiveWindowCount = 0
                }
                behindLiveWindowCount++
                behindLiveWindowLastTime = now
                
                if (behindLiveWindowCount <= 3) {
                    com.mediaplayer.app.util.RemoteLogger.i("ExoPlayer", "Behind live window detected ($behindLiveWindowCount/3), auto-recovering...")
                    exoPlayer?.seekToDefaultPosition()
                    exoPlayer?.prepare()
                    return // 吞掉错误，不再向上层报错
                } else {
                    com.mediaplayer.app.util.RemoteLogger.i("ExoPlayer", "Behind live window circuit breaker tripped! Throwing error.")
                    // 熔断，重置计数，并抛给上层换源
                    behindLiveWindowCount = 0
                }
            }
            
            // 2. 音视频解码或 AudioTrack 崩溃导致卡死，触发自愈重启 (兜底)
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
            ) {
                val now = System.currentTimeMillis()
                if (now - criticalErrorLastTime > 60000) {
                    criticalErrorCount = 0
                }
                criticalErrorCount++
                criticalErrorLastTime = now
                
                if (criticalErrorCount <= 3) {
                    com.mediaplayer.app.util.RemoteLogger.e("ExoPlayer", "Audio/Video critical error detected ($criticalErrorCount/3), attempting to recover...", error)
                    exoPlayer?.seekToDefaultPosition()
                    exoPlayer?.prepare()
                    return // 吞掉错误，尝试自愈
                } else {
                    com.mediaplayer.app.util.RemoteLogger.e("ExoPlayer", "Critical error circuit breaker tripped! Throwing error.", error)
                    criticalErrorCount = 0
                }
            }
            
            // 3. 格式嗅探与重试 (MimeType 降级)
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            ) {
                if (!isMimeTypeFallback) {
                    isMimeTypeFallback = true
                    com.mediaplayer.app.util.RemoteLogger.i("ExoPlayer", "Parsing error detected, falling back to M3U8 MimeType...")
                    // 强制使用 M3U8 MimeType 再次尝试播放
                    playInternal(currentUrl, currentUserAgent, currentHeaders, androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    return // 吞掉本次错误
                }
            }

            listener.onError()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                val audioMime = exoPlayer?.audioFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: ""
                val videoMime = exoPlayer?.videoFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: ""
                lastResolution = "${videoSize.width}x${videoSize.height}"
                val info = buildString {
                    append(lastResolution)
                    if (videoMime.isNotEmpty()) append(" | ").append(videoMime)
                    if (audioMime.isNotEmpty()) append(" | ").append(audioMime)
                }
                if (isPlayerPlaying) {
                    listener.onPlaying(info)
                }
            }
        }
    }

    override fun setAspectRatio(scaleMode: Int) {
        this.currentScaleMode = scaleMode
        applyScaleMode()
    }
    
    private fun applyScaleMode() {
        val parent = videoLayout
        val pv = playerView ?: return
        val parentWidth = parent.width
        val parentHeight = parent.height
        
        if (parentWidth == 0 || parentHeight == 0) {
            // Wait for layout
            parent.post { applyScaleMode() }
            return
        }

        var targetWidth = ViewGroup.LayoutParams.MATCH_PARENT
        var targetHeight = ViewGroup.LayoutParams.MATCH_PARENT

        when (currentScaleMode) {
            Prefs.SCALE_MODE_DEFAULT -> {
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            Prefs.SCALE_MODE_STRETCH -> {
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
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
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
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
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
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
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                targetWidth = ViewGroup.LayoutParams.MATCH_PARENT
                targetHeight = ViewGroup.LayoutParams.MATCH_PARENT
            }
            Prefs.SCALE_MODE_CROP -> {
                pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            }
        }

        val lp = pv.layoutParams
        if (lp is android.widget.FrameLayout.LayoutParams) {
            lp.width = targetWidth
            lp.height = targetHeight
            lp.gravity = android.view.Gravity.CENTER
            pv.layoutParams = lp
        } else {
            lp.width = targetWidth
            lp.height = targetHeight
            pv.layoutParams = lp
        }
    }

    override fun setDecoderMode(mode: Int) {
        this.currentDecoderMode = mode
    }

    override fun setCacheDuration(cacheMs: Int) {
        this.currentCacheMs = cacheMs
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun resume() {
        exoPlayer?.play()
    }
    
    override fun stop() {
        exoPlayer?.stop()
    }

    override fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }

    override fun getTime(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }

    override fun getDuration(): Long {
        val d = exoPlayer?.duration ?: 0L
        return if (d == androidx.media3.common.C.TIME_UNSET) 0L else d
    }

    override fun setTime(timeMs: Long) {
        exoPlayer?.seekTo(timeMs)
    }

    override fun setRate(rate: Float) {
        exoPlayer?.setPlaybackSpeed(rate)
    }

    // ── 音轨/字幕接口实现 ──

    override fun getAudioTracks(): List<AudioTrackInfo> {
        val tracks = exoPlayer?.currentTracks ?: return emptyList()
        val result = mutableListOf<AudioTrackInfo>()
        for ((groupIndex, group) in tracks.groups.withIndex()) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    result.add(AudioTrackInfo(
                        index = (groupIndex shl 16) or (i and 0xFFFF),
                        language = format.language ?: "und",
                        label = format.label ?: languageToDisplayName(format.language)
                            ?: "音轨 ${result.size + 1}",
                        codec = format.sampleMimeType?.substringAfter("/")?.uppercase() ?: "",
                        channelCount = format.channelCount,
                        isSelected = group.isTrackSelected(i)
                    ))
                }
            }
        }
        return result
    }

    override fun selectAudioTrack(index: Int) {
        val exo = exoPlayer ?: return
        val groupIndex = index ushr 16
        val trackIndex = index and 0xFFFF
        val groups = exo.currentTracks.groups
        if (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            if (group.type == C.TRACK_TYPE_AUDIO) {
                val builder = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                
                for (g in groups) {
                    if (g.type == C.TRACK_TYPE_AUDIO) {
                        if (g == group) {
                            builder.addOverride(TrackSelectionOverride(g.mediaTrackGroup, listOf(trackIndex)))
                        } else {
                            builder.addOverride(TrackSelectionOverride(g.mediaTrackGroup, emptyList<Int>()))
                        }
                    }
                }
                
                exo.trackSelectionParameters = builder.build()
            }
        }
    }

    override fun getSubtitleTracks(): List<SubtitleTrackInfo> {
        val tracks = exoPlayer?.currentTracks ?: return emptyList()
        val result = mutableListOf(SubtitleTrackInfo(
            index = -1, language = "", label = "关闭",
            isEmbedded = false, mimeType = "", isSelected = true
        ))
        var trackCounter = 1
        for ((groupIndex, group) in tracks.groups.withIndex()) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val isSelected = group.isTrackSelected(i)
                    if (isSelected) result[0] = result[0].copy(isSelected = false)
                    result.add(SubtitleTrackInfo(
                        index = (groupIndex shl 16) or (i and 0xFFFF),
                        language = format.language ?: "und",
                        label = format.label ?: languageToDisplayName(format.language)
                            ?: "字幕 $trackCounter",
                        isEmbedded = true,
                        mimeType = format.sampleMimeType ?: "",
                        isSelected = isSelected
                    ))
                    trackCounter++
                }
            }
        }
        return result
    }

    override fun selectSubtitleTrack(index: Int) {
        val exo = exoPlayer ?: return
        if (index < 0) { disableSubtitle(); return }
        
        val groupIndex = index ushr 16
        val trackIndex = index and 0xFFFF
        val groups = exo.currentTracks.groups
        if (groupIndex in groups.indices) {
            val group = groups[groupIndex]
            if (group.type == C.TRACK_TYPE_TEXT) {
                val builder = exo.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    
                for (g in groups) {
                    if (g.type == C.TRACK_TYPE_TEXT) {
                        if (g == group) {
                            builder.addOverride(TrackSelectionOverride(g.mediaTrackGroup, listOf(trackIndex)))
                        } else {
                            builder.addOverride(TrackSelectionOverride(g.mediaTrackGroup, emptyList<Int>()))
                        }
                    }
                }
                
                exo.trackSelectionParameters = builder.build()
            }
        }
    }

    override fun disableSubtitle() {
        val exo = exoPlayer ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    override fun loadExternalSubtitle(uri: Uri, mimeType: String): Boolean {
        val exo = exoPlayer ?: return false
        val currentPos = exo.currentPosition
        val wasPlaying = exo.isPlaying

        val originalItem = exo.currentMediaItem ?: return false
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()

        val newItem = originalItem.buildUpon()
            .setSubtitleConfigurations(
                originalItem.localConfiguration?.subtitleConfigurations.orEmpty() + subtitleConfig
            )
            .build()

        val factory = mediaSourceFactory ?: return false
        val mediaSource = factory.createMediaSource(newItem)
        exo.setMediaSource(mediaSource)
        exo.seekTo(currentPos)
        exo.prepare()
        if (wasPlaying) exo.play()
        return true
    }

    override fun release() {
        releasePlayer()
        videoLayout.removeView(playerView)
        playerView = null
    }
    
    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        isPlayerPlaying = false
        lastResolution = ""
    }
}

// 辅助：语言代码 → 中文显示名
private fun languageToDisplayName(code: String?): String? {
    return when (code?.take(2)) {
        "zh", "chi", "zho" -> "中文"
        "en", "eng" -> "英语"
        "ja", "jpn" -> "日语"
        "ko", "kor" -> "韩语"
        "fr", "fre", "fra" -> "法语"
        "de", "deu", "ger" -> "德语"
        "es", "spa" -> "西班牙语"
        "pt", "por" -> "葡萄牙语"
        "ru", "rus" -> "俄语"
        "ar", "ara" -> "阿拉伯语"
        "th", "tha" -> "泰语"
        "vi", "vie" -> "越南语"
        else -> code?.uppercase()
    }
}
