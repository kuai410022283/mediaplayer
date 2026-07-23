/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.net.ServerSocket
import java.net.URL

/**
 * 嵌入式 Web 配置服务器。
 * 启动后用户通过浏览器访问 http://<电视IP>:<port>/ 填写服务器配置。
 *
 * @param context    Android Context
 * @param port       监听端口。传入 0 表示自动查找可用端口（从 9528 开始递增探测）
 * @param onUrlSaved 配置保存后的回调，参数为解析后的服务器地址列表（第一项为主服务器）
 */
class ConfigWebServer(
    private val context: Context,
    port: Int = 0,
    private val onUrlSaved: (List<String>) -> Unit
) : NanoHTTPD(if (port > 0) port else findAvailablePort(9528)) {

    /** 实际监听端口（与传入 NanoHTTPD 构造函数的端口一致）。 */
    val actualPort: Int = if (port > 0) port else findAvailablePort(9528)

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri

        if (method == Method.GET && uri == "/") {
            return newFixedLengthResponse(getHtmlForm())
        }

        if (method == Method.POST && uri == "/save") {
            try {
                val map = HashMap<String, String>()
                session.parseBody(map)
                val params = session.parameters
                val rawInput = params["server_url"]?.firstOrNull()?.trim()
                val location = params["location"]?.firstOrNull()?.trim()

                if (rawInput.isNullOrEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_HTML, getErrorHtml("请输入配置信息"))
                }

                val resolvedUrls = parseMultipleUrls(rawInput)
                if (resolvedUrls.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_HTML, getErrorHtml("所有配置信息均无效，请检查后重试"))
                }

                val prefs = context.getSharedPreferences(com.mediaplayer.app.Prefs.FILE, Context.MODE_PRIVATE)
                if (!location.isNullOrEmpty()) {
                    prefs.edit().putString("device_location", location).apply()
                } else {
                    prefs.edit().remove("device_location").apply()
                }

                onUrlSaved(resolvedUrls)
                return newFixedLengthResponse(getSuccessHtml())
            } catch (e: Exception) {
                e.printStackTrace()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error parsing body")
            }
        }

        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    // ──────────────────────────────────────────────
    //  端口探测
    // ──────────────────────────────────────────────

    companion object {
        /**
         * 从 [startPort] 开始递增探测可用端口，避免与已有服务冲突。
         * 最多尝试 10 个端口。
         */
        private fun findAvailablePort(startPort: Int = 9528): Int {
            for (port in startPort until startPort + 10) {
                try {
                    val ss = ServerSocket(port)
                    ss.close()
                    return port
                } catch (_: Exception) {
                    // 端口被占用，尝试下一个
                }
            }
            // 所有端口均被占用，让 OS 分配随机端口
            return 0
        }
    }

    // ──────────────────────────────────────────────
    //  多服务器地址解析
    // ──────────────────────────────────────────────

    /**
     * 将用户输入的文本分割为多个候选服务器地址，逐个解析并验证。
     *
     * 分隔符：换行、逗号、分号、空格
     * 每个条目优先尝试 Base64 解码，失败则直接作为 URL 处理。
     */
    private fun parseMultipleUrls(input: String): List<String> {
        val parts = input.split(Regex("[\\n,;]+"))
            .flatMap { it.split(Regex("\\s+")) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (parts.isEmpty()) return emptyList()

        val valid = mutableListOf<String>()
        for (item in parts) {
            val url = resolveSingleUrl(item)
            if (url != null && url !in valid) {
                valid.add(url)
            }
        }
        return valid
    }

    /**
     * 解析单条输入为有效的服务器 URL。
     *
     * 流程：
     * 1. 尝试 Base64 解码
     * 2. 解码成功则验证 URL
     * 3. 解码失败则直接验证原始输入
     */
    private fun resolveSingleUrl(input: String): String? {
        val candidates = buildList {
            val decoded = try {
                val bytes = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
                String(bytes, Charsets.UTF_8).trim()
            } catch (_: Exception) {
                null
            }
            if (decoded != null) add(decoded)
            add(input)
        }

        for (candidate in candidates) {
            val normalized = normalizeUrl(candidate)
            if (isValidHttpUrl(normalized)) {
                return normalized
            }
        }
        return null
    }

    /** 补全 URL 协议前缀。 */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)) {
            return "http://$trimmed"
        }
        return trimmed
    }

    /** 使用 java.net.URL 严格校验是否为合法的 HTTP(S) URL。 */
    private fun isValidHttpUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            val protocol = parsed.protocol.lowercase()
            (protocol == "http" || protocol == "https") &&
                parsed.host.isNotEmpty() &&
                (parsed.host.indexOf('.') > 0 || parsed.host == "localhost" || parsed.host.contains(':'))
        } catch (_: Exception) {
            false
        }
    }

    // ──────────────────────────────────────────────
    //  HTML 页面
    // ──────────────────────────────────────────────

    private fun getErrorHtml(message: String): String {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>配置错误</title>
                <style>
                    body { font-family: -apple-system, sans-serif; background: #f4f4f5; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
                    .card { background: white; padding: 2rem; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); width: 90%; max-width: 400px; text-align: center; }
                    h2 { color: #dc3545; margin-top: 0; }
                    p { color: #666; font-size: 14px; white-space: pre-wrap; }
                    a { display: inline-block; margin-top: 16px; padding: 10px 24px; background: #007bff; color: white; text-decoration: none; border-radius: 6px; font-size: 14px; }
                    a:hover { background: #0056b3; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>配置无效</h2>
                    <p>${message}</p>
                    <a href="/">返回重试</a>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getHtmlForm(): String {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>服务器配置</title>
                <style>
                    body { font-family: -apple-system, sans-serif; background: #f4f4f5; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
                    .card { padding: 2rem; width: 90%; max-width: 420px; text-align: center; }
                    h2 { margin-top: 0; color: #333; }
                    textarea { width: 100%; padding: 12px; margin: 12px 0; border: 1px solid #ccc; border-radius: 6px; box-sizing: border-box; font-size: 15px; resize: vertical; min-height: 100px; }
                    input, textarea { width: 100%; padding: 12px; margin: 12px 0; border: 1px solid #ccc; border-radius: 6px; box-sizing: border-box; font-size: 15px; }
                    .hint { text-align: left; color: #888; font-size: 12px; margin: -8px 0 12px; line-height: 1.5; }
                    button { width: 100%; padding: 12px; background: #007bff; color: white; border: none; border-radius: 6px; font-size: 16px; cursor: pointer; transition: 0.3s; }
                    button:hover { background: #0056b3; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>欢迎使用</h2>
                    <p style="color:#666; font-size:14px;">请输入授权与位置信息</p>
                    <form action="/save" method="post">
                        <textarea name="server_url" placeholder="请在此粘贴服务商提供的授权码&#10;支持多个授权码，当主服务器宕机时，会自动切换至备用服务器" required></textarea>
                        <div class="hint">第一行为主服务器，每行填写一个。</div>
                        <input type="text" name="location" placeholder="安装位置（例如：13号楼一单元302），方便服务器识别设备。" required>
                        <button type="submit">保存配置</button>
                    </form>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun getSuccessHtml(): String {
        return """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>配置成功</title>
                <style>
                    body { font-family: -apple-system, sans-serif; background: #f4f4f5; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
                    .card { background: white; padding: 2rem; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); width: 90%; max-width: 400px; text-align: center; }
                    h2 { color: #28a745; margin-top: 0; }
                    p { color: #666; }
                </style>
            </head>
            <body>
                <div class="card">
                    <h2>✅ 配置保存成功</h2>
                    <p>您的电视端已收到配置并正在重新连接服务器，您可以关闭此页面了。</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }
}