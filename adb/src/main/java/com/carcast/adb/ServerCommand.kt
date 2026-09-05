package com.carcast.adb

/**
 * The shell command that starts our server as uid 2000, identical to what the testing guide has the
 * user type from a PC. The APK path is the app's own base.apk (world-readable, so no copy to
 * /data/local/tmp), and the build id lets the server refuse a stale APK/server mix.
 */
object ServerCommand {
    const val MAIN_CLASS = "com.carcast.server.Server"

    fun build(apkPath: String, buildId: String, port: Int, extra: Map<String, String> = emptyMap()): String {
        require(apkPath.isNotBlank() && !apkPath.contains('\'')) { "bad apk path" }
        require(Regex("[A-Za-z0-9._-]+").matches(buildId)) { "bad build id '$buildId'" }
        val args = buildString {
            append("port=").append(port)
            for ((k, v) in extra) {
                require(Regex("[a-z]+").matches(k) && !v.contains(Regex("[\\s'\"]"))) { "bad option $k=$v" }
                append(' ').append(k).append('=').append(v)
            }
        }
        return "CLASSPATH='$apkPath' exec app_process / $MAIN_CLASS $buildId $args"
    }

    /**
     * Detached form: the server outlives the adb stream, wireless debugging (which Android turns off
     * with Wi-Fi) and the app, until reboot or `POST /api/stop` from loopback. Output goes to [logFile]
     * (shell-writable; the app reads the same lines via /api/log). This is how the app starts it.
     */
    fun detached(apkPath: String, buildId: String, port: Int, logFile: String = "/data/local/tmp/carcast/server.log"): String {
        require(!logFile.contains(Regex("[\\s'\"]"))) { "bad log path" }
        val inner = build(apkPath, buildId, port, mapOf("daemon" to "true")).removePrefix("CLASSPATH='$apkPath' exec ")
        return "mkdir -p ${logFile.substringBeforeLast('/')}; CLASSPATH='$apkPath' setsid nohup $inner >$logFile 2>&1 </dev/null & echo launched pid=$!"
    }

    /** What the user types from a PC when the app cannot do it itself; shown on screen. */
    fun forPc(packageName: String, buildId: String, port: Int): String =
        "adb shell 'CLASSPATH=\$(pm path $packageName | cut -d: -f2) app_process / $MAIN_CLASS $buildId port=$port'"
}
