package com.anistream.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.TimeUnit

/** How to drive a DLNA TV: where to send AVTransport commands. */
data class DlnaInfo(val controlUrl: String, val serviceType: String)

/** A TV found on the Wi-Fi that isn't a Chromecast: an LG webOS TV and/or a DLNA TV. */
data class LanTv(
    val name: String,
    val ip: String,
    val isLg: Boolean,
    val dlna: DlnaInfo?,
)

/** Finds LG webOS and DLNA TVs on the home Wi-Fi with SSDP (the "who's out there?" broadcast UPnP uses). */
object LanDiscovery {
    private const val TAG = "AniLan"
    private const val LG_ST = "urn:lge-com:service:webos-second-screen:1"
    private const val RENDERER_ST = "urn:schemas-upnp-org:device:MediaRenderer:1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun scan(context: Context, listenMs: Long = 3_000): List<LanTv> = withContext(Dispatchers.IO) {
        val (network, wifiIp) = LanNetwork.wifi(context) ?: return@withContext emptyList()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("ani-cast")?.apply { setReferenceCounted(false); acquire() }
        // ip -> (description urls, answered as an LG TV)
        val found = mutableMapOf<String, Pair<MutableSet<String>, Boolean>>()
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                // Send from the Wi-Fi address so the search goes out on the home network, not over a VPN.
                socket.bind(InetSocketAddress(InetAddress.getByName(wifiIp), 0))
                // Not allowed while a VPN such as Tailscale is on; the address above already picks Wi-Fi.
                runCatching { network.bindSocket(socket) }
                socket.soTimeout = 300
                val group = InetAddress.getByName("239.255.255.250")
                fun search(st: String) {
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = msg.toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
                }
                val deadline = System.currentTimeMillis() + listenMs
                var sends = 0
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    // Ask a few times: UDP gets lost, and TVs waking from standby answer late.
                    if (sends < 3 && System.currentTimeMillis() > deadline - listenMs + sends * 800) {
                        search(LG_ST); search(RENDERER_ST); sends++
                    }
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    val text = String(packet.data, 0, packet.length)
                    val ip = packet.address.hostAddress ?: continue
                    val header = { name: String ->
                        Regex("(?im)^$name:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1)
                    }
                    val st = header("ST").orEmpty()
                    val location = header("LOCATION")
                    val entry = found.getOrPut(ip) { mutableSetOf<String>() to false }
                    if (location != null && st == RENDERER_ST) entry.first += location
                    if (st == LG_ST) {
                        found[ip] = entry.first.also { set -> location?.let { set += "lg:$it" } } to true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSDP scan failed: ${e.message}")
        } finally {
            lock?.release()
        }

        coroutineScope {
            found.map { (ip, value) ->
                async {
                    val (locations, isLg) = value
                    var name: String? = null
                    var dlna: DlnaInfo? = null
                    for (loc in locations) {
                        val url = loc.removePrefix("lg:")
                        val xml = runCatching { fetch(url) }.getOrNull() ?: continue
                        if (name == null) name = tag(xml, "friendlyName")
                        if (!loc.startsWith("lg:") && dlna == null) dlna = avTransport(xml, url)
                    }
                    if (!isLg && dlna == null) null
                    else LanTv(name = name?.let(::unescape) ?: if (isLg) "LG TV" else "TV ($ip)", ip = ip, isLg = isLg, dlna = dlna)
                }
            }.awaitAll().filterNotNull().sortedBy { it.name.lowercase() }
        }
    }

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name[^>]*>([^<]*)</(?:\\w+:)?$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim()

    /** The AVTransport service's control address from a UPnP device description. */
    private fun avTransport(xml: String, location: String): DlnaInfo? {
        val base = tag(xml, "URLBase")?.takeIf { it.isNotBlank() } ?: location
        val service = Regex("<service>(.*?)</service>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(xml).map { it.groupValues[1] }
            .firstOrNull { it.contains("AVTransport", ignoreCase = true) } ?: return null
        val type = tag(service, "serviceType") ?: return null
        val control = tag(service, "controlURL") ?: return null
        val url = runCatching { URI(base).resolve(control).toString() }.getOrNull() ?: return null
        return DlnaInfo(url, type)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
}
