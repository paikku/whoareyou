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

    /** What the user types from a PC when the app cannot do it itself; shown on screen. */
    fun forPc(packageName: String, buildId: String, port: Int): String =
        "adb shell 'CLASSPATH=\$(pm path $packageName | cut -d: -f2) app_process / $MAIN_CLASS $buildId port=$port'"
}
