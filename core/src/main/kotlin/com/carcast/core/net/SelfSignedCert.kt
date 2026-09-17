package com.carcast.core.net

import com.carcast.core.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * A self-signed certificate for the phone's own addresses, made on the phone, kept on the phone.
 *
 * <h3>What it is for</h3>
 * One question cannot be asked over plain http: **does this car's browser have WebCodecs?**
 * `VideoDecoder` is `[SecureContext]` in the WebCodecs IDL, so on `http://100.99.9.9:3333` the object is
 * simply absent — which tells us nothing about the car. The answer decides whether a real certificate (a
 * domain, DNS-01, renewals, a private key shipped in the APK) is worth building at all, so it has to come
 * first, and it has to be cheap.
 *
 * A self-signed certificate is enough to ask. The car shows "your connection is not private"; past
 * Advanced → Proceed the page is still an https origin, so `isSecureContext` is true and the feature
 * detection is meaningful. That is a measuring tool, not a product: nobody should be clicking through a
 * warning every drive. If the answer turns out to be yes, the warning is what the real certificate buys.
 *
 * <h3>Why it is hand-rolled</h3>
 * Neither the JDK nor Android exposes a public API that signs an X.509 certificate (`keytool` is not on
 * the phone and `sun.security.x509` is not on Android), and the alternative — BouncyCastle — is megabytes
 * of dependency in a process that today needs none. A v3 certificate is a small DER document, and this
 * file writes it: the same approach the rest of this repo takes with fMP4, WebSocket frames and JSON.
 * Reading it back is done through the platform ([CertificateFactory]), which is also the test that the
 * bytes are right.
 *
 * <h3>Stability</h3>
 * The key pair and certificate are written to [keystore] and reused. This matters: the browser remembers
 * the exception per certificate, so a certificate regenerated on every server start would make the car ask
 * again every time.
 */
object SelfSignedCert {
    private const val TAG = "SelfSignedCert"
    private const val ALIAS = "carcast"
    /**
     * The tun address the car opens (Config.TUN_ADDRESS in the app). Kept here because both the self-signed
     * SAN list and the "which host name should the car type" hint have to agree on it.
     */
    const val CAR_ADDRESS = "100.99.9.9"
    private val PASSWORD = "carcast".toCharArray()
    /** Ten years. This certificate is trusted by nobody, so a long life costs nothing and saves a re-prompt. */
    private const val VALID_DAYS = 3650L

    class Tls(val sslContext: SSLContext, val certificate: X509Certificate, val selfSigned: Boolean = true) {
        /** SHA-256 of the DER, colon-separated — the same string the browser shows under "certificate details". */
        val fingerprint: String by lazy {
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }
        }

        val subject: String get() = certificate.subjectX500Principal.name

