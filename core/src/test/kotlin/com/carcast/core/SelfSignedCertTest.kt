package com.carcast.core

import com.carcast.core.net.SelfSignedCert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * The certificate is written byte by byte (no BouncyCastle in the shell process), so the tests are: does a
 * real X.509 parser accept it, does a real TLS stack serve with it, and does a real TLS client complete a
 * handshake and get the page. Anything wrong in the DER fails here rather than in a car, which is the only
 * place the certificate is otherwise exercised.
 *
 * Why it exists at all: `VideoDecoder` is `[SecureContext]`, so "does this car have WebCodecs" cannot be
 * asked over http. See [SelfSignedCert].
 */
class SelfSignedCertTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private object NoAssets : Assets {
        override fun open(path: String): InputStream? = null
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test(timeout = 20_000)
    fun theCertificateParsesAndCoversThePhonesAddresses() {
        val tls = SelfSignedCert.load(tmp.newFile("tls.p12").also { it.delete() }, listOf("swlan0=10.207.115.115"))
        val cert: X509Certificate = tls.certificate

        cert.checkValidity() // throws if the dates are wrong, which back-dating exists to prevent
        cert.verify(cert.publicKey) // self-signed: the signature must check out against its own key
        assertEquals("CN=CarCast", cert.subjectX500Principal.name)
        assertEquals("a self-signed certificate is its own issuer", cert.subjectX500Principal, cert.issuerX500Principal)

        val sans = cert.subjectAlternativeNames.orEmpty().map { "${it[0]}:${it[1]}" }
        // 7 = iPAddress, 2 = dNSName (RFC 5280 GeneralName tags).
        assertTrue("the tun address must be covered — it is the one the car opens: $sans", "7:100.99.9.9" in sans)
        assertTrue("loopback is what the laptop and the e2e tests use: $sans", "7:127.0.0.1" in sans)
        assertTrue("the hotspot address the host passed in is missing: $sans", "7:10.207.115.115" in sans)

        // Chrome refuses a server certificate without serverAuth, so this one is not optional.
        assertTrue("extKeyUsage serverAuth", cert.extendedKeyUsage.orEmpty().contains("1.3.6.1.5.5.7.3.1"))
        assertEquals("not a CA", -1, cert.basicConstraints)
        assertTrue("digitalSignature", cert.keyUsage?.get(0) == true)
        assertTrue("SHA-256 fingerprint, as the browser shows it", Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(tls.fingerprint))
    }

    /** A new certificate every start would make the car click through the warning every start. */
    @Test(timeout = 20_000)
    fun theCertificateSurvivesARestart() {
        val store = tmp.newFile("tls.p12").also { it.delete() }
        val first = SelfSignedCert.load(store, emptyList())
        assertTrue("the keystore should have been written", store.isFile)
        val second = SelfSignedCert.load(store, emptyList())
        assertEquals(first.fingerprint, second.fingerprint)
    }

    /** A corrupt keystore must cost a warning, not the page. */
    @Test(timeout = 20_000)
    fun aBrokenKeystoreIsReplacedRatherThanFatal() {
        val store = tmp.newFile("tls.p12")
        store.writeText("this is not a PKCS12 file")
        assertNotNull(SelfSignedCert.load(store, emptyList()).certificate)
    }

    /**
     * A certificate somebody else signed, handed over as PEM. This is the path that matters on the car:
     * its interstitial for a self-signed certificate turned out to be the non-overridable kind (no
     * "Advanced"), so only a publicly trusted certificate can ever reach a secure context there.
     *
     * The fixture is made here rather than committed — a private key in git is a bad habit even when the
     * key is worthless — and it is round-tripped through both PEM shapes Let's Encrypt tooling produces.
     */
    @Test(timeout = 20_000)
    fun aCertificateHandedOverAsPemIsUsedAsIs() {
        val made = SelfSignedCert.load(tmp.newFile("src.p12").also { it.delete() }, emptyList())
        val certPem = pem("CERTIFICATE", made.certificate.encoded)

        val store = java.security.KeyStore.getInstance("PKCS12").apply {
            tmp.root.resolve("src.p12").inputStream().use { load(it, "carcast".toCharArray()) }
        }
        val key = store.getKey("carcast", "carcast".toCharArray()) as java.security.PrivateKey
        val loaded = SelfSignedCert.fromPem(certPem, pem("PRIVATE KEY", key.encoded))

        assertEquals("the same certificate should come back", made.fingerprint, loaded.fingerprint)
        assertFalse("a PEM we were handed is not ours to call self-signed", loaded.selfSigned)
    }

