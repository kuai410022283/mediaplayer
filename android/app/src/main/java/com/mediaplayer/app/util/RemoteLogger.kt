/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.content.Context
import android.util.Log
import com.mediaplayer.app.Prefs
import com.mediaplayer.app.data.api.ApiClient
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

object RemoteLogger {
    private const val TAG = "RemoteLogger"
    private const val MAX_FILE_SIZE = 1 * 1024 * 1024 // 1MB 切片
    private const val MAX_LOG_FILES = 30               // 最大文件数
    private const val MAX_LOG_DIR_SIZE = 20 * 1024 * 1024 // 20MB 总上限
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isRunning = AtomicBoolean(false)
    private var uploadJob: Job? = null
    private var isEnabled = false

    private lateinit var logDir: File
    private var currentLogFile: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        logDir = File(context.filesDir, "remote_logs")
        if (!logDir.exists()) {
            logDir.mkdirs()
        }
        
        // 启动时清理过期日志
        enforceRotation()

        val prefs = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        isEnabled = prefs.getBoolean(Prefs.KEY_ENABLE_LOG, false)

        if (isEnabled) {
            startUploadTask()
            val deviceType = DeviceUtils.getDeviceType(context).name
            val deviceModel = android.os.Build.MODEL
            val osVersion = android.os.Build.VERSION.RELEASE
            i("DeviceInfo", "Model: $deviceModel, Type: $deviceType, OS: Android $osVersion")
        }
    }

    fun updateConfig(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            // 立即取消上传任务，无需等待下一个 30s 循环检查点
            uploadJob?.cancel()
            uploadJob = null
            isRunning.set(false)
        } else if (!isRunning.get()) {
            startUploadTask()
        }
    }

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return

        scope.launch {
            try {
                val time = dateFormat.format(Date())
                val errorMsg = throwable?.stackTraceToString()?.let { "\n$it" } ?: ""
                val logLine = "[$time] [$level] [$tag] $message$errorMsg\n"

                val file = getCurrentLogFile()
                file.appendText(logLine)
                
                if (file.length() > MAX_FILE_SIZE) {
                    currentLogFile = null 
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入日志失败: ${e.message}")
            }
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        log("E", tag, message, throwable)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        log("I", tag, message)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        log("D", tag, message)
    }

    private fun getCurrentLogFile(): File {
        var file = currentLogFile
        if (file == null || !file.exists()) {
            val ts = System.currentTimeMillis()
            file = File(logDir, "log_$ts.txt")
            currentLogFile = file
            enforceRotation()
        }
        return file
    }

    /** 日志轮转：限制文件数量 <= 30，总大小 <= 20MB */
    private fun enforceRotation() {
        try {
            val files = logDir.listFiles { _, name ->
                name.endsWith(".txt")
            }?.toMutableList() ?: return
            if (files.size <= 1) return

            files.sortBy { it.name }

            // 限制文件数量
            while (files.size > MAX_LOG_FILES) {
                val removed = files.removeAt(0)
                removed.delete()
            }

            // 限制总大小
            var totalSize = files.sumOf { it.length() }
            while (totalSize > MAX_LOG_DIR_SIZE && files.size > 1) {
                val oldest = files.removeAt(0)
                totalSize -= oldest.length()
                oldest.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "日志轮转失败: ${e.message}")
        }
    }

    private fun startUploadTask() {
        if (isRunning.compareAndSet(false, true)) {
            uploadJob = scope.launch {
                while (isActive && isEnabled) {
                    uploadPendingLogs()
                    delay(30_000)
                }
                isRunning.set(false)
            }
        }
    }

    private suspend fun uploadPendingLogs() {
        try {
            val current = currentLogFile
            if (current != null && current.length() > 0) {
                currentLogFile = null
            }

            val files = logDir.listFiles { _, name ->
                (name.startsWith("log_") || name.startsWith("crash_")) && name.endsWith(".txt")
            }
            if (files.isNullOrEmpty()) return

            val sortedFiles = files.sortedBy { it.name }
            for (file in sortedFiles) {
                if (file == currentLogFile) {
                    continue
                }

                val reqFile = file.asRequestBody("text/plain".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("log_file", file.name, reqFile)

                try {
                    val response = ApiClient.getService().clientUploadLog(body)
                    if (response.isSuccessful && response.body()?.code == 0) {
                        file.delete()
                    } else {
                        break 
                    }
                } catch (e: Exception) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传日志异常", e)
        }
    }
}
