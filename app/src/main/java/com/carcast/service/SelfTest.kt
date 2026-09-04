package com.carcast.service

import com.carcast.Config
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * In-app network self-test so that "the laptop cannot connect" can be split into
 * "the server is down", "the tun address is not local" or "hotspot -> tun delivery is blocked"
 * without adb. Results go to StreamService.log and show on the main screen.
 */
object SelfTest {
    data class Iface(val name: String, val address: String)

    /** Every non-loopback IPv4 address on the phone: tun0 (100.99.9.9), swlan0/ap0 (hotspot), wlan0 ... */
    fun interfaces(): List<Iface> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { Iface(nif.name, it.hostAddress ?: "?") } }
            .sortedBy { it.name }
    } catch (_: Exception) { emptyList() }

    fun run(log: (String) -> Unit) {
        Thread {
            val targets = listOf("127.0.0.1", Config.TUN_ADDRESS) + interfaces().map { it.address }.distinct()
            for (host in targets.distinct()) {
                val r = probe(host)
                log("self-test $host:${Config.HTTP_PORT} → $r")
            }
            log("self-test 인터넷(gstatic 204) → ${probeUrl("http://connectivitycheck.gstatic.com/generate_204")}")
        }.apply { isDaemon = true }.start()
    }

    private fun probe(host: String): String = probeUrl("http://$host:${Config.HTTP_PORT}/api/status")

    private fun probeUrl(url: String): String = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val code = c.responseCode
        c.disconnect()
        "HTTP $code"
    } catch (e: Exception) {
        "실패 (${e.javaClass.simpleName}: ${e.message})"
    }
}
