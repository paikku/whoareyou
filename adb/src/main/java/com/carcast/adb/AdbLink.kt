package com.carcast.adb

import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.exception.AdbAuthException
import com.flyfishxu.kadb.exception.AdbPairAuthException
import com.flyfishxu.kadb.shell.AdbShellPacket
import com.flyfishxu.kadb.shell.AdbShellStream
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * A connection to this phone's own adbd over wireless debugging (always 127.0.0.1, the port comes
 * from mDNS or the user). Everything here blocks; call from a background thread.
 */
class AdbLink(val port: Int, val host: String = "127.0.0.1") : AutoCloseable {
    private val kadb = Kadb.create(host, port, connectTimeout = 5000, socketTimeout = 0)

    class NotPairedException(cause: Throwable) : IOException("이 앱의 키가 페어링되어 있지 않음", cause)

    /** Output of `id`, e.g. `uid=2000(shell) gid=2000(shell) ...`; [NotPairedException] when adbd rejects our key. */
    @Throws(IOException::class)
    fun whoAmI(): String = try {
        kadb.shell("id").allOutput.trim()
    } catch (e: AdbAuthException) { throw NotPairedException(e) }
      catch (e: AdbPairAuthException) { throw NotPairedException(e) }
      catch (e: Exception) {
          // adbd closes the TLS handshake with CERTIFICATE_UNKNOWN when our key is not in its paired list.
          val msg = (e.message ?: "") + (e.cause?.message ?: "")
          if (msg.contains("CERTIFICATE_UNKNOWN") || msg.contains("certificate", ignoreCase = true)) throw NotPairedException(e)
          throw e
      }

    @Throws(IOException::class)
    fun shell(command: String): String = kadb.shell(command).allOutput

    /**
     * Runs [command] and streams its stdout/stderr lines to [onLine] on a daemon thread until the
     * command exits or [ShellProcess.close] is called. Closing the stream is the kill switch: the
     * server reads stdin and exits on EOF (see core ServerMain).
     */
    @Throws(IOException::class)
    fun launch(command: String, onLine: (String, Boolean) -> Unit, onExit: (Int?) -> Unit): ShellProcess {
        val stream = kadb.openShell(command)
        return ShellProcess(stream, onLine, onExit).also { it.start() }
    }

    override fun close() { runCatching { kadb.close() } }

    class ShellProcess internal constructor(
        private val stream: AdbShellStream,
        private val onLine: (String, Boolean) -> Unit,
        private val onExit: (Int?) -> Unit,
    ) : AutoCloseable {
        @Volatile var closed = false
            private set
        private val out = ServerOutput.LineAssembler { onLine(it, false) }
        private val err = ServerOutput.LineAssembler { onLine(it, true) }

        internal fun start() {
            Thread({
                var exit: Int? = null
                try {
                    while (!closed) {
                        when (val p = stream.read()) {
                            is AdbShellPacket.StdOut -> out.feed(String(p.payload))
                            is AdbShellPacket.StdError -> err.feed(String(p.payload))
                            is AdbShellPacket.Exit -> { exit = p.payload[0].toInt() and 0xff; break }
                        }
                    }
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "shell stream ended: $e")
                } finally {
                    out.flush(); err.flush()
                    closed = true
                    runCatching { stream.close() }
                    onExit(exit)
                }
            }, "adb-shell").apply { isDaemon = true }.start()
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { stream.close() }
        }
    }

    companion object {
        private const val TAG = "AdbLink"

        /**
         * Pairs our key with adbd using the 6-digit code from the "Pair device with pairing code"
         * dialog. Throws on a wrong code / closed dialog.
         */
        @Throws(IOException::class)
        fun pair(port: Int, code: String, host: String = "127.0.0.1") {
            val digits = code.filter { it.isDigit() }
            require(digits.length == 6) { "페어링 코드는 숫자 6자리" }
            runBlocking { Kadb.pair(host, port, digits, AdbIdentity.deviceName) }
        }
    }
}
