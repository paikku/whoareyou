package com.carcast.net

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Listening socket built on android.system.Os instead of java.net.ServerSocket, because we need
 * the raw fd: VpnService.protect(fd) marks the listener "protected from VPN", and the kernel
 * copies the listener's mark onto every SYN-ACK and accepted child socket (tcp_fwmark_accept).
 * java.net.ServerSocket hides its fd behind non-SDK APIs.
 */
class TcpListener(port: Int, backlog: Int = 16) : Closeable {
    val fd: FileDescriptor
    @Volatile var isClosed = false
        private set

    init {
        val s = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
        } catch (e: ErrnoException) { throw IOException("socket: ${e.message}", e) }
        try {
            Os.setsockoptInt(s, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
            Os.bind(s, Inet4Address.getByName("0.0.0.0"), port)
            Os.listen(s, backlog)
        } catch (e: ErrnoException) {
            try { Os.close(s) } catch (_: ErrnoException) {}
            throw IOException("bind :$port: ${e.message}", e)
        }
        fd = s
    }

    @Throws(IOException::class)
    fun accept(): TcpConn {
        while (true) {
            try {
                return TcpConn(Os.accept(fd, null))
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw IOException("accept: ${e.message}", e)
            }
        }
    }

    /** Runs [block] with a duplicate int fd of the listener (VpnService.protect takes an int). */
    fun <T> withRawFd(block: (Int) -> T): T = ParcelFileDescriptor.dup(fd).use { block(it.fd) }

    override fun close() {
        if (isClosed) return
        isClosed = true
        // shutdown() wakes a thread blocked in accept(); close() alone does not.
        try { Os.shutdown(fd, OsConstants.SHUT_RDWR) } catch (_: ErrnoException) {}
        try { Os.close(fd) } catch (_: ErrnoException) {}
    }
}

/** One accepted connection: blocking streams over the raw fd with an optional read timeout. */
class TcpConn(private val fd: FileDescriptor) : Closeable {
    @Volatile var isClosed = false
        private set

    /** 0 = block forever. Applies to every read on [input]. */
    @Volatile var readTimeoutMs = 0

    val remote: String = describe { Os.getpeername(fd) }
    val local: String = describe { Os.getsockname(fd) }

    fun setTcpNoDelay(on: Boolean) {
        try { Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, if (on) 1 else 0) } catch (_: ErrnoException) {}
    }

    val input: InputStream = object : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int {
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                try {
                    val t = readTimeoutMs
                    if (t > 0) {
                        val p = StructPollfd().also { it.fd = fd; it.events = OsConstants.POLLIN.toShort() }
                        if (Os.poll(arrayOf(p), t) == 0) throw SocketTimeoutException("read timed out after $t ms")
                    }
                    val n = Os.read(fd, b, off, len)
                    return if (n <= 0) -1 else n
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw IOException("read: ${e.message}", e)
                }
            }
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                try {
                    val n = Os.write(fd, b, o, l)
                    o += n; l -= n
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw IOException("write: ${e.message}", e)
                }
            }
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        try { Os.shutdown(fd, OsConstants.SHUT_RDWR) } catch (_: ErrnoException) {}
        try { Os.close(fd) } catch (_: ErrnoException) {}
    }

    private fun describe(get: () -> java.net.SocketAddress?): String = try {
        val a = get()
        if (a is InetSocketAddress) "${a.address.hostAddress}:${a.port}" else a.toString()
    } catch (_: Exception) { "?" }
}
