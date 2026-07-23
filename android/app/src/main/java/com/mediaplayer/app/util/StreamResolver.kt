/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object StreamResolver {

    // 使用不自动跟随重定向的客户端，手动接管重定向逻辑，支持 HTTPS <-> HTTP 的跨协议降级/升级
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    suspend fun resolve(originalUrl: String, userAgent: String?, customHeaders: String?): String {
        return withContext(Dispatchers.IO) {
            var currentUrl = originalUrl
            if (currentUrl.startsWith("/")) {
                val serverUrl = com.mediaplayer.app.data.api.ApiClient.getServerUrl().trimEnd('/')
                currentUrl = serverUrl + currentUrl
            }

            // 代理 URL 不需要探测重定向（直接由播放器带 Token 请求），直接返回
            if (currentUrl.contains("/api/v1/stream/proxy/") ||
                currentUrl.contains("/api/v1/stream/catchup/")) {
                return@withContext currentUrl
            }

            var redirects = 0
            val maxRedirects = 5

            while (redirects < maxRedirects) {
                // 检查协程是否已被取消，及时退出避免不必要的 HTTP 请求
                if (!isActive) return@withContext currentUrl

                if (currentUrl.startsWith("file://", ignoreCase = true)) {
                    if (currentUrl.endsWith(".strm", ignoreCase = true)) {
                        try {
                            var extractedUrl: String? = null
                            val file = java.io.File(java.net.URI(currentUrl))
                            if (file.exists()) {
                                file.bufferedReader().use { reader ->
                                    var charsRead = 0
                                    var line: String? = reader.readLine()
                                    while (line != null && charsRead < 10240) {
                                        val currentLine = line ?: break
                                        charsRead += currentLine.length
                                        val trimmed = currentLine.trim()
                                        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                                            extractedUrl = trimmed
                                            break
                                        }
                                        line = reader.readLine()
                                    }
                                }
                            }
                            if (extractedUrl != null) {
                                currentUrl = extractedUrl!!
                                redirects++
                                continue
                            } else {
                                break // 找不到链接兜底返回
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            break
                        }
                    } else {
                        break // 本地非 strm 文件，直接返回原始 file:// 供播放器读取
                    }
                }

                try {
                    val requestBuilder = Request.Builder().url(currentUrl)

                    val ua = if (userAgent.isNullOrEmpty()) "Mozilla/5.0 (Linux; Android 10; TV) AppleWebKit/537.36 TV-Player" else userAgent
                    requestBuilder.header("User-Agent", ua)

                    if (!customHeaders.isNullOrEmpty()) {
                        try {
                            val json = JSONObject(customHeaders)
                            val keys = json.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                val value = json.getString(key)
                                requestBuilder.header(key, value)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    // 使用 GET 请求，因为有些服务端会拦截 HEAD 请求。
                    // 只要在读取到响应头后立即调用 response.close()，就不会下载响应体，不会浪费带宽。
                    requestBuilder.get()

                    val response = client.newCall(requestBuilder.build()).execute()
                    
                    // HTTP 请求返回后检查协程是否已被取消
                    if (!isActive) {
                        response.close()
                        return@withContext currentUrl
                    }
                    
                    val code = response.code
                    val isRedirect = code in 300..399

                    if (isRedirect) {
                        val location = response.header("Location")
                        response.close()
                        if (!location.isNullOrEmpty()) {
                            currentUrl = if (location.startsWith("http://", ignoreCase = true) || location.startsWith("https://", ignoreCase = true)) {
                                location
                            } else {
                                val baseUri = java.net.URI(currentUrl)
                                baseUri.resolve(location).toString()
                            }
                            redirects++
                            continue
                        } else {
                            break
                        }
                    } else {
                        // 如果是 .strm 文件且请求成功，尝试解析提取真实的视频直链
                        if (code == 200 && currentUrl.endsWith(".strm", ignoreCase = true)) {
                            var extractedUrl: String? = null
                            try {
                                response.body?.byteStream()?.let { stream ->
                                    val reader = java.io.BufferedReader(java.io.InputStreamReader(stream))
                                    var charsRead = 0
                                    var line: String? = reader.readLine()
                                    while (line != null && charsRead < 10240) { // 最多读取 ~10KB 防止内存爆炸
                                        val currentLine = line ?: break
                                        charsRead += currentLine.length
                                        val trimmed = currentLine.trim()
                                        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                                            extractedUrl = trimmed
                                            break
                                        }
                                        line = reader.readLine()
                                    }
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            response.close()
                            
                            if (extractedUrl != null) {
                                currentUrl = extractedUrl!!
                                redirects++
                                continue
                            }
                        } else {
                            response.close()
                        }
                        
                        // 遇到非重定向状态（如 200 OK），说明这就是真实的流地址
                        break
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    break // 发生异常时直接中断，返回目前获取到的地址
                }
            }
            currentUrl
        }
    }
}
