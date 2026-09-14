package com.carcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The host process adds its own /api routes (the shell server's /api/screen, /api/apps, /api/hotspot …) and
 * returns null for anything it does not recognise. Both halves matter and neither is obvious from the call
 * site: a host route that shadowed a core one would break /api/status for the car, and a host that stopped
 * being consulted would take the car's screen and app controls with it.
 */
class ExtraApiTest {
    /** No APK and no assets: these requests never touch a file. */
    private object NoAssets : Assets {
        override fun open(path: String): InputStream? = null
    }

    /** StreamSession takes the port it is told to take, so pick one the kernel just said was free. */
    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    @Test(timeout = 20_000)
    fun theHostSeesTheRequestAndItsAnswerIsServed() {
        val seen = CopyOnWriteArrayList<String>()
        val port = freePort()
        val session = StreamSession(NoAssets, port = port, process = "test")
        session.extraApi = { method, path, query ->
            if (path == "/api/probe") {
                seen.add("$method ${query["x"]}")
                Json.obj(mapOf("mine" to true))
            } else {
                null
            }
        }
        session.start()
        try {
            assertTrue(get("http://127.0.0.1:$port/api/probe?x=7").contains("\"mine\":true"))
            assertEquals(listOf("GET 7"), seen.toList())
        } finally {
            session.stop()
        }
    }

    /** "Not mine" must still fall through to the core routes, or adding a host route would shadow them. */
    @Test(timeout = 20_000)
    fun nullFromTheHostFallsThroughToTheCoreRoutes() {
        val port = freePort()
        val session = StreamSession(NoAssets, port = port, process = "test")
        session.extraApi = { _, _, _ -> null }
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
