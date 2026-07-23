/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data.model

import com.google.gson.annotations.SerializedName

data class VerifyResponse(
    @SerializedName("client_id") val clientId: Long = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("server_name") val serverName: String? = null,
    @SerializedName("announcement") val announcement: String? = null,
    @SerializedName("announcement_interval") val announcementInterval: Int = 0,
    @SerializedName("enable_log") val enableLog: Boolean = false,
    @SerializedName("startup_media_enabled") val startupMediaEnabled: Boolean = false,
    @SerializedName("startup_media") val startupMedia: String? = null,
    @SerializedName("startup_media_type") val startupMediaType: String = "image",
    @SerializedName("startup_duration") val startupDuration: Int = 5,
    @SerializedName("startup_skip_after") val startupSkipAfter: Int = 0,
    @SerializedName("plan_name") val planName: String? = null,
    @SerializedName("expires_at") val expiresAt: String? = null,
    @SerializedName("global_maintenance") val globalMaintenance: Boolean = false,
    @SerializedName("backup_servers") val backupServers: List<String>? = null,
    @SerializedName("is_tester") val isTester: Boolean = false
)
