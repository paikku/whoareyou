package com.carcast.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.carcast.Config

/**
 * Not a VPN in any meaningful sense: it only attaches [Config.TUN_ADDRESS] to the phone.
 * No routes are added, so no traffic (ours or anyone's) is sent through the tun device.
 * Hotspot clients addressing 100.99.9.9 hit the kernel's local table and are delivered to
 * whatever socket is bound on that port (our HTTP server on 0.0.0.0).
 *
 * Two things keep Android's VPN policy routing out of the way:
 *  - allowBypass(): a "secure" VPN adds a prohibit rule that drops any packet of a VPN-subject
 *    uid that the (empty) tun table cannot route. That killed our own SYN-ACKs to hotspot
 *    clients and every other app's internet. A bypassable VPN has no such rule.
 *  - addDisallowedApplication(self): our own sockets are never subject to the VPN at all,
 *    so replies sourced from 100.99.9.9 are routed like any normal app's packets.
 */
class CarVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                closeTun()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> openTun()
        }
        return START_STICKY
    }

    private fun openTun() {
        if (tun != null) return
        try {
            val builder = Builder()
                .setSession("CarCast")
                .addAddress(Config.TUN_ADDRESS, Config.TUN_PREFIX)
                .setMtu(1500)
                .setBlocking(false)
                .setMetered(false)
                .allowBypass()
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: android.content.pm.PackageManager.NameNotFoundException) {
                Log.w(TAG, "cannot exclude self from VPN", e)
            }
            tun = builder.establish()
            state = if (tun != null) State.UP else State.ERROR
            Log.i(TAG, "tun ${Config.TUN_ADDRESS}/${Config.TUN_PREFIX} state=$state")
        } catch (e: Exception) {
            state = State.ERROR
            Log.e(TAG, "establish failed", e)
        }
    }

    private fun closeTun() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        state = State.DOWN
    }

    override fun onRevoke() {
        closeTun()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTun()
        super.onDestroy()
    }

    enum class State { DOWN, UP, ERROR }

    companion object {
        private const val TAG = "CarVpnService"
        const val ACTION_STOP = "com.carcast.vpn.STOP"

        @Volatile
        var state: State = State.DOWN
            private set
    }
}
