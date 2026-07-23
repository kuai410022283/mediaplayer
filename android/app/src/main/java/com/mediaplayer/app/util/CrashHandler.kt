/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var context: Context? = null

    companion object {
        val instance: CrashHandler by lazy(mode = LazyThreadSafetyMode.SYNCHRONIZED) {
            CrashHandler()
        }
    }

    fun init(context: Context) {
        this.context = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(t: Thread, e: Throwable) {
        if (!handleException(e) && defaultHandler != null) {
            defaultHandler?.uncaughtException(t, e)
        } else {
            try {
                Thread.sleep(2000)
            } catch (ex: InterruptedException) {
                Log.e("CrashHandler", "Error : ", ex)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        }
    }

    private fun handleException(ex: Throwable?): Boolean {
        if (ex == null) return false
        com.mediaplayer.app.util.RemoteLogger.e("CrashHandler", "Uncaught Exception", ex)
        saveCrashInfoToFile(ex)
        return true
    }

    private fun saveCrashInfoToFile(ex: Throwable) {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        ex.printStackTrace(printWriter)
        var cause = ex.cause
        while (cause != null) {
            cause.printStackTrace(printWriter)
            cause = cause.cause
        }
        printWriter.close()
        val result = writer.toString()

        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            val fileName = "crash-$timestamp.log"
            // 写入 remote_logs/ 目录，由 RemoteLogger 上传任务统一上传
            val ctx = context ?: return
            val dir = File(ctx.filesDir, "remote_logs")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val fos = FileOutputStream(File(dir, fileName))
            fos.write(result.toByteArray())
            fos.close()
        } catch (e: Exception) {
            Log.e("CrashHandler", "An error occurred while writing file...", e)
        }
    }
}