    /** Let's Encrypt's tooling usually writes PKCS#1; Java only reads PKCS#8, so it is rewrapped. */
    @Test(timeout = 20_000)
    fun aPkcs1RsaKeyIsAccepted() {
        val keys = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs1 = innerPkcs1(keys.private.encoded)
        // A certificate is needed to pair with it; any will do, so borrow the one we can make.
        val cert = SelfSignedCert.load(null, emptyList()).certificate
        // The key will not match that certificate's public key, which PKCS12 does not check — what is under
        // test is that the PKCS#1 body is parsed at all (a wrong wrapper throws InvalidKeySpecException).
        val tls = SelfSignedCert.fromPem(pem("CERTIFICATE", cert.encoded), pem("RSA PRIVATE KEY", pkcs1))
        assertNotNull(tls.certificate)
    }

    /**
     * The RSAPrivateKey inside a PKCS#8 PrivateKeyInfo: SEQUENCE { INTEGER 0, AlgorithmIdentifier,
     * OCTET STRING(pkcs1) }. Java will not emit PKCS#1, so the test takes it back apart.
     */
    private fun innerPkcs1(pkcs8: ByteArray): ByteArray {
        var i = 0
        /** Reads the length at [i], leaves [i] on the first content byte, returns the content size. */
        fun len(): Int {
            val first = pkcs8[i++].toInt() and 0xFF
            if (first < 0x80) return first
            var n = 0
            repeat(first and 0x7F) { n = (n shl 8) or (pkcs8[i++].toInt() and 0xFF) }
            return n
        }
        /** Skips one whole TLV, whatever it is. (`i += len()` would not: Kotlin reads i before len() moves it.) */
        fun skip(expectedTag: Int) {
            require(pkcs8[i].toInt() == expectedTag) { "expected tag $expectedTag at $i, found ${pkcs8[i]}" }
            i++
            val n = len()
            i += n
        }
        require(pkcs8[i].toInt() == 0x30) { "PrivateKeyInfo is not a SEQUENCE" }
        i++
        len()
        skip(0x02) // version
        skip(0x30) // AlgorithmIdentifier
        require(pkcs8[i].toInt() == 0x04) { "expected the OCTET STRING holding the key" }
        i++
        val n = len()
        return pkcs8.copyOfRange(i, i + n)
    }

    private fun pem(kind: String, der: ByteArray): String =
        "-----BEGIN $kind-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) +
            "\n-----END $kind-----\n"

