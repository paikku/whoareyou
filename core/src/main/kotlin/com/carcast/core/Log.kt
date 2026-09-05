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

    /** Last [RECENT_MAX] lines, served as /api/log so a detached shell server can still be read from the app or a laptop. */
    private val recent = ArrayDeque<String>()
    const val RECENT_MAX = 300

    fun recentLines(): List<String> = synchronized(recent) { recent.toList() }

    private fun emit(level: Char, tag: String, msg: String, t: Throwable?) {
        synchronized(recent) {
            recent.addLast("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} $level/$tag: $msg${if (t != null) " ($t)" else ""}")
            while (recent.size > RECENT_MAX) recent.removeFirst()
        }
        sink.log(level, tag, msg, t)
    }

    fun d(tag: String, msg: String) = emit('D', tag, msg, null)
    fun i(tag: String, msg: String) = emit('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = emit('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = emit('E', tag, msg, t)
}
