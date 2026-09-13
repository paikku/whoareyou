package com.carcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A host route has to be able to be loopback-only, the way `/api/stop` already is.
 *
 * `/api/hotspot` is the reason: the car reaches the phone *over* the hotspot it would be switching off,
 * and so does anything else sharing that hotspot. The guard is one line in the shell server, but it can
 * only work if the caller's address reaches [StreamSession.extraApi] at all — and nothing else would fail
 * if it quietly stopped, which is exactly the kind of hole this pins shut.
 */
class ExtraApiRemoteTest {
    /** No APK and no assets: these requests never touch a file. */
    private object NoAssets : Assets {
        override fun open(path: String): InputStream? = null
    }

    /** StreamSession takes the port it is told to take, so pick one the kernel just said was free. */
    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test(timeout = 20_000)
    fun extraApiSeesTheCallerAddress() {
        val seen = CopyOnWriteArrayList<String>()
        val port = freePort()
        val session = StreamSession(NoAssets, port = port, process = "test")
        session.extraApi = { method, path, _, remote ->
            if (path == "/api/probe") {
                seen.add("$method $remote")
                Json.obj(mapOf("remote" to remote, "loopback" to remote.startsWith("127.")))
            } else {
                null
            }
        }
        session.start()
        try {
            val body = get("http://127.0.0.1:$port/api/probe")
            assertTrue(body, body.contains("\"loopback\":true"))
            assertEquals(1, seen.size)
            val line = seen[0]
            assertTrue("expected 'GET 127.0.0.1:<port>', got '$line'", line.startsWith("GET 127.0.0.1:"))
        } finally {
            session.stop()
        }
    }

    /** "Not mine" must still fall through to the core routes, or adding a host route would shadow them. */
    @Test(timeout = 20_000)
    fun nullFromExtraApiFallsThroughToTheCoreRoutes() {
        val port = freePort()
        val session = StreamSession(NoAssets, port = port, process = "test")
        session.extraApi = { _, _, _, _ -> null }
        session.start()
        try {
            assertTrue(get("http://127.0.0.1:$port/api/status").contains("\"running\":true"))
        } finally {
            session.stop()
        }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 5000
        return c.inputStream.use { String(it.readBytes()) }
    }
}
