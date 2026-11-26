package com.anistream.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** The home Wi-Fi (not Tailscale or mobile data): TVs reach the phone through it. */
object LanNetwork {
    @Suppress("DEPRECATION")
    fun wifi(context: Context): Pair<Network, String>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val address = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
            if (address != null) return network to address
        }
        return null
    }

    fun wifiAddress(context: Context): String? = wifi(context)?.second
}
