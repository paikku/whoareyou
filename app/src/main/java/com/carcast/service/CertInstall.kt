package com.carcast.service

import android.content.Context
import com.carcast.Config
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 갱신한 인증서를 **폰 위에서** 받아 서버에 심는다.
 *
 * 왜 이것이 있나: 차는 자체서명 인증서의 경고를 넘지 못한다(2026-09-17 실측, tools/tls/README.md).
 * 그래서 공개 CA 가 서명한 인증서가 있어야 하고, 그런 인증서는 90일이면 만료된다 — 만료되는 날 차는
 * 경고 화면만 보여 주고, 그날 운전자가 들고 있는 것은 폰뿐이다. 그때까지의 유일한 길은 PC 에서
 * `adb push` 하거나 APK 를 다시 빌드하는 것이었다.
 *
 * 서버는 이미 `POST /api/tls` 로 인증서를 갈아끼울 수 있다(체인+키를 PEM 한 덩어리로). 그 창구는
 * **loopback 전용**이다 — 차가 무엇을 믿고 열지를 바꾸는 일이라 핫스팟에 붙은 누구도 건드리면 안 된다.
 * loopback 에서 부를 수 있는 것은 폰 위에서 도는 것뿐이고, 그것이 이 앱이다.
 *
 * 하는 일은 셋뿐이다: 받고, 이게 인증서와 키가 맞는지 보고, loopback 으로 넘긴다. **유효한가**를 판단하는
 * 것은 여기가 아니라 서버다(서버는 실제로 TLS 컨텍스트를 세워 본 뒤에만 저장한다) — 같은 판단을 두 군데서
 * 흉내 내면 둘이 엇갈리는 날이 온다.
 */
object CertInstall {

    /**
     * 기본 출처. `local-ip.sh` 는 `*.local-ip.sh` 의 Let's Encrypt 인증서와 **개인키를 공개**하고, 그
     * DNS 는 이름에 적힌 주소를 돌려준다(`100-99-9-9.local-ip.sh` → `100.99.9.9`).
     *
     * 키가 공개돼 있으므로 이것은 **진단용**이다: 누구나 그 이름으로 서버를 세울 수 있어서, 그 인증서는
     * 누가 답하고 있는지를 아무것도 증명하지 못한다. 그래도 차의 경고는 없애고 secure context 는 서므로
     * 하드웨어 디코더에 닿는다. 제품으로 가려면 우리 도메인·우리 키를 [install] 에 URL 로 넘긴다.
     */
    const val LOCAL_IP_SH = "https://local-ip.sh"

    /** 이 출처의 키는 공개돼 있다 — 화면에도 그렇게 적어야 한다. */
    fun isPublicKeySource(source: String): Boolean = runCatching { URL(source).host }
        .getOrNull()?.equals("local-ip.sh", ignoreCase = true) == true

    data class Outcome(val ok: Boolean, val message: String)

    /** 앱이 저장하는 곳. 시트의 버튼과 자동 갱신이 **같은 주소**를 본다 — 한쪽에서 바꾸면 다른 쪽도 따라간다. */
    const val PREFS = "tls"
    const val PREF_SOURCE = "certSource"
    private const val PREF_LAST_AUTO = "lastAutoRenewAt"

    /** 만료가 이만큼 남으면 갱신한다. Let's Encrypt 가 90일이라 14일이면 하루 한 번 시도할 기회가 열넷 남는다. */
    const val RENEW_BEFORE_MS = 14L * 24 * 3600 * 1000
    /** 한 번 시도했으면 이만큼 쉰다 — 실패해도 하루에 한 번만 두드린다(출처가 죽었을 때 LTE 를 갉지 않게). */
    const val RETRY_AFTER_MS = 24L * 3600 * 1000

