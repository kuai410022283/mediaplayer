/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.mediaplayer.app.data.api.ApiClient

class MediaPlayerApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        instance = this

        com.mediaplayer.app.util.CrashHandler.instance.init(this)
        com.mediaplayer.app.util.RemoteLogger.init(this)

        try {
            val clazz = Class.forName("androidx.media3.exoplayer.rtsp.RtspMessageLogger")
            val delegateClass = Class.forName("androidx.media3.exoplayer.rtsp.RtspMessageLogger\$LoggerDelegate")
            
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                delegateClass.classLoader,
                arrayOf(delegateClass)
            ) { _, method, args ->
                val tag = args[0] as String
                val msg = args[1] as String
                when (method.name) {
                    "d" -> com.mediaplayer.app.util.RemoteLogger.d(tag, msg)
                    "w" -> com.mediaplayer.app.util.RemoteLogger.e(tag, msg) // using e to save w
                    "e" -> {
                        val t = if (args.size > 2) args[2] as? Throwable else null
                        com.mediaplayer.app.util.RemoteLogger.e(tag, msg, t)
                    }
                }
                null
            }
            
            clazz.getMethod("setDelegate", delegateClass).invoke(null, proxy)
        } catch (e: Exception) {
            // Official Media3 without RtspMessageLogger, skip
            android.util.Log.i("MediaPlayerApp", "RTSP logger injection skipped.")
        }

        // Initialize API with saved server URL or default
        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        val serverUrl = prefs.getString(Prefs.KEY_SERVER_URL, Prefs.DEFAULT_SERVER_URL) ?: Prefs.DEFAULT_SERVER_URL
        ApiClient.init(serverUrl)
        
        // Restore access token
        ApiClient.accessToken = prefs.getString(Prefs.KEY_ACCESS_TOKEN, null)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .okHttpClient {
                ApiClient.getOkHttpClient()
            }
            .build()
    }

    companion object {
        lateinit var instance: MediaPlayerApp
            private set
    }
}
