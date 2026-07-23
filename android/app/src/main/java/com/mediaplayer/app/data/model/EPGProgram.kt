/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data.model

import com.google.gson.annotations.SerializedName

data class EPGProgram(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("channel_id") val channelId: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("start_time") val startTime: String = "",
    @SerializedName("end_time") val endTime: String = "",
    @SerializedName("description") val description: String = ""
)