    /**
     * 지금 자동 갱신이 필요한가. 필요하면 **왜**, 아니면 null. 순수 함수 — 검사가 시각과 상태를 넣어 본다.
     *
     * 갱신하는 경우는 둘이다: 공개 CA 인증서의 만료가 가깝거나, 아예 자체서명이거나. 뒤의 것도 넣는 이유는
     * 이 차가 자체서명의 경고를 **넘지 못하기** 때문이다(실측 2026-09-17) — 그 상태로는 하드웨어 경로가
     * 없고, 사람이 버튼을 찾아 누르기 전까지 느린 채로 다닌다. 갱신 뒤 서버는 세워 본 것만 저장하므로
     * 실패는 지금 인증서를 건드리지 않는다.
     */
    fun renewalDue(statusJson: String?, nowMs: Long, lastAttemptMs: Long): String? {
        if (nowMs - lastAttemptMs < RETRY_AFTER_MS) return null
        val st = runCatching { org.json.JSONObject(statusJson ?: return null) }.getOrNull() ?: return null
        if (st.optInt("httpsPort") <= 0) return null // TLS listener 자체가 없다 — 갈아끼울 자리가 없다
        if (!st.optBoolean("tlsTrusted")) return "자체서명 — 이 차는 그 경고를 넘지 못한다"
        val notAfter = runCatching { Instant.parse(st.optString("tlsNotAfter")).toEpochMilli() }.getOrNull() ?: return null
        val left = notAfter - nowMs
        return if (left < RENEW_BEFORE_MS) "만료 ${left / 86_400_000}일 전" else null
    }

    private val autoBusy = AtomicBoolean(false)