    /**
     * 갱신한 인증서를 PC 없이 심는 길: `POST /api/tls` 에 PEM 한 덩어리(체인 + 키). loopback 만 허용하고
     * (차가 무엇을 믿고 열지를 바꾸는 일이다), 세워 본 뒤에만 저장하며, listener 가 새 인증서로 다시 선다.
     */
    @Test(timeout = 30_000)
    fun aRenewedCertificateCanBeInstalledOverLoopback() {
        val httpPort = freePort()
        val httpsPort = freePort()
        val certFile = tmp.newFile("tls-cert.pem").also { it.delete() }
        val keyFile = tmp.newFile("tls-key.pem").also { it.delete() }
        val session = StreamSession(
            NoAssets, port = httpPort, process = "test",
            httpsPort = httpsPort, tlsKeystore = tmp.newFile("tls.p12").also { it.delete() },
            tlsCert = certFile, tlsKey = keyFile,
        )
        session.start()
        try {
            val before = fingerprintIn(session.statusJson())

            // 심을 것: 다른 키로 만든 또 하나의 인증서. (진짜 갱신도 이 모양이다 — 같은 이름, 새 바이트.)
            val fresh = SelfSignedCert.load(tmp.newFile("fresh.p12").also { it.delete() }, emptyList())
            val store = java.security.KeyStore.getInstance("PKCS12").apply {
                tmp.root.resolve("fresh.p12").inputStream().use { load(it, "carcast".toCharArray()) }
            }
            val key = store.getKey("carcast", "carcast".toCharArray()) as java.security.PrivateKey
            val body = pem("CERTIFICATE", fresh.certificate.encoded) + pem("PRIVATE KEY", key.encoded)

            // 핫스팟에 붙은 차가 인증서를 바꿀 수 있으면 안 된다. 서버는 0.0.0.0 에 붙어 있으므로 이 호스트의
            // 비-loopback 주소로 들어가면 서버가 보는 remote 가 127. 이 아니게 된다 — 그 주소가 없는 환경에서는
            // 확인할 방법이 없으니 건너뛴다(핵심 규칙은 /api/stop 과 같은 한 줄이다).
            nonLoopbackAddress()?.let { addr ->
                val refused = post(httpPort, "/api/tls", body, host = addr)
                assertTrue("차에서 온 요청을 받아 주었다: $refused", refused.contains("loopback only"))
            }
            val ok = post(httpPort, "/api/tls", body)
            assertTrue("설치 실패: $ok", ok.contains("\"ok\":true"))

            assertEquals("인증서 파일에 개인키가 들어갔다", false, certFile.readText().contains("PRIVATE KEY"))
            assertTrue("키가 저장되지 않았다", keyFile.readText().contains("PRIVATE KEY"))
            val after = fingerprintIn(session.statusJson())
            assertNotEquals("listener 가 새 인증서로 다시 서지 않았다", before, after)
            assertEquals(fresh.fingerprint, after)

            // 망가진 것을 주면 아무것도 바꾸지 않는다 — 저장해 두면 다음 기동에서 차가 열 페이지가 없어진다.
            val bad = post(httpPort, "/api/tls", "-----BEGIN CERTIFICATE-----\nnope\n-----END CERTIFICATE-----\n")
            assertTrue("거부했어야 한다: $bad", bad.contains("\"ok\":false"))
            assertEquals("실패한 설치가 인증서를 갈아치웠다", after, fingerprintIn(session.statusJson()))
        } finally {
            session.stop()
        }
    }

    private fun fingerprintIn(status: String): String =
        Regex("\"tlsFingerprint\":\"([^\"]*)\"").find(status)?.groupValues?.get(1).orEmpty()

    /** 이 호스트의 비-loopback IPv4 하나, 없으면 null. */
    private fun nonLoopbackAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull()?.hostAddress
    }.getOrNull()

    private fun post(port: Int, path: String, body: String, host: String = "127.0.0.1"): String {
        val conn = java.net.URI("http://$host:$port$path").toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }
        val stream = if (conn.responseCode >= 400) conn.errorStream else conn.inputStream
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** End to end: the same pages, over TLS, to a client that completes a real handshake. */
    @Test(timeout = 30_000)
    fun theSessionServesTheSamePagesOverTls() {
        val httpPort = freePort()
        val httpsPort = freePort()
        val session = StreamSession(
            NoAssets, port = httpPort, process = "test",
            httpsPort = httpsPort, tlsKeystore = tmp.newFile("tls.p12").also { it.delete() },
        )
        session.start()
        try {
            val status = session.statusJson()
            assertTrue("the car has to be told where to go: $status", status.contains("\"httpsPort\":$httpsPort"))
            assertTrue("and which certificate it will be asked to accept: $status", status.contains("\"tlsFingerprint\":\""))

            val socket = trustEverything().socketFactory.createSocket("127.0.0.1", httpsPort) as SSLSocket
            socket.use {
                it.startHandshake()
                it.outputStream.write("GET /api/status HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                it.outputStream.flush()
                val body = it.inputStream.readBytes().toString(Charsets.UTF_8)
                assertTrue("TLS listener did not serve /api/status: $body", body.contains("\"type\":\"status\""))
            }
        } finally {
            session.stop()
        }
    }

    /**
     * The car will be clicking through a warning; this client does the same thing on purpose. It trusts
     * anything, which is fine for a test whose subject is the handshake and the page, not the trust.
     */
    private fun trustEverything(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), java.security.SecureRandom()) }
    }
}
