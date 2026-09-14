package com.carcast.service

import com.carcast.service.StreamService
import org.json.JSONObject

/**
 * Whether the phone's hotspot is up, for display only — the user switches it themselves (the app cannot:
 * see the shell server's Hotspot class and docs/verification-log.md open question 0).
 *
 * Two sources, because the one that knows most is not always there. The shell server can ask the framework
 * directly (`getWifiApState`), but the whole point of the home screen switch is that it is useful when
 * nothing of ours is running — and then the only thing left is what any process can see: an AP-shaped
 * interface carrying an IPv4 address. That is weaker, so it is the fallback, never the override.
 */
object HotspotState {

    /** Names an AP is brought up on: swlan0 is Samsung's, ap0/softap0 Qualcomm/AOSP, wlan1 a second radio. */
    private val AP_INTERFACES = setOf("swlan0", "softap0", "ap0", "wlan1")

    /** true/false when something could tell us, null when nothing could — which is not the same as "off". */
    fun on(): Boolean? = fromServer() ?: fromInterfaces()

    /** The server's answer, if a server is answering and the phone would say. */
    private fun fromServer(): Boolean? = runCatching {
        val h = JSONObject(StreamService.shellStatus ?: return null).optJSONObject("hotspot") ?: return null
        if (h.optBoolean("known", false)) h.optBoolean("on", false) else null
    }.getOrNull()

    /**
     * An AP interface with an address means the AP is up. The absence of one is weaker evidence — a phone
     * could name it something we do not know — but on the devices this runs on it is the normal signal, and
     * "no AP interface" is a far better answer for the driver than a shrug.
     */
    private fun fromInterfaces(): Boolean? = runCatching {
        val ifaces = SelfTest.interfaces()
        if (ifaces.isEmpty()) null else ifaces.any { it.name.lowercase() in AP_INTERFACES }
    }.getOrNull()
}
