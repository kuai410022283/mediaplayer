/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

object NetworkUtils {
    /**
     * 获取本地 IPv4 地址，优先级：
     * 1. 有线网卡（eth/en/ether）
     * 2. 无线网卡（wlan/wl）
     * 3. 其他（tun、ppp 等）
     */
    fun getLocalIpAddress(): String? {
        try {
            val allInterfaces = mutableListOf<NetworkInterface>()
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                allInterfaces.add(en.nextElement())
            }

            // 按优先级排序：有线 > 无线 > 其他
            allInterfaces.sortBy { intf ->
                val name = intf.name.lowercase()
                when {
                    name.startsWith("eth") || name.startsWith("en") || name.contains("ether") -> 0
                    name.startsWith("wlan") || name.startsWith("wl") -> 1
                    else -> 2
                }
            }

            for (intf in allInterfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (ex: SocketException) {
            ex.printStackTrace()
        }
        return null
    }
}
