/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.data.api

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * API 客户端单例 —— 管理 Retrofit 实例和服务器地址。
 * 使用前必须调用 [init] 设置服务器地址。
 */
object ApiClient {

    private var serverUrl: String = com.mediaplayer.app.Prefs.DEFAULT_SERVER_URL
    private var retrofit: Retrofit? = null
    private var apiService: ApiService? = null
    private var okHttpClient: OkHttpClient? = null

    /** 当前使用的 token（由 ClientAuthManager 设置） */
    var accessToken: String? = null

    fun formatUrl(url: String): String {
        var clean = url.trim().trimEnd('/')
        if (clean.isNotEmpty() && !clean.startsWith("http://", ignoreCase = true) && !clean.startsWith("https://", ignoreCase = true)) {
            clean = "http://$clean"
        }
        return clean
    }

    fun init(url: String) {
        val normalized = formatUrl(url)
        if (normalized == serverUrl && retrofit != null) return
        serverUrl = normalized
        retrofit = null
        apiService = null
        okHttpClient = null
    }

    fun reset() {
        retrofit = null
        apiService = null
        okHttpClient = null
        accessToken = null
    }

    fun getServerUrl(): String = serverUrl

    fun getService(): ApiService {
        if (apiService == null) {
            apiService = getRetrofit().create(ApiService::class.java)
        }
        return apiService!!
    }

    fun getOkHttpClient(): OkHttpClient {
        if (okHttpClient == null) {
            val logging = HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
                override fun log(message: String) {
                    if (message.contains("Exception") || message.contains("Failed") || message.contains("error", ignoreCase = true)) {
                        com.mediaplayer.app.util.RemoteLogger.e("OkHttp", message)
                    } else {
                        com.mediaplayer.app.util.RemoteLogger.d("OkHttp", message)
                    }
                }
            }).apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val original = chain.request()
                    val builder = original.newBuilder()
                    val requestUrl = original.url.toString()
                    if (requestUrl.startsWith(serverUrl)) {
                        accessToken?.let { builder.header("Authorization", "Bearer $it") }
                    }
                    chain.proceed(builder.build())
                }
                .addInterceptor(logging)
                .build()
        }
        return okHttpClient!!
    }

    private fun getRetrofit(): Retrofit {
        if (retrofit == null) {
            val gson = GsonBuilder()
                .setLenient()
                .create()

            retrofit = Retrofit.Builder()
                .baseUrl("$serverUrl/api/v1/")
                .client(getOkHttpClient())
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
        }
        return retrofit!!
    }

    /** 获取流代理 URL */
    fun getStreamProxyUrl(channelId: Long): String {
        return "$serverUrl/api/v1/stream/proxy/$channelId"
    }

    /** 获取回看流 URL */
    fun getCatchupUrl(channelId: Long, startTimeUnix: Long, endTimeUnix: Long): String {
        return "$serverUrl/api/v1/stream/catchup/$channelId?start=$startTimeUnix&end=$endTimeUnix"
    }
}
