package com.carcast.core

import com.carcast.core.net.SelfSignedCert
import org.junit.Assert.assertEquals
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