    /**
     * 사람 손 없이 갱신한다. [statusJson] 은 서버가 방금 답한 `/api/status`. 부르는 쪽의 스레드에서 돌고
     * (네트워크다 — 메인에서 부르지 말 것), 시도 시각을 **먼저** 적어 두어 도중에 죽어도 하루에 한 번만
     * 다시 온다. 출처는 시트에서 저장한 주소, 없으면 local-ip.sh 다.
     */
    fun autoRenew(context: Context, statusJson: String?, log: (String) -> Unit): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val why = renewalDue(statusJson, System.currentTimeMillis(), prefs.getLong(PREF_LAST_AUTO, 0L)) ?: return false
        if (!autoBusy.compareAndSet(false, true)) return false
        try {
            prefs.edit().putLong(PREF_LAST_AUTO, System.currentTimeMillis()).apply()
            val source = prefs.getString(PREF_SOURCE, null) ?: LOCAL_IP_SH
            log("인증서 자동 갱신 ($why): $source")
            if (isPublicKeySource(source)) log("주의: 이 출처는 개인키가 공개된 진단용 인증서입니다")
            val r = install(source, log)
            log("인증서 자동 갱신: ${r.message}")
            return r.ok
        } finally {
            autoBusy.set(false)
        }
    }

    /** 체인 + 키는 몇 KB 다. 이보다 크면 PEM 이 아닌 것을 받고 있는 것이다. */
    private const val MAX_PEM_BYTES = 512 * 1024

    private const val CERT_MARK = "-----BEGIN CERTIFICATE-----"
    private val KEY_MARK = Regex("-----BEGIN (?:RSA |EC |ENCRYPTED )?PRIVATE KEY-----")

    /**
     * [source] 에서 받아 폰의 서버에 심는다. 호출한 스레드에서 돈다(메인 스레드에서 부르지 말 것).
     *
     * [fetch] 와 [post] 는 검사에서 갈아끼우기 위한 자리다. 기본값이 진짜 경로이고, 그 둘 중 하나만
     * 가짜로 바꿔도 나머지 절반은 실제로 돈다.
     */
    fun install(
        source: String = LOCAL_IP_SH,
        log: (String) -> Unit = {},
        fetch: (String) -> String = ::download,
        post: (String) -> String = ::postPem,
    ): Outcome {
        val url = source.trim()
        if (url.isEmpty()) return Outcome(false, "받아올 주소가 비어 있습니다")
        log("인증서 받는 중: $url")
        val pem = try {
            bundle(url, fetch)
        } catch (e: Exception) {
            return Outcome(false, "받지 못했습니다: ${e.message ?: e.toString()}")
        }
        // 서버가 세워 보기 전에 여기서 걸러 두는 것은 하나뿐이다: 애초에 인증서와 키가 아닌 것.
        // 404 페이지나 로그인 화면을 그대로 넘기면 서버 쪽 오류 메시지가 "무엇이 왔는지"를 말해 주지 못한다.
        if (!pem.contains(CERT_MARK)) return Outcome(false, "인증서가 없습니다 (받은 것이 PEM 이 아닙니다)")
        if (!KEY_MARK.containsMatchIn(pem)) return Outcome(false, "개인키가 없습니다 — 체인과 키를 함께 주는 주소여야 합니다")
        log("받았습니다 (${pem.length}바이트) — 폰의 서버에 심는 중")

        val answer = try {
            post(pem)
        } catch (e: Exception) {
            return Outcome(false, "서버에 넘기지 못했습니다 (127.0.0.1:${Config.HTTP_PORT}): ${e.message ?: e.toString()}")
        }
        return describe(answer)
    }

    /**
     * `local-ip.sh` 는 체인과 키를 두 파일로 준다(`/server.pem`, `/server.key`). 우리 도메인으로 발급한
     * 것은 한 덩어리로 두는 편이 자연스러우므로(`cat cert.pem key.pem`), 주소 하나면 그대로 받는다.
     */
    private fun bundle(url: String, fetch: (String) -> String): String {
        val base = url.trimEnd('/')
        if (!isPublicKeySource(url) || URL(url).path.trim('/').isNotEmpty()) return fetch(url)
        return fetch("$base/server.pem").trimEnd() + "\n" + fetch("$base/server.key").trimEnd() + "\n"
    }

    /**
     * 받는 길. **https 만** 받는다 — 개인키가 지나가는 길이고, 평문으로 받아서는 지금 심고 있는 그
     * 인증서가 누구 것인지 알 수 없다. (`local-ip.sh` 의 키는 어차피 공개지만, 우리 도메인의 키는 아니다.)
     */
    fun download(url: String, timeoutMs: Int = 20_000): String {
        require(url.startsWith("https://")) { "https 주소여야 합니다 (개인키를 평문으로 받지 않습니다): $url" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        try {
            if (c.responseCode != 200) throw java.io.IOException("$url → HTTP ${c.responseCode}")
            // 주소를 잘못 넣으면 무엇이든 올 수 있다. 인증서 체인과 키는 아무리 길어야 몇 KB 라,
            // 그보다 한참 큰 것을 폰의 메모리로 다 읽을 이유가 없다.
            val body = c.inputStream.use { it.readNBytes(MAX_PEM_BYTES + 1) }
            if (body.size > MAX_PEM_BYTES) throw java.io.IOException("$url 이 너무 큽니다 (${MAX_PEM_BYTES / 1024}KB 넘음) — PEM 주소가 맞습니까")
            return String(body, Charsets.US_ASCII)
        } finally {
            c.disconnect()
        }
    }

    /** 심는 길: loopback 의 `POST /api/tls`. 서버는 세워 본 뒤에만 저장하고, 실패하면 아무것도 바꾸지 않는다. */
    fun postPem(pem: String, timeoutMs: Int = 20_000): String {
        val c = URL("http://127.0.0.1:${Config.HTTP_PORT}/api/tls").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5_000
        c.readTimeout = timeoutMs
        c.setRequestProperty("Content-Type", "application/x-pem-file")
        try {
            c.outputStream.use { it.write(pem.toByteArray(Charsets.US_ASCII)) }
            val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
            return stream?.use { String(it.readBytes()) } ?: ""
        } finally {
            c.disconnect()
        }
    }

    /** 서버의 답을 사람이 읽을 한 줄로. 서버가 무슨 말을 하든 그 말을 그대로 옮긴다 — 여기서 고쳐 쓰지 않는다. */
    private fun describe(answer: String): Outcome {
        val json = runCatching { org.json.JSONObject(answer) }.getOrNull()
            ?: return Outcome(false, "서버 응답을 읽지 못했습니다: ${answer.take(200)}")
        if (!json.optBoolean("ok")) {
            return Outcome(false, "서버가 거부했습니다: ${json.optString("error").ifBlank { answer.take(200) }}")
        }
        val subject = json.optString("subject").ifBlank { "?" }
        val until = json.optString("notAfter").take(10).ifBlank { "?" }
        val host = json.optString("host").ifBlank { null }
        val port = json.optInt("port").takeIf { it > 0 }
        val where = if (host != null && port != null) "https://$host:$port/" else host
        val trusted = json.optBoolean("trusted")
        return Outcome(
            true,
            if (trusted && where != null) "심었습니다: $subject ($until 까지) — 차는 $where 를 경고 없이 엽니다"
            else "심었습니다: $subject ($until 까지) — 다만 공개 CA 가 서명한 것이 아니라 차는 여전히 경고에 막힙니다",
        )
    }
}
