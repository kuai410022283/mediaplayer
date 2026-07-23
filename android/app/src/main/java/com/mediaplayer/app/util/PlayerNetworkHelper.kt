/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object PlayerNetworkHelper {

    private var playerOkHttpClient: OkHttpClient? = null

    /**
     * 获取专用于播放器的 OkHttpClient。
     * 配置了：
     * 1. 较长的超时时间（应对弱网）
     * 2. 信任所有 SSL 证书（应对老旧 Android TV 设备 Let's Encrypt 根证书过期的问题）
     * 3. 禁用重定向（通常在 StreamResolver 已经做了解析，或者交给 ExoPlayer 自己处理）
     */
    fun getPlayerOkHttpClient(): OkHttpClient {
        if (playerOkHttpClient == null) {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                // 允许跨协议重定向等在 ExoPlayer 层面配置，但在底层 OkHttp 可以放开基本限制
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)

            try {
                // 创建一个不验证证书链的信任管理器
                val trustAllCerts = arrayOf<TrustManager>(
                    @SuppressLint("CustomX509TrustManager")
                    object : X509TrustManager {
                        @SuppressLint("TrustAllX509TrustManager")
                        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}

                        @SuppressLint("TrustAllX509TrustManager")
                        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}

                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                )

                // 安装信任所有证书的 trust manager
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                
                // 创建一个忽略主机名验证的 HostnameVerifier
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
                
            } catch (e: Exception) {
                RemoteLogger.e("PlayerNetworkHelper", "Failed to setup unsafe SSL context", e)
            }

            playerOkHttpClient = builder.build()
        }
        return playerOkHttpClient!!
    }
}
