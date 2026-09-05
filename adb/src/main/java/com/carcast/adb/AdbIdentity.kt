package com.carcast.adb

import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import okio.Path.Companion.toOkioPath
import java.io.File

/**
 * The app's own ADB host key (RSA-2048 + self-signed certificate), kept in the app's private files.
 * Pairing once registers this key with adbd; afterwards `connect` succeeds without any prompt until
 * the user revokes it in Developer options. Call [init] before any pairing or connection.
 */
object AdbIdentity {
    private const val DEVICE_NAME = "CarCast"

    fun init(filesDir: File) {
        val key = File(filesDir, "adb/adbkey.pem")
        KadbCert.configure(
            store = OkioFilePrivateKeyStore(key.toOkioPath()),
            policy = KadbCertPolicy(subject = KadbCertPolicy.Subject(cn = DEVICE_NAME, o = "CarCast")),
        )
    }

    /** SHA-256 of the certificate, as Developer options shows under "Paired devices"; null until generated. */
    fun fingerprint(): String? = runCatching { KadbCert.ensureReady().fingerprintSha256 }.getOrNull()

    /** Forget the key: adbd will require a new pairing. */
    fun reset() = KadbCert.clear()

    val deviceName: String get() = DEVICE_NAME
}
