package com.carcast.adb

/** Reads what the shell server prints (see core ServerMain / shell-server Server) into events. */
object ServerOutput {
    sealed class Event {
        /** `carcast-server uid=2000 build=abc1234 android=16` */
        data class Started(val uid: Int, val build: String, val android: String) : Event()
        /** `carcast-server ready build=abc1234 port=3333` */
        data class Ready(val port: Int) : Event()
        /** `carcast-server: build id mismatch, expected X got Y` or other fatal line on stderr */
        data class Failed(val reason: String) : Event()
        data class Line(val text: String) : Event()
    }

    private val STARTED = Regex("^carcast-server uid=(\\d+) build=(\\S+) android=(\\S+)")
    private val READY = Regex("^carcast-server ready build=\\S+ port=(\\d+)")

    fun parse(line: String): Event {
        STARTED.find(line)?.let { return Event.Started(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3]) }
        READY.find(line)?.let { return Event.Ready(it.groupValues[1].toInt()) }
        if (line.startsWith("carcast-server:")) return Event.Failed(line.removePrefix("carcast-server:").trim())
        if (line.contains("Exception") && !line.startsWith("\tat ")) return Event.Failed(line.trim())
        return Event.Line(line)
    }

    /** Splits arbitrary chunks (shell v2 packets cut anywhere) into complete lines. */
    class LineAssembler(private val onLine: (String) -> Unit) {
        private val buf = StringBuilder()
        fun feed(chunk: String) {
            buf.append(chunk)
            while (true) {
                val i = buf.indexOf('\n')
                if (i < 0) break
                onLine(buf.substring(0, i).trimEnd('\r'))
                buf.delete(0, i + 1)
            }
        }
        fun flush() { if (buf.isNotEmpty()) { onLine(buf.toString()); buf.clear() } }
    }
}
