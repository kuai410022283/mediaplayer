# Proguard rules for MediaPlayer

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep interface com.mediaplayer.app.data.api.ApiService { *; }
-dontwarn retrofit2.**

# Gson - 保留所有数据模型
-keep class com.google.gson.** { *; }
-keep class com.mediaplayer.app.data.model.** { *; }
-keepclassmembers class com.mediaplayer.app.data.model.** { *; }
-keep class com.mediaplayer.app.data.api.ClientAuthManager$Companion { *; }
-keep class com.mediaplayer.app.data.ChannelMemory { *; }
-keepclassmembers class com.mediaplayer.app.data.ChannelMemory { *; }

# Gson TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ExoPlayer / Media3
-keep class androidx.media3.** { *; }

# Coil
-keep class coil.** { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Keep Application class
-keep class com.mediaplayer.app.MediaPlayerApp { *; }

# Keep Activities & Services
-keep class com.mediaplayer.app.ui.** { *; }
-keep class com.mediaplayer.app.service.** { *; }

# Keep API response wrapper
-keep class com.mediaplayer.app.data.model.ApiResponse { *; }
-keep class com.mediaplayer.app.data.model.PageResponse { *; }

# Add robust Kotlin Coroutines rules
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep interface kotlin.coroutines.Continuation { *; }

# Keep API Managers
-keep class com.mediaplayer.app.data.api.ClientAuthManager { *; }
-keep class com.mediaplayer.app.data.api.ServerAuthFlowManager { *; }
-keep class com.mediaplayer.app.data.api.ApiClient { *; }

# Gson specific attributes
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# VLC Player
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }

# IJKPlayer
-keep class tv.danmaku.ijk.media.player.** { *; }
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer { *; }
-keep class tv.danmaku.ijk.media.player.ffmpeg.FFmpegApi { *; }
