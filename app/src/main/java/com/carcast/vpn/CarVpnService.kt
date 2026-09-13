package com.carcast.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.carcast.Config
import com.carcast.widget.CarCastWidget

/**
 * Not a VPN in any meaningful sense: it only attaches [Config.TUN_ADDRESS] to the phone.
 * No routes are added, so no traffic (ours or anyone's) is sent through the tun device.
 * Hotspot clients addressing 100.99.9.9 hit the kernel's local table and are delivered to
 * whatever socket is bound on that port.
 *
 * That socket must belong to a system uid: since Android 14, netd's BPF ingress program drops
 * packets to a VPN address that arrive on a non-VPN interface when the receiving socket belongs
 * to an app uid (protect(), app exclusion and Network binding do not help; verified on One UI 8).
 * Hence the HTTP/WS server runs in the shell-uid process, and this service only keeps the address.
 *
 * allowBypass() + addDisallowedApplication(self) keep the VPN from touching anyone's routing:
 * a secure VPN with an empty table would prohibit every non-VPN packet of the covered uids.
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
            setState(if (tun != null) State.UP else State.ERROR)
            Log.i(TAG, "tun ${Config.TUN_ADDRESS}/${Config.TUN_PREFIX} state=$state")
        } catch (e: Exception) {
            setState(State.ERROR)
            Log.e(TAG, "establish failed", e)
        }
    }

    private fun closeTun() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        setState(State.DOWN)
    }

    /**
     * The tun is the only thing that knows when the tun is up, so it is the only thing that can say so.
     *
     * Whoever asked for the change learns nothing by asking again straight away: stopping goes through
     * startService, so the caller is several thread hops ahead of the tun actually closing. The home screen
     * switch was left showing "VPN ●" after being switched off for exactly that reason — the redraw ran
     * before this line did, and nothing redrew afterwards.
     */
    private fun setState(s: State) {
        if (state == s) return
        state = s
        CarCastWidget.refresh(this)
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

        /** Written by the service itself on every transition, which is also when the widget is redrawn. */
        @Volatile
        var state: State = State.DOWN
            private set
    }
}
