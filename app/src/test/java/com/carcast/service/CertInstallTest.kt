package com.carcast.service

import com.carcast.Config
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 인증서를 폰 위에서 갈아끼우는 길. 이게 없으면 만료되는 날 차는 경고 화면만 띄우고, 그날 운전자가
 * 들고 있는 것은 폰뿐이다.
 *
 * 여기서 잡아 두는 계약은 넷이다: 두 파일로 주는 출처와 한 덩어리로 주는 출처를 둘 다 다루고, PEM 이
 * 아닌 것은 **서버에 보내기 전에** 멈추고, 개인키를 평문으로 받지 않고, 서버가 거부하면 그 이유를
 * 지어내지 않고 그대로 옮긴다. 마지막 하나는 진짜 소켓으로 — loopback 으로 POST 하는 것이 이 코드가
 * 실제로 하는 일의 절반이라 흉내로는 확인이 되지 않는다.
 */
class CertInstallTest {

    private var fake: FakeServer? = null

    @After
    fun tearDown() {
        fake?.close()
        fake = null
    }

    private val CERT = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
    private val KEY = "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----"

    /** local-ip.sh 는 체인과 키를 따로 준다. 서버의 창구는 한 덩어리만 받으므로 여기서 붙여야 한다. */
    @Test
    fun theTwoFileSourceIsFetchedAsTwoFilesAndSentAsOne() {
        val asked = CopyOnWriteArrayList<String>()
        var sent: String? = null
        val r = CertInstall.install(
            source = CertInstall.LOCAL_IP_SH,
            fetch = { url ->
                asked.add(url)
                if (url.endsWith(".key")) KEY else CERT
            },
            post = { sent = it; """{"ok":true,"subject":"CN=*.local-ip.sh","notAfter":"2026-12-16","host":"100-99-9-9.local-ip.sh","port":3443,"trusted":true}""" },
        )

        assertEquals(listOf("https://local-ip.sh/server.pem", "https://local-ip.sh/server.key"), asked)
        assertTrue(sent!!, sent!!.contains(CERT))
        assertTrue(sent!!, sent!!.contains(KEY))
        assertTrue(r.message, r.ok)
        assertTrue(r.message, r.message.contains("https://100-99-9-9.local-ip.sh:3443/"))
    }

    /** 우리 도메인으로 받은 것은 `cat cert.pem key.pem` 한 덩어리다 — 주소 하나면 그대로 받는다. */
    @Test
    fun aBundleUrlIsFetchedOnce() {
        val asked = CopyOnWriteArrayList<String>()
        val r = CertInstall.install(
            source = "https://files.example.com/car/bundle.pem",
            fetch = { asked.add(it); "$CERT\n$KEY\n" },
            post = { """{"ok":true,"subject":"CN=car.example.com","notAfter":"2026-12-16","host":"car.example.com","port":3443,"trusted":true}""" },
        )

        assertEquals(listOf("https://files.example.com/car/bundle.pem"), asked)
        assertTrue(r.message, r.ok)
        assertFalse("공개 키 출처가 아니다", CertInstall.isPublicKeySource("https://files.example.com/car/bundle.pem"))
    }

    /**
     * 404 페이지나 로그인 화면을 그대로 넘기면 서버 쪽 오류가 "무엇이 왔는지"를 말해 주지 못한다.
     * 그리고 서버에 닿지도 않았다는 것이 요점이다 — 지금 도는 인증서는 건드려지지 않는다.
     */
    @Test
    fun somethingThatIsNotAPemNeverReachesTheServer() {
        var sent: String? = null
        val noKey = CertInstall.install(source = "https://x/bundle.pem", fetch = { CERT }, post = { sent = it; "{}" })
        assertFalse(noKey.ok)
        assertTrue(noKey.message, noKey.message.contains("개인키가 없습니다"))

        val html = CertInstall.install(source = "https://x/bundle.pem", fetch = { "<html>404</html>" }, post = { sent = it; "{}" })
        assertFalse(html.ok)
        assertTrue(html.message, html.message.contains("인증서가 없습니다"))

        assertNull("서버에 아무것도 보내면 안 된다", sent)
    }

