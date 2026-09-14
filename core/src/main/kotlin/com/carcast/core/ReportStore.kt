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
        /** The client's own one-line summary (the body's own top-level `"summary"`), for status lines. */
        val summary: String get() = topLevelSummary(body)

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

        /**
         * 본문 **최상위**의 `"summary"` 값.
         *
         * 예전에는 정규식으로 첫 매치를 집었는데, 리포트 본문에는 그보다 앞에 `server.lastReport.summary`
         * 가 들어 있다 — 즉 **직전 리포트의 요약**이다. 그것이 다시 그렇게 저장되면서 목록 전체가 옛
         * 문자열 하나로 굳어 버렸다(실차 리포트 #35~#55 가 전부 같은 `dac9803 …` 을 달고 있던 이유).
         * 그래서 중괄호 깊이를 세어 최상위 키만 본다. 키 순서에 기대지 않는다.
         */
        internal fun topLevelSummary(body: String): String {
            var i = 0
            var depth = 0
            while (i < body.length) {
                val c = body[i]
                when {
                    c == '{' || c == '[' -> { depth++; i++ }
                    c == '}' || c == ']' -> { depth--; i++ }
                    c == '"' -> {
                        val end = endOfString(body, i) ?: return ""
                        var j = end + 1
                        while (j < body.length && body[j].isWhitespace()) j++
                        val isKey = j < body.length && body[j] == ':'
                        if (isKey && depth == 1 && body.substring(i + 1, end) == "summary") {
                            var k = j + 1
                            while (k < body.length && body[k].isWhitespace()) k++
                            if (k >= body.length || body[k] != '"') return "" // 문자열이 아니면 요약이 아니다
                            val valueEnd = endOfString(body, k) ?: return ""
                            return Json.unescape(body.substring(k + 1, valueEnd))
                        }
                        i = end + 1
                    }
                    else -> i++
                }
            }
            return ""
        }

        /** 여는 따옴표 [start] 에 대응하는 닫는 따옴표의 위치. 이스케이프를 건너뛴다. */
        private fun endOfString(s: String, start: Int): Int? {
            var i = start + 1
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i += 2
                    '"' -> return i
                    else -> i++
                }
            }
            return null
        }

        private val ID = Regex("-(\\d+)\\.json$")
        private fun FIELD(name: String) = Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"")

        private fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    }
}