        /**
         * The host name the car should type, when the certificate is a wildcard for a domain whose DNS turns a
         * dashed address back into that address (`*.local-ip.sh` → `100-99-9-9.local-ip.sh` → 100.99.9.9).
         * That is the whole trick: a **publicly trusted** certificate for the phone's own private address, so
         * the car gets a secure context with no warning to click through — and this car has no way to click
         * through one (2026-09-17: the interstitial came up non-overridable, NET::ERR_CERT_AUTHORITY_INVALID
         * with no "Advanced", so a self-signed certificate cannot ask the WebCodecs question here).
         *
         * Null for a self-signed certificate, which has no such domain.
         */
        fun hostFor(ip: String): String? {
            val wildcards = certificate.subjectAlternativeNames.orEmpty()
                .filter { it[0] == 2 }.map { it[1].toString() }
                .filter { it.startsWith("*.") }
            val domain = wildcards.firstOrNull() ?: return null
            return "${ip.replace('.', '-')}.${domain.removePrefix("*.")}"
        }
    }

    /**
     * Loads the certificate from [keystore], or makes one covering [addresses] (IPv4 literals; loopback and
     * the tun address are always included) and saves it there. A keystore that cannot be read is replaced —
     * a stale file must not be the reason the car has no page to open.
     */
    @Synchronized
    fun load(keystore: File?, addresses: List<String>): Tls {
        if (keystore != null && keystore.isFile) {
            try {
                return fromKeyStore(readKeyStore(keystore))
            } catch (e: Exception) {
                Log.w(TAG, "keystore unreadable (${e}) — making a new one")
            }
        }
        val ks = generate(addresses)
        if (keystore != null) {
            try {
                keystore.parentFile?.mkdirs()
                keystore.outputStream().use { ks.store(it, PASSWORD) }
                // The private key is the phone's; nobody else on the hotspot has any business reading it.
                runCatching { keystore.setReadable(false, false); keystore.setReadable(true, true) }
            } catch (e: Exception) {
                Log.w(TAG, "could not save the keystore ($e) — the car will be asked again next start")
            }
        }
        return fromKeyStore(ks)
    }

    /**
     * A certificate somebody else's CA signed, handed to us as PEM (chain + private key).
     *
     * Why this exists: the car's interstitial turned out to be the **non-overridable** kind — the text reads
     * "지금은 100.99.9.9에 방문할 수 없습니다", there is no Advanced button, and so a self-signed certificate
     * can never reach a secure context there. A publicly trusted certificate has no interstitial at all. It
     * does not need a domain of our own either: services like local-ip.sh hold a Let's Encrypt wildcard for a
     * domain whose DNS decodes a dashed address (`100-99-9-9.local-ip.sh` → 100.99.9.9) and publish the key,
     * which is exactly what a diagnostic needs and exactly what a product must not ship (everyone has that
     * key, so it proves nothing about who is answering).
     *
     * Both PKCS#8 (`BEGIN PRIVATE KEY`) and PKCS#1 (`BEGIN RSA PRIVATE KEY`, what Let's Encrypt tooling
     * usually writes) are accepted; Java only reads the former, so the latter is rewrapped here.
     */
    fun fromPem(certPem: String, keyPem: String): Tls {
        val factory = CertificateFactory.getInstance("X.509")
        val chain: List<Certificate> = pemBlocks(certPem, "CERTIFICATE")
            .map { factory.generateCertificate(ByteArrayInputStream(it)) }
        require(chain.isNotEmpty()) { "no CERTIFICATE block in the certificate PEM" }
        val ks = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, privateKey(keyPem), PASSWORD, chain.toTypedArray())
        }
        val leaf = chain.first() as X509Certificate
        Log.i(TAG, "certificate from PEM: ${leaf.subjectX500Principal.name} (${chain.size} in chain, until ${leaf.notAfter})")
        return fromKeyStore(ks).let { Tls(it.sslContext, it.certificate, selfSigned = false) }
    }

    private fun privateKey(pem: String): PrivateKey {
        val pkcs8 = pemBlocks(pem, "PRIVATE KEY").firstOrNull()
            ?: throw IllegalArgumentException("no PRIVATE KEY block in the key PEM")
        // "BEGIN RSA PRIVATE KEY" is PKCS#1: the bare RSAPrivateKey, without the algorithm wrapper Java wants.
        val der = if (pem.contains("BEGIN RSA PRIVATE KEY")) pkcs1ToPkcs8(pkcs8) else pkcs8
        val algorithm = if (pem.contains("BEGIN RSA PRIVATE KEY")) "RSA" else guessAlgorithm(der)
        return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
    }

    /** PrivateKeyInfo ::= SEQUENCE { version 0, AlgorithmIdentifier(rsaEncryption, NULL), OCTET STRING(pkcs1) } */
    private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray = seq(
        der(TAG_INTEGER, byteArrayOf(0)),
        seq(oid("1.2.840.113549.1.1.1"), der(0x05, ByteArray(0))),
        der(TAG_OCTET_STRING, pkcs1),
    )

    /** EC and RSA are the only shapes anyone hands us; the OID inside the PKCS#8 says which. */
    private fun guessAlgorithm(pkcs8: ByteArray): String =
        if (oid("1.2.840.10045.2.1").let { ec -> pkcs8.indexOfSlice(ec) >= 0 }) "EC" else "RSA"

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Every `-----BEGIN x-----` … `-----END x-----` body in [pem], Base64-decoded, in order. */
    private fun pemBlocks(pem: String, kind: String): List<ByteArray> {
        val re = Regex("-----BEGIN [A-Z ]*$kind-----(.*?)-----END [A-Z ]*$kind-----", RegexOption.DOT_MATCHES_ALL)
        return re.findAll(pem).map { Base64.getMimeDecoder().decode(it.groupValues[1]) }.toList()
    }

    private fun readKeyStore(file: File): KeyStore =
        KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }

    private fun fromKeyStore(ks: KeyStore): Tls {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return Tls(ctx, ks.getCertificate(ALIAS) as X509Certificate)
    }

    private fun generate(addresses: List<String>): KeyStore {
        // P-256: every browser takes it, and the key pair is made in milliseconds on a phone (RSA-2048 is
        // hundreds of times slower and this runs while the driver is waiting for a picture).
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val ips = (listOf(CAR_ADDRESS, "127.0.0.1") + addresses).map { it.substringAfterLast('=') }
            .filter { it.count { c -> c == '.' } == 3 }
            .distinct()
        val tbs = tbsCertificate(keys.public.encoded, ips)
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(tbs); sign() }
        val der = seq(tbs, ECDSA_SHA256_ALG_ID, bitString(signature))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        Log.i(TAG, "self-signed certificate for ${ips.joinToString(", ")} (valid until ${cert.notAfter})")
        return KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(ALIAS, keys.private, PASSWORD, arrayOf(cert))
        }
    }

    private fun tbsCertificate(publicKeyInfo: ByteArray, ips: List<String>): ByteArray {
        val now = System.currentTimeMillis()
        // Back-dated a day: a phone whose clock is slightly behind the car's must not make a certificate
        // the car considers not-yet-valid.
        val from = Date(now - 86_400_000L)
        val to = Date(now + VALID_DAYS * 86_400_000L)
        val serial = ByteArray(8).also { SecureRandom().nextBytes(it) }
        return seq(
            explicit(0, der(TAG_INTEGER, byteArrayOf(2))), // v3
            integer(BigInteger(1, serial)),
            ECDSA_SHA256_ALG_ID,
            name(),
            seq(utcTime(from), utcTime(to)),
            name(),
            publicKeyInfo, // already a DER SubjectPublicKeyInfo
            explicit(3, seq(*extensions(ips))),
        )
    }

    private fun extensions(ips: List<String>): Array<ByteArray> = arrayOf(
        // basicConstraints: not a CA.
        extension("2.5.29.19", critical = true, value = seq()),
        // keyUsage: digitalSignature only — an ECDSA key cannot encipher anything anyway.
        extension("2.5.29.15", critical = true, value = der(TAG_BIT_STRING, byteArrayOf(7, 0x80.toByte()))),
        // extKeyUsage: serverAuth. Chrome rejects a certificate without it outright.
        extension("2.5.29.37", critical = false, value = seq(oid("1.3.6.1.5.5.7.3.1"))),
        // subjectAltName. The CN is ignored by browsers; this list is what is actually checked, and it is
        // why the certificate has to be made on the phone — nobody else knows the hotspot address.
        extension("2.5.29.17", critical = false, value = seq(*(
            ips.map { der(0x87, ipBytes(it)) } + listOf(der(0x82, "localhost".toByteArray(Charsets.US_ASCII)))
        ).toTypedArray())),
    )

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) seq(oid(oid), der(TAG_BOOLEAN, byteArrayOf(0xFF.toByte())), der(TAG_OCTET_STRING, value))
        else seq(oid(oid), der(TAG_OCTET_STRING, value))

    /** CN=CarCast. Browsers ignore it — the SAN list decides — but a certificate needs a name. */
    private fun name(): ByteArray =
        seq(set(seq(oid("2.5.4.3"), der(TAG_UTF8_STRING, "CarCast".toByteArray(Charsets.UTF_8)))))

    private fun ipBytes(ip: String): ByteArray =
        ByteArray(4).also { out -> ip.split('.').forEachIndexed { i, part -> out[i] = part.toInt().toByte() } }

    // --- DER ---------------------------------------------------------------------------------------
    private const val TAG_BOOLEAN = 0x01
    private const val TAG_INTEGER = 0x02
    private const val TAG_BIT_STRING = 0x03
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_OID = 0x06
    private const val TAG_UTF8_STRING = 0x0C
    private const val TAG_UTC_TIME = 0x17

    /** ecdsa-with-SHA256 (1.2.840.10045.4.3.2). No parameters, which is what the spec requires here. */
    private val ECDSA_SHA256_ALG_ID: ByteArray by lazy { seq(oid("1.2.840.10045.4.3.2")) }

    private fun der(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun seq(vararg parts: ByteArray): ByteArray = der(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray): ByteArray = der(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun explicit(n: Int, content: ByteArray): ByteArray = der(0xA0 or n, content)
    private fun integer(v: BigInteger): ByteArray = der(TAG_INTEGER, v.toByteArray())
    /** The leading byte counts the unused bits of the last byte; everything here is byte-aligned but keyUsage. */
    private fun bitString(bytes: ByteArray): ByteArray = der(TAG_BIT_STRING, byteArrayOf(0) + bytes)

    private fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return der(TAG_UTC_TIME, f.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val out = ArrayList<Byte>()
        out.add((parts[0] * 40 + parts[1]).toByte())
        for (p in parts.drop(2)) {
            val chunk = ArrayList<Byte>()
            var v = p
            chunk.add((v and 0x7F).toByte())
            v = v shr 7
            while (v > 0) {
                chunk.add(((v and 0x7F) or 0x80).toByte())
                v = v shr 7
            }
            out.addAll(chunk.reversed())
        }
        return der(TAG_OID, out.toByteArray())
    }
}
