package com.carcast.adb

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * mDNS shows every device on the Wi-Fi that has wireless debugging on. We only ever want our own
 * adbd, so a discovered service counts only when its host address is one of ours (or loopback).
 */
object LocalHost {
    fun addresses(): Set<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .flatMap { ni -> ni.inetAddresses.toList() }
            .map { it.hostAddress?.substringBefore('%') ?: "" }
            .filter { it.isNotEmpty() }
            .toSet()
    } catch (_: Exception) { emptySet() }

    fun isLocal(host: InetAddress?, local: Set<String> = addresses()): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress || host.isAnyLocalAddress) return true
        val s = host.hostAddress?.substringBefore('%') ?: return false
        return s in local
    }
}
