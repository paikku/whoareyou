package com.carcast.adb

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings

/** What the app remembers about wireless debugging. Ports are NOT remembered: adbd changes them on every toggle. */
class AdbPrefs(context: Context) {
    private val p = context.getSharedPreferences("adb", Context.MODE_PRIVATE)

    /** Set after a successful pairing; only affects what the UI says (adbd is the source of truth). */
    var paired: Boolean
        get() = p.getBoolean("paired", false)
        set(v) = p.edit().putBoolean("paired", v).apply()

    /** A user-typed connect port (from the Wireless debugging screen), used instead of mDNS when > 0. */
    var manualConnectPort: Int
        get() = p.getInt("connectPort", 0)
        set(v) = p.edit().putInt("connectPort", v).apply()

    companion object {
        /** Opens Developer options scrolled to "Wireless debugging" (the key AOSP and One UI both use). */
        fun wirelessDebuggingIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", "toggle_adb_wireless") })
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
