package com.carcast.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny logger with a pluggable sink so the same code logs to logcat inside the app,
 * to stdout under app_process (where it becomes the adb shell output) and to the console in tests.
 */
object Log {
    fun interface Sink { fun log(level: Char, tag: String, msg: String, t: Throwable?) }

    @Volatile var sink: Sink = Sink { level, tag, msg, t ->
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        println("$ts $level/$tag: $msg")
        t?.printStackTrace()
    }

    fun d(tag: String, msg: String) = sink.log('D', tag, msg, null)
    fun i(tag: String, msg: String) = sink.log('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = sink.log('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = sink.log('E', tag, msg, t)
}
