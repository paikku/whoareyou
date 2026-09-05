package com.carcast.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Finds the port adbd listens on, via the mDNS records it publishes:
 *  - [PAIRING] `_adb-tls-pairing._tcp` — only while the "Pair device with pairing code" dialog is open
 *  - [CONNECT] `_adb-tls-connect._tcp` — whenever wireless debugging is on
 * Both ports change on every toggle/reboot, so they are looked up every time, never stored.
 * Only services resolving to one of this phone's own addresses are reported (see [LocalHost]).
 */
class AdbMdns(context: Context, private val type: String, private val requireLocal: Boolean = true, private val onPort: (Int) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "adb-mdns").apply { isDaemon = true } }
    @Volatile private var running = false
    private var reported = -1
    private val callbacks = HashMap<String, NsdManager.ServiceInfoCallback>()

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery start failed $type: $errorCode") }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String) { Log.i(TAG, "discovering $type") }
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!running) return
            Log.d(TAG, "found ${info.serviceName}")
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { Log.w(TAG, "resolve registration failed: $errorCode") }
                override fun onServiceUpdated(resolved: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= 34) resolved.hostAddresses.firstOrNull() else @Suppress("DEPRECATION") resolved.host
                    val port = resolved.port
                    val local = LocalHost.isLocal(host)
                    Log.i(TAG, "${resolved.serviceName} → $host:$port local=$local")
                    if ((local || !requireLocal) && port > 0 && running && reported != port) {
                        reported = port
                        onPort(port)
                    }
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() {}
            }
            synchronized(callbacks) {
                callbacks.remove(info.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
                callbacks[info.serviceName] = cb
            }
            try { nsd.registerServiceInfoCallback(info, executor, cb) } catch (e: Exception) { Log.w(TAG, "resolve: $e") }
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(callbacks) { callbacks.remove(info.serviceName) }?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
    }

    fun start() {
        if (running) return
        running = true
        reported = -1
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
        synchronized(callbacks) {
            for (cb in callbacks.values) runCatching { nsd.unregisterServiceInfoCallback(cb) }
            callbacks.clear()
        }
    }

    companion object {
        private const val TAG = "AdbMdns"
        const val PAIRING = "_adb-tls-pairing._tcp"
        const val CONNECT = "_adb-tls-connect._tcp"
    }
}
