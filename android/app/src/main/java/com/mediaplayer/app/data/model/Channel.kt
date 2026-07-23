/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data.model

import com.google.gson.annotations.SerializedName

data class ChannelLine(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("stream_url") val streamUrl: String = "",
    @SerializedName("stream_type") val streamType: String = "hls",
    @SerializedName("content_type") val contentType: String = "",
    @SerializedName("user_agent") val userAgent: String = "",
    @SerializedName("custom_headers") val customHeaders: String = "",
    @SerializedName("support_catchup") val supportCatchup: Boolean = false,
    @SerializedName("catchup_days") val catchupDays: Int = 0
)

data class Channel(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("group_id") val groupId: Long = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("logo") val logo: String = "",
    @SerializedName("description") val description: String = "",
    @SerializedName("current_epg") var currentEpg: String = "",
    @SerializedName("next_epg") var nextEpg: String = "",
    @SerializedName("epg_percent") var epgPercent: Int = 0,

    @SerializedName("sort_order") val sortOrder: Int = 0,
    @SerializedName("lines") val lines: List<ChannelLine> = emptyList(),
    
    // 兼容旧接口的冗余字段（避免崩溃）
    @SerializedName("stream_url") val legacyStreamUrl: String = "",
    @SerializedName("stream_type") val legacyStreamType: String = "hls",
    @SerializedName("user_agent") val legacyUserAgent: String = "",
    @SerializedName("custom_headers") val legacyCustomHeaders: String = "",
    @SerializedName("support_catchup") val supportCatchup: Boolean = false,
    @SerializedName("catchup_type") val catchupType: String = "",
    @SerializedName("catchup_source") val catchupSource: String = "",
    @SerializedName("catchup_days") val catchupDays: Int = 0
) {
    @Transient var globalIndex: Int = -1

    fun getLinesSafely(): List<ChannelLine> {
        if (lines.isNotEmpty()) return lines
        if (legacyStreamUrl.isNotEmpty()) {
            return listOf(ChannelLine(id, legacyStreamUrl, legacyStreamType, "", legacyUserAgent, legacyCustomHeaders, supportCatchup, catchupDays))
        }
        return emptyList()
    }

    fun getDynamicEpgPercent(): Int {
        if (currentEpg.isEmpty()) return epgPercent
        try {
            val parts = currentEpg.split(" ")
            if (parts.isNotEmpty()) {
                val timeRange = parts[0]
                val times = timeRange.split("-")
                if (times.size == 2) {
                    val startParts = times[0].split(":")
                    val endParts = times[1].split(":")
                    if (startParts.size == 2 && endParts.size == 2) {
                        val startMinOfDay = startParts[0].toInt() * 60 + startParts[1].toInt()
                        val endMinOfDay = endParts[0].toInt() * 60 + endParts[1].toInt()
                        
                        val now = java.util.Calendar.getInstance()
                        val currentMinOfDay = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
                        
                        // Handle crossing midnight
                        val totalDuration = if (endMinOfDay < startMinOfDay) {
                            (24 * 60 - startMinOfDay) + endMinOfDay
                        } else {
                            endMinOfDay - startMinOfDay
                        }
                        
                        val elapsed = if (endMinOfDay < startMinOfDay) {
                            if (currentMinOfDay < startMinOfDay) { // after midnight
                                (24 * 60 - startMinOfDay) + currentMinOfDay
                            } else { // before midnight
                                currentMinOfDay - startMinOfDay
                            }
                        } else {
                            currentMinOfDay - startMinOfDay
                        }
                        
                        if (elapsed in 0..totalDuration) {
                            if (totalDuration == 0) return 100
                            return ((elapsed.toFloat() / totalDuration) * 100).toInt().coerceIn(0, 100)
                        } else if (elapsed > totalDuration) {
                            return 100
                        } else {
                            return 0
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return epgPercent
    }
}
