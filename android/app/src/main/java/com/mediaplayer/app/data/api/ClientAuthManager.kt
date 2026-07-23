/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data.api

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.mediaplayer.app.Prefs
import com.mediaplayer.app.data.model.ClientRegisterResp
import com.mediaplayer.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 客户端认证管理器 —— 处理设备注册、token 持久化、状态轮询。
 */
class ClientAuthManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    fun getDeviceId(): String {
        var id = prefs.getString(Prefs.KEY_DEVICE_ID, null)
        if (id.isNullOrEmpty()) {
            @SuppressLint("HardwareIds")
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            id = androidId ?: UUID.randomUUID().toString()
            prefs.edit().putString(Prefs.KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun getToken(): String? = prefs.getString(Prefs.KEY_ACCESS_TOKEN, null)

    fun getStatus(): String? = prefs.getString(Prefs.KEY_CLIENT_STATUS, null)

    fun getPlanName(): String? = prefs.getString(Prefs.KEY_PLAN_NAME, null)

    fun getExpiresAt(): String? = prefs.getString(Prefs.KEY_EXPIRES_AT, null)

    fun getServerName(): String? = prefs.getString(Prefs.KEY_SERVER_NAME, null)

    fun getClientId(): Long {
        return prefs.getLong(Prefs.KEY_CLIENT_ID, 0)
    }

    fun isApproved(): Boolean {
        return getStatus() == "approved" && getToken() != null
    }

    fun getLocation(): String? = prefs.getString("device_location", null)

    /** 注册设备到后端 */
    suspend fun register(): Result<ClientRegisterResp> = withContext(Dispatchers.IO) {
        try {
            val body = mutableMapOf(
                "name" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "device_id" to getDeviceId(),
                "device_model" to Build.MODEL,
                "device_os" to "Android ${Build.VERSION.RELEASE}",
                "app_version" to BuildConfig.VERSION_NAME
            )
            getLocation()?.let {
                body["note"] = it
            }
            val response = ApiClient.getService().clientRegister(body)
            if (response.isSuccessful) {
                val resp = response.body()!!
                val data = resp.data
                if (data != null) {
                    saveAuth(data)
                    Result.success(data)
                } else {
                    Result.failure(Exception(resp.message))
                }
            } else {
                Result.failure(Exception("注册失败: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 验证当前 token 是否有效 */
    suspend fun verify(): Result<com.mediaplayer.app.data.model.VerifyResponse?> = withContext(Dispatchers.IO) {
        try {
            val token = getToken() ?: return@withContext Result.failure(Exception("无令牌"))
            val response = ApiClient.getService().clientVerify("Bearer $token")
            if (response.isSuccessful && response.body()?.code == 0) {
                val data = response.body()?.data
                if (data != null) {
                    prefs.edit().apply {
                        putBoolean(Prefs.KEY_ENABLE_LOG, data.enableLog)
                        putString(Prefs.KEY_PLAN_NAME, data.planName ?: "")
                        putString(Prefs.KEY_EXPIRES_AT, data.expiresAt ?: "")
                        putString(Prefs.KEY_SERVER_NAME, data.serverName ?: "")
                        apply()
                    }
                    com.mediaplayer.app.util.RemoteLogger.updateConfig(data.enableLog)
                }
                Result.success(data)
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 轮询检查审批状态 */
    suspend fun checkStatus(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = getToken()
            if (token != null) {
                val response = ApiClient.getService().clientVerify("Bearer $token")
                if (response.isSuccessful && response.body()?.code == 0) {
                    val data = response.body()?.data
                    if (data != null) {
                        prefs.edit().apply {
                            putString(Prefs.KEY_CLIENT_STATUS, "approved")
                            putString(Prefs.KEY_PLAN_NAME, data.planName ?: "")
                            putString(Prefs.KEY_EXPIRES_AT, data.expiresAt ?: "")
                            putString(Prefs.KEY_SERVER_NAME, data.serverName ?: "")
                            apply()
                        }
                    } else {
                        prefs.edit().putString(Prefs.KEY_CLIENT_STATUS, "approved").apply()
                    }
                    return@withContext Result.success("approved")
                }
            }
            
            // 没有 token 或者验证失败，调用 register 接口获取最新状态
            val name = "${Build.MANUFACTURER} ${Build.MODEL}"
            val regBody = mutableMapOf(
                "name" to name, 
                "device_id" to getDeviceId(),
                "device_model" to Build.MODEL,
                "device_os" to "Android ${Build.VERSION.RELEASE}",
                "app_version" to BuildConfig.VERSION_NAME
            )
            getLocation()?.let {
                regBody["note"] = it
            }
            val regResp = ApiClient.getService().clientRegister(regBody)
            if (regResp.isSuccessful) {
                val resp = regResp.body()
                val data = resp?.data
                if (data != null) {
                    saveAuth(data)
                    Result.success(data.status)
                } else {
                    Result.success(prefs.getString(Prefs.KEY_CLIENT_STATUS, "pending") ?: "pending")
                }
            } else {
                Result.success(prefs.getString(Prefs.KEY_CLIENT_STATUS, "pending") ?: "pending")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveAuth(resp: ClientRegisterResp) {
        prefs.edit().apply {
            resp.accessToken.takeIf { it.isNotEmpty() }?.let {
                putString(Prefs.KEY_ACCESS_TOKEN, it)
                ApiClient.accessToken = it
            }
            putLong(Prefs.KEY_CLIENT_ID, resp.clientId)
            putString(Prefs.KEY_CLIENT_STATUS, resp.status)
            putBoolean(Prefs.KEY_ENABLE_LOG, resp.enableLog)
            if (!resp.expiresAt.isNullOrEmpty()) {
                putString(Prefs.KEY_EXPIRES_AT, resp.expiresAt)
            }
            putString(Prefs.KEY_SERVER_NAME, resp.serverName ?: "")
            apply()
        }
        com.mediaplayer.app.util.RemoteLogger.updateConfig(resp.enableLog)
        // 同步到 ApiClient
        resp.accessToken.takeIf { it.isNotEmpty() }?.let {
            ApiClient.accessToken = it
        }
    }

    fun clearAuth() {
        prefs.edit().apply {
            remove(Prefs.KEY_ACCESS_TOKEN)
            remove(Prefs.KEY_CLIENT_ID)
            remove(Prefs.KEY_CLIENT_STATUS)
            apply()
        }
        ApiClient.accessToken = null
    }
}
