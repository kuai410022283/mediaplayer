/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Process
import android.util.Rational
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.mediaplayer.app.Prefs
import com.mediaplayer.app.R

interface PipActionCallback {
    fun onPipPlay()
    fun onPipPause()
    fun onPipNext()
    fun onPipPrev()
}

/**
 * 专职的画中画（PiP）控制器，封装 PiP 生命周期、参数更新及媒体播控。
 */
class PipController(private val activity: AppCompatActivity, private val callback: PipActionCallback) {
    private val TAG = "PipController"

    companion object {
        private const val EXTRA_CONTROL_TYPE = "control_type"
        private const val CONTROL_PLAY = 1
        private const val CONTROL_PAUSE = 2
        private const val CONTROL_NEXT = 3
        private const val CONTROL_PREV = 4
    }

    private val actionMediaControl = "com.mediaplayer.app.PIP_MEDIA_CONTROL_${activity.javaClass.simpleName}"

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null || intent.action != actionMediaControl) return
            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_PLAY -> callback.onPipPlay()
                CONTROL_PAUSE -> callback.onPipPause()
                CONTROL_NEXT -> callback.onPipNext()
                CONTROL_PREV -> callback.onPipPrev()
            }
        }
    }

    private var isReceiverRegistered = false

    init {
        // 动态注册广播接收器
        val filter = IntentFilter(actionMediaControl)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(pipReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(pipReceiver, filter)
        }
        isReceiverRegistered = true
    }

    fun release() {
        if (isReceiverRegistered) {
            try {
                activity.unregisterReceiver(pipReceiver)
            } catch (e: Exception) {}
            isReceiverRegistered = false
        }
    }

    /**
     * 判断当前系统是否真正授予了画中画权限
     */
    private fun hasSystemPipPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appOps = activity.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            // 兼容性替代 unsafeCheckOpNoThrow
            val mode = try {
                val method = AppOpsManager::class.java.getMethod("checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                method.invoke(appOps, AppOpsManager.OPSTR_PICTURE_IN_PICTURE.hashCode(), Process.myUid(), activity.packageName) as Int
            } catch (e: Exception) {
                // 稳妥回退方案
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_PICTURE_IN_PICTURE, Process.myUid(), activity.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
        return false
    }

    /**
     * 判断是否开启且支持画中画
     */
    fun isPipEnabledAndSupported(): Boolean {
        val prefs = activity.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        val isPipEnabled = prefs.getBoolean(Prefs.KEY_ENABLE_PIP, false)
        return isPipEnabled && !DeviceUtils.isTV(activity) && hasSystemPipPermission()
    }

    private fun createRemoteAction(iconId: Int, title: String, controlType: Int): RemoteAction? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val intent = Intent(actionMediaControl).apply {
            setPackage(activity.packageName)
            putExtra(EXTRA_CONTROL_TYPE, controlType)
        }
        val pendingIntent = PendingIntent.getBroadcast(activity, controlType, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        
        // 将 VectorDrawable 转换为 Bitmap，防止部分系统 (如小米/三星) 的 SystemUI 跨进程渲染 VectorDrawable 失败导致图标被丢弃
        val drawable = ContextCompat.getDrawable(activity, iconId) ?: return null
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 144
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 144
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        
        val icon = android.graphics.drawable.Icon.createWithBitmap(bitmap)
        return RemoteAction(icon, title, title, pendingIntent)
    }

    /**
     * 动态更新画中画参数。包括：无缝切换支持、以及播放控制栏按钮。
     * @param isPlaying 是否正在播放视频
     * @param showControls 是否在画中画中显示切台/暂停按钮
     */
    fun updatePipParams(isPlaying: Boolean, showControls: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isPipEnabledAndSupported()) {
                // 若权限或开关关闭，向系统注销自动进入能力
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    try {
                        val params = PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
                        activity.setPictureInPictureParams(params)
                    } catch (e: Exception) {}
                }
                return
            }

            try {
                val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(isPlaying)
                }

                if (showControls) {
                    val actions = mutableListOf<RemoteAction>()
                    
                    // 上一个频道
                    createRemoteAction(R.drawable.ic_vod_rewind, "上一个", CONTROL_PREV)?.let { actions.add(it) }
                    
                    // 播放/暂停
                    if (isPlaying) {
                        createRemoteAction(R.drawable.ic_vod_pause, "暂停", CONTROL_PAUSE)?.let { actions.add(it) }
                    } else {
                        createRemoteAction(R.drawable.ic_vod_play, "播放", CONTROL_PLAY)?.let { actions.add(it) }
                    }
                    
                    // 下一个频道
                    createRemoteAction(R.drawable.ic_vod_forward, "下一个", CONTROL_NEXT)?.let { actions.add(it) }

                    builder.setActions(actions)
                }

                activity.setPictureInPictureParams(builder.build())
            } catch (e: Exception) {
                RemoteLogger.e(TAG, "更新画中画参数失败", e)
            }
        }
    }

    /**
     * 拦截用户按 Home 键离开应用。若正在播放，才允许进入小窗。
     */
    fun handleUserLeaveHint(isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipEnabledAndSupported() && isPlaying) {
            // Android 12+ (S): 系统会自动根据 setAutoEnterEnabled(true) 丝滑进入画中画
            // 此时再手动调用 enterPictureInPictureMode 会破坏系统的平滑过渡动画，所以只在 Android 8-11 手动触发
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                try {
                    val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
                    val actions = mutableListOf<RemoteAction>()
                    createRemoteAction(R.drawable.ic_vod_rewind, "上一个", CONTROL_PREV)?.let { actions.add(it) }
                    if (isPlaying) {
                        createRemoteAction(R.drawable.ic_vod_pause, "暂停", CONTROL_PAUSE)?.let { actions.add(it) }
                    } else {
                        createRemoteAction(R.drawable.ic_vod_play, "播放", CONTROL_PLAY)?.let { actions.add(it) }
                    }
                    createRemoteAction(R.drawable.ic_vod_forward, "下一个", CONTROL_NEXT)?.let { actions.add(it) }
                    builder.setActions(actions)

                    activity.enterPictureInPictureMode(builder.build())
                } catch (e: Exception) {
                    RemoteLogger.e(TAG, "手动触发进入画中画失败", e)
                }
            }
        }
    }

    fun shouldKeepPlayerAliveOnPause(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (activity.isInPictureInPictureMode) {
                return true
            }
        }
        return false
    }

    fun handlePictureInPictureModeChanged(isInPictureInPictureMode: Boolean, viewsToHide: List<View?>) {
        if (isInPictureInPictureMode) {
            viewsToHide.forEach { it?.visibility = View.GONE }
        }
    }
}
