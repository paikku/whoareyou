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
     *
     * Killing a previous server: NEVER `pkill -f <class name>` from here. adbd runs this whole string as
     * `sh -c '…'`, so the launching shell's own command line contains the class name (in the app_process
     * part) and such a pkill kills the shell itself before it launches anything — that is exactly what
     * happened in builds c602d65–8af473a, where the launch silently did nothing and the app kept showing
     * an older build's log. So we kill by the pid file we wrote last time, plus a pattern anchored at the
     * start of the command line (`^app_process / …`), which can match the server but never a `sh -c` shell.
     * Every earlier server log is deleted before launching so a stale one can never be read as this one.
     */
    fun detached(apkPath: String, buildId: String, port: Int, logFile: String = "/data/local/tmp/carcast/server.log"): String {
        require(!logFile.contains(Regex("[\\s'\"*]"))) { "bad log path" }
        val dir = logFile.substringBeforeLast('/')
        val pid = "$dir/server.pid"
        val inner = build(apkPath, buildId, port, mapOf("daemon" to "true")).removePrefix("CLASSPATH='$apkPath' exec ")
        val serverPattern = "^app_process / " + MAIN_CLASS.replace(".", "\\.")
        // The pid file survives reboots while pids get reused, so only kill it if that pid still is our server.
        return "mkdir -p $dir; [ -f $pid ] && grep -q $MAIN_CLASS /proc/\$(cat $pid)/cmdline 2>/dev/null && kill \$(cat $pid) 2>/dev/null; " +
            "pkill -f '$serverPattern' 2>/dev/null; " +
            "sleep 1; rm -f $dir/server-*.log $logFile; " +
            "CLASSPATH='$apkPath' setsid nohup $inner >$logFile 2>&1 </dev/null & " +
            "echo \$! >$pid; sleep 2; echo launched pid=\$(cat $pid); head -c 4000 $logFile"
    }

    /** What the user types from a PC when the app cannot do it itself; shown on screen. */
    fun forPc(packageName: String, buildId: String, port: Int): String =
        "adb shell 'CLASSPATH=\$(pm path $packageName | cut -d: -f2) app_process / $MAIN_CLASS $buildId port=$port'"
}
