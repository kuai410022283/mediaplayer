/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.mediaplayer.app.data.api.ApiClient
import com.mediaplayer.app.data.model.AppUpdateConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.content.ContextCompat

object UpdateManager {

    fun checkUpdate(context: Context, scope: CoroutineScope, showUpToDateToast: Boolean = false) {
        scope.launch(Dispatchers.IO) {
            try {
                val arch = Build.SUPPORTED_ABIS.firstOrNull() ?: "all"
                val response = ApiClient.getService().checkUpdate(arch)
                if (response.isSuccessful) {
                    val updateConfig = response.body()?.data
                    val currentVersionCode = try {
                        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                        PackageInfoCompat.getLongVersionCode(pInfo)
                    } catch (e: Exception) {
                        1L
                    }
                    if (updateConfig != null && updateConfig.version_code > currentVersionCode) {
                        withContext(Dispatchers.Main) {
                            showUpdateDialog(context, updateConfig)
                        }
                    } else if (showUpToDateToast) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "当前已是最新版本", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (showUpToDateToast) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "检查更新失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showUpdateDialog(context: Context, config: AppUpdateConfig) {
        val builder = AlertDialog.Builder(context)
            .setTitle("发现新版本: ${config.version_name ?: ""}")
            .setMessage(config.update_log ?: "修复了一些已知问题")
            .setCancelable(!config.force_update)
            .setPositiveButton("立即更新") { _, _ ->
                if (!config.download_url.isNullOrEmpty()) {
                    downloadAndInstall(context, config.download_url)
                } else {
                    Toast.makeText(context, "下载地址无效", Toast.LENGTH_SHORT).show()
                }
            }

        if (!config.force_update) {
            builder.setNegativeButton("稍后") { dialog, _ ->
                dialog.dismiss()
            }
        }

        builder.show()
    }

    private fun downloadAndInstall(context: Context, url: String) {
        var finalUrl = url
        val lowerUrl = finalUrl.lowercase(java.util.Locale.getDefault())
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            finalUrl = "http://$finalUrl"
        }
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = Uri.parse(finalUrl)
        val request = DownloadManager.Request(uri)
        
        val fileName = "update_${System.currentTimeMillis()}.apk"
        request.setTitle("正在下载更新")
        request.setDescription("请稍候...")
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        
        val downloadId = downloadManager.enqueue(request)
        Toast.makeText(context, "开始下载更新，可在通知栏查看进度", Toast.LENGTH_SHORT).show()

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctxt: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(context, fileName)
                    context.unregisterReceiver(this)
                }
            }
        }
        
        ContextCompat.registerReceiver(
            context,
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun installApk(context: Context, fileName: String) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
            if (!file.exists()) return

            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
