/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

object QRCodeHelper {
    // 缓存上一次生成的二维码，避免相同内容重复生成
    private var cachedText: String? = null
    private var cachedBitmap: Bitmap? = null
    
    fun generateQRCode(
        text: String, 
        size: Int = 512, 
        colorBg: Int = Color.TRANSPARENT, 
        colorFg: Int = Color.WHITE
    ): Bitmap? {
        if (text.isEmpty()) return null
        
        // 如果内容相同，直接返回缓存
        if (text == cachedText && cachedBitmap != null && !cachedBitmap!!.isRecycled) {
            return cachedBitmap
        }
        
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
            hints[EncodeHintType.MARGIN] = 1 // 缩小默认白边
            
            val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix[x, y]) colorFg else colorBg
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            
            // 更新缓存
            cachedBitmap?.recycle()
            cachedBitmap = bitmap
            cachedText = text
            
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