    /** 개인키가 지나가는 길이다. 평문 주소는 받지 않는다 — 기본 경로(download)가 그 자리에서 멈춘다. */
    @Test
    fun aPlainHttpSourceIsRefusedBeforeAnythingIsFetched() {
        var sent: String? = null
        val r = CertInstall.install(source = "http://files.example.com/bundle.pem", post = { sent = it; "{}" })
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("https"))
        assertNull(sent)
    }

    /** 서버는 세워 본 뒤에만 저장한다. 거부하면 그 이유가 유일하게 쓸모 있는 정보이므로 지어내지 않는다. */
    @Test
    fun aRefusalFromTheServerIsPassedThroughAsItIs() {
        val r = CertInstall.install(
            source = "https://x/bundle.pem",
            fetch = { "$CERT\n$KEY\n" },
            post = { """{"ok":false,"error":"certificate and key do not match"}""" },
        )
        assertFalse(r.ok)
        assertTrue(r.message, r.message.contains("certificate and key do not match"))
    }

    /**
     * 그리고 진짜 소켓으로 한 번. loopback 의 `POST /api/tls` 가 이 코드의 나머지 절반이고, 그 창구가
     * loopback 전용인 이유(차가 무엇을 믿을지를 바꾸는 일이다) 때문에 폰 위에서 도는 이 앱 말고는
     * 부를 수 있는 것이 없다.
     */
    @Test(timeout = 30_000)
    fun itReallyPostsThePemToTheServerOverLoopback() {
        val server = FakeServer().also { fake = it }
        val r = CertInstall.install(source = "https://x/bundle.pem", fetch = { "$CERT\n$KEY\n" })

        assertEquals(listOf("POST /api/tls"), server.requests.toList())
        assertTrue(server.bodies.toString(), server.bodies.single().contains(CERT))
        assertTrue(server.bodies.toString(), server.bodies.single().contains(KEY))
        assertTrue(r.message, r.ok)
        assertTrue(r.message, r.message.contains("2026-12-16"))
    }

    // ── 자동 갱신의 판단 — 순수 함수라 시각을 넣어 본다 ──────────────────────────────────────

    private val DAY = 24L * 3600 * 1000
    private fun status(trusted: Boolean, notAfterMs: Long, httpsPort: Int = 3443) =
        """{"httpsPort":$httpsPort,"tlsTrusted":$trusted,"tlsNotAfter":"${java.time.Instant.ofEpochMilli(notAfterMs)}"}"""

    /** 멀쩡한 인증서는 건드리지 않는다 — 갱신은 만료가 가까울 때만이다. */
    @Test
    fun aHealthyCertificateIsLeftAlone() {
        val now = 1_700_000_000_000L
        assertNull(CertInstall.renewalDue(status(true, now + 60 * DAY), now, 0L))
    }

    /** 14 일 안이면 갱신한다. 하루 한 번씩 열네 번의 기회가 남는 시점이다. */
    @Test
    fun anExpiringCertificateIsRenewed() {
        val now = 1_700_000_000_000L
        val why = CertInstall.renewalDue(status(true, now + 10 * DAY), now, 0L)
        assertTrue(why.toString(), why != null && why.contains("만료"))
    }

    /** 자체서명도 갱신 대상이다 — 이 차는 그 경고를 넘지 못하므로 그대로 두면 하드웨어 경로가 영영 없다. */
    @Test
    fun aSelfSignedCertificateIsReplaced() {
        val now = 1_700_000_000_000L
        val why = CertInstall.renewalDue(status(false, now + 300 * DAY), now, 0L)
        assertTrue(why.toString(), why != null && why.contains("자체서명"))
    }

    /** 하루에 한 번만. 출처가 죽어 있어도 LTE 를 계속 두드리지 않는다. */
    @Test
    fun atMostOnceADay() {
        val now = 1_700_000_000_000L
        val due = status(true, now + 3 * DAY)
        assertNull(CertInstall.renewalDue(due, now, now - 2 * 3600 * 1000))
        assertTrue(CertInstall.renewalDue(due, now, now - 25 * 3600 * 1000) != null)
    }

    /** TLS listener 가 없거나 상태를 못 읽으면 할 일이 없다 — 갈아끼울 자리가 없다. */
    @Test
    fun nothingToDoWithoutATlsListenerOrStatus() {
        val now = 1_700_000_000_000L
        assertNull(CertInstall.renewalDue(status(false, now, httpsPort = 0), now, 0L))
        assertNull(CertInstall.renewalDue(null, now, 0L))
        assertNull(CertInstall.renewalDue("not json", now, 0L))
        assertNull(CertInstall.renewalDue("""{"httpsPort":3443,"tlsTrusted":true,"tlsNotAfter":"garbage"}""", now, 0L))
    }

    /** /api/tls 하나만 아는 서버. BulkControlTest 와 같은 이유로 생 소켓이다(앱 단위 검사에는 안드로이드가 없다). */
    private class FakeServer : AutoCloseable {
        private val socket = bind()
        val requests = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        @Volatile private var closed = false

        private val thread = Thread({
            while (!closed) {
                val client = try { socket.accept() } catch (_: Exception) { return@Thread }
                try { serve(client) } catch (_: Exception) { } finally { runCatching { client.close() } }
            }
        }, "fake-tls-server").apply { isDaemon = true; start() }

        private fun bind(): ServerSocket {
            var last: Exception? = null
            repeat(100) {
                try {
                    return ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress("127.0.0.1", Config.HTTP_PORT), 8)
                    }
                } catch (e: java.io.IOException) {
                    last = e
                    Thread.sleep(50)
                }
            }
            throw IllegalStateException("port ${Config.HTTP_PORT} never came free", last)
        }

        private fun serve(client: Socket) {
            val input = client.getInputStream()
            val reader = input.bufferedReader()
            val request = reader.readLine() ?: return
            val parts = request.split(' ')
            requests.add("${parts[0]} ${parts.getOrElse(1) { "/" }}")
            var length = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toInt()
                }
            }
            if (length > 0) {
                val body = CharArray(length)
                var read = 0
                while (read < length) {
                    val n = reader.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                bodies.add(String(body, 0, read))
            }
            write(
                client.getOutputStream(),
                """{"ok":true,"subject":"CN=car.example.com","notAfter":"2026-12-16","host":"car.example.com","port":3443,"trusted":true}""",
            )
        }

        private fun write(out: OutputStream, body: String) {
            val bytes = body.toByteArray()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n"
                    + "Connection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        }

        override fun close() {
            closed = true
            runCatching { socket.close() }
            thread.interrupt()
            runCatching { thread.join(2_000) }
        }
    }
}
