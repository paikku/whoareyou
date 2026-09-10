package com.carcast.adb

/**
 * The service names we open on adbd. Kept out of [AdbLink] on purpose: loading that class pulls in
 * Kadb, whose class files are built for a newer JDK than the CI test runtime, so a unit test that
 * touched it died with UnsupportedClassVersionError. Here the strings and their validation stay
 * testable on their own.
 */
object AdbServices {

    /**
     * What `adb tcpip <port>` sends. adbd answers with one line and re-executes itself listening on
     * that TCP port on every interface — the legacy RSA path, not the Wi-Fi-gated wireless debugging
     * one (see docs/hotspot-only.md).
     *
     * Ports below 1024 need root to bind, and adbd's well-known 5555 is avoided by the caller
     * picking a random high port instead, since the port is reachable from the hotspot.
     */
    fun tcpip(port: Int): String {
        require(port in 1024..65535) { "TCP 모드 포트 범위를 벗어남: $port" }
        return "tcpip:$port"
    }

    /** What `adb usb` sends: the opposite of [tcpip], adbd goes back to USB/wireless only. */
    const val USB = "usb:"
}
