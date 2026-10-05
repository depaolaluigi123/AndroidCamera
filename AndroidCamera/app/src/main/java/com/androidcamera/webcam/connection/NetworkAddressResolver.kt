package com.androidcamera.webcam.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Resolves the host address shown to the user depending on connection mode.
 */
class NetworkAddressResolver(private val context: Context) {

    fun resolveHost(preferLocalhost: Boolean): String {
        if (preferLocalhost) {
            // USB mode: prefer USB tether interface, then localhost (adb reverse).
            findUsbIpv4Address()?.let { return it }
            return "127.0.0.1"
        }
        return findWifiIpv4Address() ?: findIpv4Address() ?: "127.0.0.1"
    }

    fun findUsbIpv4Address(): String? {
        findFromInterfaces(preferredNames = listOf("rndis", "usb", "eth"))?.let { return it }
        return findFromConnectivityManager(usbOnly = true)
    }

    fun findWifiIpv4Address(): String? {
        findFromInterfaces(preferredNames = listOf("wlan", "wifi"))?.let { return it }
        return findFromConnectivityManager(usbOnly = false)
    }

    fun findIpv4Address(): String? {
        findWifiIpv4Address()?.let { return it }
        findUsbIpv4Address()?.let { return it }
        return findFromNetworkInterfaces()
    }

    private fun findFromConnectivityManager(usbOnly: Boolean): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val hasUsb = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_USB)
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val hasEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

            val matches = if (usbOnly) {
                hasUsb || hasEthernet
            } else {
                hasWifi || hasEthernet || hasUsb ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            if (!matches) continue

            val link: LinkProperties = cm.getLinkProperties(network) ?: continue
            for (address in link.linkAddresses) {
                val inet = address.address
                if (inet is Inet4Address && !inet.isLoopbackAddress) {
                    return inet.hostAddress
                }
            }
        }
        return null
    }

    private fun findFromInterfaces(preferredNames: List<String>): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.filter { iface ->
                    iface.isUp && !iface.isLoopback &&
                        preferredNames.any { iface.name.contains(it, ignoreCase = true) }
                }
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { address ->
                    address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        address.hostAddress?.startsWith("169.254.") != true
                }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun findFromNetworkInterfaces(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { address ->
                    address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        address.hostAddress?.startsWith("169.254.") != true
                }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
