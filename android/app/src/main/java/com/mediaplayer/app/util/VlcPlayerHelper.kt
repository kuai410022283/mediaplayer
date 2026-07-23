/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import com.mediaplayer.app.Prefs

class VlcPlayerHelper(
    private val context: Context,
    private val videoLayout: VLCVideoLayout,
    private val listener: IPlayerHelper.PlayerListener
) : IPlayerHelper {

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentScaleMode: Int = Prefs.SCALE_MODE_DEFAULT
    private var isTransitioning: Boolean = false

    init {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        currentScaleMode = prefs.getInt(Prefs.KEY_SCALE_MODE, Prefs.SCALE_MODE_DEFAULT)
        initPlayer()
    }

    private fun initPlayer() {
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val decoderMode = (context as? com.mediaplayer.app.ui.home.MainActivity)?.currentDecoderMode ?: prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)

        val options = ArrayList<String>()
        options.add("--aout=opensles")
        options.add("--audio-time-stretch")
        // 强制 RTSP 使用 TCP 传输，解决 UDP 在 Android/TV 盒子环境下容易丢包或被 NAT 拦截导致无法播放的问题
        options.add("--rtsp-tcp")
        options.add("--network-synchronisation") // 增加全局容错选项

        // We no longer add caching or jitter options globally here because they are applied per-Media based on URL in play().
        // options.add("--network-caching=$cacheMs")
        // options.add("--clock-jitter=0")
        // options.add("--clock-synchro=0")

        when (decoderMode) {
            Prefs.DECODER_MODE_HARDWARE -> {
                options.add("--avcodec-hw=any")
                options.add("--codec=mediacodec,all")
            }
            Prefs.DECODER_MODE_SOFTWARE -> {
                options.add("--avcodec-hw=none")
            }
            else -> { // AUTO
                options.add("--vout=android_display")
                options.add("--avcodec-hw=any")
            }
        }

        libVlc = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVlc)
        mediaPlayer?.attachViews(videoLayout, null, false, false)

        mediaPlayer?.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    listener.onBuffering(event.buffering)
                }
                MediaPlayer.Event.Playing -> {
                    // 先发送一个基础状态
                    listener.onPlaying("VLC")
                    
                    // VLC 在刚触发 Playing 时可能还未完全解析出轨道信息，延迟 1000ms 再次读取
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        var videoRes = ""
                        var audioCodec = ""
                        mediaPlayer?.media?.let { media ->
                            for (i in 0 until media.trackCount) {
                                val track = media.getTrack(i)
                                if (track.type == IMedia.Track.Type.Video) {
                                    val vt = track as IMedia.VideoTrack
                                    if (vt.width > 0 && vt.height > 0) {
                                        videoRes = "${vt.width}x${vt.height}"
                                    }
                                } else if (track.type == IMedia.Track.Type.Audio) {
                                    val at = track as IMedia.AudioTrack
                                    audioCodec = at.codec?.trim()?.uppercase() ?: ""
                                }
                            }
                        }
                        val info = buildString {
                            if (videoRes.isNotEmpty()) append(videoRes)
                            if (videoRes.isNotEmpty() && audioCodec.isNotEmpty()) append(" | ")
                            if (audioCodec.isNotEmpty()) append(audioCodec)
                        }
                        if (info.isNotEmpty()) {
                            listener.onPlaying(info) // 再次更新包含分辨率的状态
                        }
                        // 通知轨道列表变化
                        listener.onTracksChanged(
                            audioTracks = getAudioTracks(),
                            subtitleTracks = getSubtitleTracks()
                        )
                    }, 1000)
                }
                MediaPlayer.Event.EncounteredError -> {
                    if (!isTransitioning) {
                        com.mediaplayer.app.util.RemoteLogger.e("VLCPlayer", "Encountered internal playback error.")
                        listener.onError()
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    if (!isTransitioning) {
                        com.mediaplayer.app.util.RemoteLogger.i("VLCPlayer", "End of stream reached.")
                        listener.onPlaybackCompleted()
                    }
                }
            }
        }
    }

    override fun play(url: String, userAgent: String, customHeaders: String) {
        val player = mediaPlayer ?: return
        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val cacheMs = prefs.getInt(Prefs.KEY_NETWORK_CACHE, Prefs.DEFAULT_NETWORK_CACHE)

        // 停止旧播放，防止旧流在新流准备好之前继续输出音频，导致声音重叠
        isTransitioning = true
        player.stop()

        val media = Media(libVlc, Uri.parse(url))
        
        // 智能缓存判断逻辑
        var finalCacheMs = cacheMs
        var useAggressiveLatency = false
        if (cacheMs == 0) { // 自动模式
            val lowerUrl = url.lowercase()
            // 砍掉所有繁琐的 IP 判断，只认原生组播协议
            val isMulticast = lowerUrl.startsWith("udp://") || lowerUrl.startsWith("rtp://")
            
            if (isMulticast) {
                finalCacheMs = 300 // 原生组播流 300ms 保证秒换台
            } else {
                finalCacheMs = 1500 // HTTP/HTTPS流(含内网代理和公网直连) 1500ms 保证抗抖动
            }
            useAggressiveLatency = false // 自动模式下，不再强制开启激进防抖屏蔽
        } else {
            // 如果用户手动设置了很低的缓存（<= 200ms），则开启激进模式
            useAggressiveLatency = cacheMs <= 200
        }

        media.addOption(":network-caching=$finalCacheMs")
        media.addOption(":live-caching=$finalCacheMs")
        if (useAggressiveLatency) {
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
        }
        
        media.addOption(":http-reconnect=true")

        val decoderMode = (context as? com.mediaplayer.app.ui.home.MainActivity)?.currentDecoderMode ?: prefs.getInt(Prefs.KEY_DECODER_MODE, Prefs.DECODER_MODE_AUTO)
        when (decoderMode) {
            Prefs.DECODER_MODE_HARDWARE -> media.setHWDecoderEnabled(true, true)
            Prefs.DECODER_MODE_SOFTWARE -> media.setHWDecoderEnabled(false, false)
            else -> media.setHWDecoderEnabled(true, false) // 自动
        }

        applyMediaOptions(media, url, userAgent, customHeaders)
        player.media = media
        isTransitioning = false
        player.play()
        applyScaleMode()
    }

    private fun applyMediaOptions(media: Media, url: String, userAgent: String?, customHeaders: String?) {
        var finalUserAgent = userAgent ?: "TVPlayer/1.0"
        
        // 动态添加系统 Token 到 User-Agent，防止 Token 在地址栏暴露 (针对不支持 Authorization 头的 VLC)
        val serverUrl = com.mediaplayer.app.data.api.ApiClient.getServerUrl()
        if (url.startsWith(serverUrl)) {
            val token = com.mediaplayer.app.data.api.ApiClient.accessToken
            if (!token.isNullOrEmpty()) {
                finalUserAgent = "$finalUserAgent (Token=$token)"
            }
        }
        
        media.addOption(":http-user-agent=$finalUserAgent")
        if (!customHeaders.isNullOrEmpty()) {
            try {
                val json = JSONObject(customHeaders)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.getString(key)
                    if (key.equals("referer", ignoreCase = true) || key.equals("referrer", ignoreCase = true)) {
                        media.addOption(":http-referrer=$value")
                    } else if (key.equals("origin", ignoreCase = true)) {
                        media.addOption(":http-origin=$value")
                    } else if (key.equals("cookie", ignoreCase = true)) {
                        media.addOption(":http-cookies=$value")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun applyScaleMode() {
        when (currentScaleMode) {
            Prefs.SCALE_MODE_STRETCH -> {
                mediaPlayer?.aspectRatio = "16:9"
                mediaPlayer?.scale = 0f
            }
            Prefs.SCALE_MODE_16_10 -> {
                mediaPlayer?.aspectRatio = "16:10"
                mediaPlayer?.scale = 0f
            }
            Prefs.SCALE_MODE_4_3 -> {
                mediaPlayer?.aspectRatio = "4:3"
                mediaPlayer?.scale = 0f
            }
            Prefs.SCALE_MODE_FILL -> {
                val w = videoLayout.width
                val h = videoLayout.height
                if (w > 0 && h > 0) {
                    mediaPlayer?.aspectRatio = "$w:$h"
                } else {
                    mediaPlayer?.aspectRatio = null
                }
                mediaPlayer?.scale = 0f
            }
            Prefs.SCALE_MODE_CROP -> {
                mediaPlayer?.aspectRatio = null
                mediaPlayer?.scale = 0f // libvlc default fit
            }
            else -> {
                mediaPlayer?.aspectRatio = null
                mediaPlayer?.scale = 0f
            }
        }
    }

    override fun setAspectRatio(scaleMode: Int) {
        this.currentScaleMode = scaleMode
        applyScaleMode()
    }

    override fun setDecoderMode(mode: Int) {}
    override fun setCacheDuration(cacheMs: Int) {}

    override fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying ?: false
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun resume() {
        mediaPlayer?.play()
    }
    
    override fun stop() {
        mediaPlayer?.stop()
    }

    override fun getTime(): Long {
        return mediaPlayer?.time ?: 0L
    }

    override fun getDuration(): Long {
        return mediaPlayer?.length ?: 0L
    }

    override fun setTime(timeMs: Long) {
        mediaPlayer?.time = timeMs
    }

    fun getRate(): Float {
        return mediaPlayer?.rate ?: 1.0f
    }

    override fun setRate(rate: Float) {
        mediaPlayer?.rate = rate
    }

    // ── 音轨/字幕接口实现 ──

    override fun getAudioTracks(): List<AudioTrackInfo> {
        val mp = mediaPlayer ?: return emptyList()
        val descriptions = mp.audioTracks ?: return emptyList()
        val currentId = mp.audioTrack
        return descriptions.filter { it.id >= 0 }.map { desc ->
            AudioTrackInfo(
                index = desc.id,
                language = desc.name?.take(2)?.lowercase() ?: "und",
                label = desc.name ?: "音轨 ${desc.id}",
                codec = "",
                channelCount = 0,
                isSelected = desc.id == currentId
            )
        }
    }

    override fun selectAudioTrack(index: Int) {
        mediaPlayer?.audioTrack = index
    }

    override fun getSubtitleTracks(): List<SubtitleTrackInfo> {
        val mp = mediaPlayer ?: return emptyList()
        val descriptions = mp.spuTracks ?: return emptyList()
        val currentId = mp.spuTrack
        val result = mutableListOf(SubtitleTrackInfo(
            index = -1, language = "", label = "关闭",
            isEmbedded = false, mimeType = "",
            isSelected = currentId == -1
        ))
        descriptions.filter { it.id >= 0 }.forEach { desc ->
            result.add(SubtitleTrackInfo(
                index = desc.id,
                language = desc.name?.take(2)?.lowercase() ?: "und",
                label = desc.name ?: "字幕 ${desc.id}",
                isEmbedded = true,
                mimeType = "",
                isSelected = desc.id == currentId
            ))
        }
        return result
    }

    override fun selectSubtitleTrack(index: Int) {
        mediaPlayer?.spuTrack = index
    }

    override fun disableSubtitle() {
        mediaPlayer?.spuTrack = -1
    }

    override fun loadExternalSubtitle(uri: Uri, mimeType: String): Boolean {
        return try {
            mediaPlayer?.addSlave(IMedia.Slave.Type.Subtitle, uri.toString(), true)
            true
        } catch (e: Exception) {
            com.mediaplayer.app.util.RemoteLogger.e("VLC", "Failed to load subtitle", e)
            false
        }
    }

    override fun release() {
        mediaPlayer?.release()
        libVlc?.release()
        mediaPlayer = null
        libVlc = null
    }
}
