/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.content.Context
import android.os.Build
import android.view.Display
import android.view.WindowManager

object HdrCapabilitiesHelper {

    data class HdrInfo(
        val isDolbyVisionSupported: Boolean,
        val isHdr10Supported: Boolean,
        val isHdr10PlusSupported: Boolean,
        val isHlgSupported: Boolean
    )

    fun getCapabilities(context: Context): HdrInfo {
        var dv = false
        var hdr10 = false
        var hdr10Plus = false
        var hlg = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val display = windowManager.defaultDisplay
                val hdrCapabilities = display.hdrCapabilities

                if (hdrCapabilities != null) {
                    val supportedTypes = hdrCapabilities.supportedHdrTypes
                    for (type in supportedTypes) {
                        when (type) {
                            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> dv = true
                            Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdr10 = true
                            Display.HdrCapabilities.HDR_TYPE_HLG -> hlg = true
                            4 -> hdr10Plus = true // Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
                        }
                    }
                }
            } catch (e: Exception) {
                RemoteLogger.e("HdrCapabilitiesHelper", "Failed to get HDR capabilities", e)
            }
        }

        return HdrInfo(dv, hdr10, hdr10Plus, hlg)
    }

    fun printHdrInfo(context: Context) {
        val info = getCapabilities(context)
        RemoteLogger.i("HdrCapabilitiesHelper", "HDR Panel Support -> DolbyVision: ${info.isDolbyVisionSupported}, HDR10: ${info.isHdr10Supported}, HDR10+: ${info.isHdr10PlusSupported}, HLG: ${info.isHlgSupported}")
    }
}
