/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mediaplayer.app.Prefs
import com.mediaplayer.app.ui.home.MainActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean(Prefs.KEY_AUTO_START, false) // 默认关闭
            
            if (autoStart) {
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
            }
        }
    }
}
