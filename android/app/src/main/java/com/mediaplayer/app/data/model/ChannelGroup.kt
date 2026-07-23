/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data.model

import com.google.gson.annotations.SerializedName

data class ChannelGroup(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("icon") val icon: String = "",
    @SerializedName("sort_order") val sortOrder: Int = 0,
    @SerializedName("created_at") val createdAt: String = "",
    @SerializedName("updated_at") val updatedAt: String = ""
)
