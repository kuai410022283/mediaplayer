/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.mediaplayer.app.util.RemoteLogger

/**
 * 频道独立记忆模型
 */
data class ChannelMemory(
    val channelId: Long,
    var lineIndex: Int? = null,
    var decoderMode: Int? = null,
    var playerCore: Int? = null,
    var audioTrackId: String? = null,
    var subtitleTrackId: String? = null,
    var lastUpdateTime: Long = System.currentTimeMillis()
) {
    fun isEmpty(): Boolean {
        return lineIndex == null && decoderMode == null && playerCore == null && audioTrackId == null && subtitleTrackId == null
    }
}

/**
 * 频道独立记忆管理器（单例）
 * 支持最大 2000 个频道的自动 LRU 淘汰机制
 */
object ChannelMemoryManager {
    private const val PREFS_NAME = "channel_memory_prefs"
    private const val MAX_MEMORY_COUNT = 2000
    
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    
    // 内存缓存
    private val memoryCache = mutableMapOf<Long, ChannelMemory>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadAllToCache()
    }

    private fun loadAllToCache() {
        memoryCache.clear()
        try {
            val allEntries = prefs.all
            for ((key, value) in allEntries) {
                if (key.startsWith("memory_") && value is String) {
                    val memory = gson.fromJson(value, ChannelMemory::class.java)
                    if (memory != null) {
                        memoryCache[memory.channelId] = memory
                    }
                }
            }
            RemoteLogger.i("ChannelMemory", "Loaded ${memoryCache.size} channel memories.")
        } catch (e: Exception) {
            RemoteLogger.e("ChannelMemory", "Failed to load channel memories: ${e.message}")
        }
    }

    private fun saveMemory(memory: ChannelMemory) {
        memory.lastUpdateTime = System.currentTimeMillis()
        memoryCache[memory.channelId] = memory
        
        enforceLruLimit()
        
        prefs.edit().putString("memory_${memory.channelId}", gson.toJson(memory)).apply()
    }

    private fun enforceLruLimit() {
        if (memoryCache.size <= MAX_MEMORY_COUNT) return
        
        // 超出限制，执行 LRU 淘汰
        val sortedList = memoryCache.values.sortedBy { it.lastUpdateTime }
        val toRemoveCount = memoryCache.size - MAX_MEMORY_COUNT
        
        val editor = prefs.edit()
        for (i in 0 until toRemoveCount) {
            val oldest = sortedList[i]
            memoryCache.remove(oldest.channelId)
            editor.remove("memory_${oldest.channelId}")
            RemoteLogger.i("ChannelMemory", "Evicted old memory for channel ${oldest.channelId}")
        }
        editor.apply()
    }

    fun getMemory(channelId: Long): ChannelMemory? {
        return memoryCache[channelId]
    }

    fun hasDecoderOrCoreMemory(channelId: Long): Boolean {
        val mem = memoryCache[channelId] ?: return false
        return mem.decoderMode != null || mem.playerCore != null
    }

    fun updateLineIndex(channelId: Long, lineIndex: Int) {
        val memory = memoryCache[channelId] ?: ChannelMemory(channelId)
        memory.lineIndex = lineIndex
        saveMemory(memory)
    }

    fun updateDecoder(channelId: Long, decoderMode: Int) {
        val memory = memoryCache[channelId] ?: ChannelMemory(channelId)
        memory.decoderMode = decoderMode
        saveMemory(memory)
    }

    fun updateCore(channelId: Long, playerCore: Int) {
        val memory = memoryCache[channelId] ?: ChannelMemory(channelId)
        memory.playerCore = playerCore
        saveMemory(memory)
    }

    fun updateTracks(channelId: Long, audioTrackId: String?, subtitleTrackId: String?) {
        // 如果都为空，没必要更新
        if (audioTrackId == null && subtitleTrackId == null) return
        
        val memory = memoryCache[channelId] ?: ChannelMemory(channelId)
        if (audioTrackId != null) memory.audioTrackId = audioTrackId
        if (subtitleTrackId != null) memory.subtitleTrackId = subtitleTrackId
        saveMemory(memory)
    }

    fun clearDecoderAndCore(channelId: Long) {
        val memory = memoryCache[channelId] ?: return
        memory.decoderMode = null
        memory.playerCore = null
        
        if (memory.isEmpty()) {
            memoryCache.remove(channelId)
            prefs.edit().remove("memory_$channelId").apply()
        } else {
            saveMemory(memory)
        }
    }
}
