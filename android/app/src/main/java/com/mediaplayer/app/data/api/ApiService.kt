/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data.api

import com.mediaplayer.app.data.model.*
import retrofit2.Response
import retrofit2.http.*

import com.google.gson.JsonElement

/**
 * Retrofit API 接口定义 —— 对应后端 /api/v1/ 路由
 */
interface ApiService {

    // ── 管理员认证 ─────────────────────────────────────

    @POST("admin/login")
    suspend fun adminLogin(@Body body: Map<String, String>): Response<APIResponse<JsonElement>>

    // ── 客户端注册 & 验证 ──────────────────────────────

    @POST("client/register")
    suspend fun clientRegister(@Body body: Map<String, String>): Response<APIResponse<ClientRegisterResp>>

    @GET("client/verify")
    suspend fun clientVerify(@Header("Authorization") token: String): Response<APIResponse<VerifyResponse>>

    @GET("client/me")
    suspend fun clientMe(): Response<APIResponse<JsonElement>>

    // ── 客户端日志 ───────────────────────────────────────

    @Multipart
    @POST("client/logs")
    suspend fun clientUploadLog(
        @Part logFile: okhttp3.MultipartBody.Part
    ): Response<APIResponse<JsonElement>>

    // ── 频道分组 ───────────────────────────────────────

    @GET("groups")
    suspend fun getGroups(): Response<APIResponse<List<ChannelGroup>>>

    // ── 频道 ───────────────────────────────────────────

    @GET("channels")
    suspend fun getChannels(
        @Query("group_id") groupId: Long? = null,
        @Query("search") search: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 200
    ): Response<APIResponse<PageResponse<Channel>>>

    @GET("channels/{id}")
    suspend fun getChannel(@Path("id") id: Long): Response<APIResponse<Channel>>


    // ── EPG ────────────────────────────────────────────

    @GET("epg")
    suspend fun getEPG(@Query("channel_id") channelId: String): Response<APIResponse<List<EPGProgram>>>

    // ── 播放历史 ───────────────────────────────────────

    @POST("history")
    suspend fun addHistory(@Body body: @JvmSuppressWildcards Map<String, Any>): Response<APIResponse<JsonElement>>

    // ── 检查更新 ───────────────────────────────────────

    @GET("update")
    suspend fun checkUpdate(@Query("arch") arch: String): Response<APIResponse<AppUpdateConfig>>
}
