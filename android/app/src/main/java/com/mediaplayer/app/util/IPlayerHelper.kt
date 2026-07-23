/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.net.Uri

// ── 音轨/字幕数据结构 ──

data class AudioTrackInfo(
    val index: Int,           // 轨道索引（播放器内部 ID）
    val language: String,     // ISO 639 语言代码 (zh, en, ja, und)
    val label: String,        // 显示名称（国语、英语、导演评论...）
    val codec: String,        // 编码格式 (AAC, AC3, EAC3, OPUS, DTS...)
    val channelCount: Int,    // 声道数 (2=立体声, 6=5.1, 8=7.1)
    val isSelected: Boolean   // 是否为当前选中
) {
    val id: String get() = "${language}_${label}"
}

data class SubtitleTrackInfo(
    val index: Int,           // 轨道索引（-1 表示"关闭"）
    val language: String,     // ISO 639 语言代码
    val label: String,        // 显示名称
    val isEmbedded: Boolean,  // 是否内嵌于容器中
    val mimeType: String,     // MIME 类型（用于外挂字幕格式识别）
    val isSelected: Boolean   // 是否为当前选中
) {
    val id: String get() = "${language}_${label}"
}

interface IPlayerHelper {
    /**
     * Start playback
     */
    fun play(url: String, userAgent: String = "", customHeaders: String = "")
    
    fun pause()
    fun resume()
    fun stop()
    fun release()
    
    fun isPlaying(): Boolean
    fun getTime(): Long
    fun getDuration(): Long
    fun setTime(timeMs: Long)
    fun setRate(rate: Float)
    
    /**
     * Settings passthrough
     * @param scaleMode 0=Auto, 1=Stretch, 2=Crop, 3=4:3
     */
    fun setAspectRatio(scaleMode: Int)
    
    /**
     * Settings passthrough
     * @param mode 0=Auto, 1=Hardware, 2=Software
     */
    fun setDecoderMode(mode: Int)
    
    /**
     * Settings passthrough
     * @param cacheMs 0=Auto, or value in milliseconds
     */
    fun setCacheDuration(cacheMs: Int)

    // ── 音轨/字幕接口（默认空实现，向后兼容） ──

    /** 获取可用音轨列表（仅在播放器 STATE_READY 后有效） */
    fun getAudioTracks(): List<AudioTrackInfo> = emptyList()

    /** 切换音轨（运行时无缝切换，无需重建播放器） */
    fun selectAudioTrack(index: Int) {}

    /** 获取可用字幕列表（包含内嵌 + 已加载的外挂字幕） */
    fun getSubtitleTracks(): List<SubtitleTrackInfo> = emptyList()

    /** 切换内嵌字幕 */
    fun selectSubtitleTrack(index: Int) {}

    /** 关闭字幕显示 */
    fun disableSubtitle() {}

    /** 加载外挂字幕文件 */
    fun loadExternalSubtitle(uri: Uri, mimeType: String): Boolean = false

    interface PlayerListener {
        /**
         * @param percent 0-100 float representing buffer percentage
         */
        fun onBuffering(percent: Float)
        
        /**
         * Called when playback officially starts
         * @param resolution Video resolution string e.g. "1920x1080"
         */
        fun onPlaying(resolution: String)
        
        /**
         * Called when playback completes naturally (e.g. catchup stream ended)
         */
        fun onPlaybackCompleted()

        /**
         * Called on playback error
         */
        fun onError()

        /**
         * Called when media format details are extracted
         */
        fun onMediaInfoReady(badgeInfo: StreamBadgeInfo) {}

        /**
         * 当可用轨道列表发生变化时回调
         * 触发时机：STATE_READY、内核重建后、媒体解析完成
         * 默认空实现（向后兼容）
         */
        fun onTracksChanged(
            audioTracks: List<AudioTrackInfo>,
            subtitleTracks: List<SubtitleTrackInfo>
        ) {}
    }
}

data class StreamBadgeInfo(
    val isDolbyVision: Boolean = false,
    val isHdr10: Boolean = false,
    val isHlg: Boolean = false,
    val isDolbyAtmos: Boolean = false,
    val isDolbyAudio: Boolean = false,
    val isDts: Boolean = false,
    val audioCodec: String = "",
    val videoCodec: String = ""
)
