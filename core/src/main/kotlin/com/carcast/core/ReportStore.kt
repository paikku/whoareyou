package com.carcast.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Diagnostic reports posted by the car's browser (`/diag` → `POST /api/report`).
 *
 * In the car there is no devtools and no way to copy text, so the page pushes what it measured back
 * to the phone. Reports are kept in memory (last [MAX]) and, when [dir] is given, written as one JSON
 * file each so they survive a server restart and can be fetched later from a laptop
 * (`GET /api/reports`) or shared from the app.
 */
class ReportStore(private val dir: File? = null) {
    class Report(val id: Int, val receivedAt: String, val remote: String, val body: String) {
        /** The client's own one-line summary (`"summary":"..."` in the body), for status lines. */
        val summary: String get() = SUMMARY.find(body)?.groupValues?.get(1)?.let { Json.unescape(it) } ?: ""

        fun toMap(): Map<String, Any?> = linkedMapOf(
            "id" to id, "receivedAt" to receivedAt, "remote" to remote, "summary" to summary, "report" to Json.Raw(body),
        )
    }

    private val reports = ArrayList<Report>()
    private var nextId = 1

    init {
        if (dir != null) {
            dir.mkdirs()
            val files = dir.listFiles { f -> f.name.startsWith("report-") && f.name.endsWith(".json") }?.sortedBy { it.name }
            for (f in files.orEmpty()) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                val id = ID.find(f.name)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                val receivedAt = FIELD("receivedAt").find(text)?.groupValues?.get(1) ?: ""
                val remote = FIELD("remote").find(text)?.groupValues?.get(1) ?: ""
                val start = text.indexOf("\"report\":")
                if (start < 0 || !text.endsWith("}")) continue
                val body = text.substring(start + "\"report\":".length, text.length - 1).trim()
                if (!Json.isObject(body)) continue
                reports += Report(id, receivedAt, remote, body)
                nextId = maxOf(nextId, id + 1)
            }
            while (reports.size > MAX) reports.removeAt(0)
        }
    }

    val size: Int get() = synchronized(this) { reports.size }
    val last: Report? get() = synchronized(this) { reports.lastOrNull() }

    /** Stores [body] (must be a JSON object) and returns the record; null when the body is not JSON. */
    fun add(body: String, remote: String): Report? {
        val trimmed = body.trim()
        if (!Json.isObject(trimmed)) return null
        val r: Report
        synchronized(this) {
            r = Report(nextId++, now(), remote, trimmed)
            reports += r
            while (reports.size > MAX) reports.removeAt(0)
        }
        if (dir != null) {
            val f = File(dir, "report-${r.receivedAt.replace(":", "").replace("-", "")}-${r.id}.json")
            runCatching { f.writeText(Json.obj(r.toMap())) }
                .onFailure { Log.w(TAG, "report not saved to $f: $it") }
        }
        return r
    }

    /** Newest first. */
    fun list(limit: Int = MAX): List<Report> = synchronized(this) { reports.asReversed().take(limit) }

    fun listJson(limit: Int = MAX): String = Json.array(list(limit).map { it.toMap() })

    companion object {
        private const val TAG = "ReportStore"
        const val MAX = 50
        private val SUMMARY = Regex("\"summary\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        private val ID = Regex("-(\\d+)\\.json$")
        private fun FIELD(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")

        private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }
}
