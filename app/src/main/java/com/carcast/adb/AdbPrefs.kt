package com.carcast.adb

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/**
 * What the app remembers about adb. The *wireless debugging* port is NOT remembered — adbd changes it on
 * every toggle and reboot — but the TCP-mode port is, because we picked it ourselves and adbd keeps it
 * (see [tcpPort] and docs/hotspot-only.md).
 */
class AdbPrefs(context: Context) {
    private val p = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    /** Set after a successful pairing; only affects what the UI says (adbd is the source of truth). */
    var paired: Boolean
        get() = p.getBoolean("paired", false)
        set(v) = p.edit().putBoolean("paired", v).apply()

    /**
     * A user-typed connect port (from the Wireless debugging screen). adbd picks a new one on every toggle and
     * reboot, so this is only a hint that is tried alongside mDNS and dropped once it has failed a few times.
     */
    var manualConnectPort: Int
        get() = p.getInt("connectPort", 0)
        set(v) = p.edit().putInt("connectPort", v).apply()

    /**
     * The port adbd was switched to with `adb tcpip` (0 = never). Unlike the wireless-debugging port this
     * one is ours, survives Wi-Fi going away and is reachable while the phone is a hotspot, so the app
     * tries it first and only falls back to wireless debugging (which needs Wi-Fi) when it fails.
     */
    var tcpPort: Int
        get() = p.getInt("tcpPort", 0)
        set(v) = p.edit().putInt("tcpPort", v).apply()

    /**
     * Whether TCP mode is wanted. On by default since 2026-09-14: the switch, the recovery without Wi-Fi and
     * the re-switch after a reboot have all been seen working on the phone (docs/verification-log.md §3.8), and
     * a first-time user who does not know to opt in loses the server the moment they leave Wi-Fi. It was off
     * before because one early attempt left adbd not serving wireless debugging (§3.7); [tcpModeFailures]
     * still stops the app from breaking that repeatedly on a phone that refuses, and the app's button turns
     * it off for good.
     */
    var tcpModeOptIn: Boolean
        get() = p.getBoolean("tcpModeOptIn", true)
        set(v) = p.edit().putBoolean("tcpModeOptIn", v).apply()

    /** Consecutive failures of [manualConnectPort]; it is dropped after a few so a stale port cannot trap the app. */
    var manualPortFailures: Int
        get() = p.getInt("manualPortFailures", 0)
        set(v) = p.edit().putInt("manualPortFailures", v).apply()

    /**
     * How many times switching to TCP mode has failed. Switching restarts adbd, so a failure costs the
     * working wireless-debugging link for that round; after a couple of tries we stop trying and just use
     * wireless debugging, rather than breaking it on every launch.
     */
    var tcpModeFailures: Int
        get() = p.getInt("tcpModeFailures", 0)
        set(v) = p.edit().putInt("tcpModeFailures", v).apply()

    companion object {
        /** Opens Developer options scrolled to "Wireless debugging" (the key AOSP and One UI both use). */
        fun wirelessDebuggingIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
