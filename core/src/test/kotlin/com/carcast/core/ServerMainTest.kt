package com.carcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ServerMainTest {
    private fun fakeApk(): File {
        val f = Files.createTempFile("fake", ".apk").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("assets/web/index.html")); z.write("<html>hi</html>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(4)); z.closeEntry()
        }
        return f
    }

    @Test
    fun parsesArgumentsAndReadsApk() {
        val apk = fakeApk()
        val o = ServerMain.parse(arrayOf("abc1234", "port=4444", "apk=${apk.path}"))
        assertEquals(4444, o.port)
        assertEquals("abc1234", o.buildId)
        assertNotNull(o.assets.open("web/index.html"))
        assertNull(o.assets.open("web/missing.html"))
        assertNull(o.assets.open("../classes.dex"))
    }

    @Test
    fun sessionServesStaticAndStatus() {
        val port = ServerSocket(0).use { it.localPort }
        val session = StreamSession(ZipAssets(fakeApk().path), port, "test", extraStatus = { mapOf("vpn" to "UP") })
        session.start()
        try {
            val status = get("http://127.0.0.1:$port/api/status")
            assertTrue(status, status.contains("\"process\":\"test\"") && status.contains("\"vpn\":\"UP\"") && status.contains("\"source\":\"none\""))
            assertEquals("<html>hi</html>", get("http://127.0.0.1:$port/"))
            val c = URL("http://127.0.0.1:$port/nope").openConnection() as HttpURLConnection
            assertEquals(404, c.responseCode)
        } finally {
            session.stop()
        }
    }

    @Test
    fun acceptsDiagReportsAndListsThem() {
        val port = ServerSocket(0).use { it.localPort }
        val dir = Files.createTempDirectory("reports").toFile()
        val session = StreamSession(ZipAssets(fakeApk().path), port, "test", reportDir = dir)
        session.start()
        try {
            val bad = post("http://127.0.0.1:$port/api/report", "nope")
            assertTrue(bad, bad.contains("\"ok\":false"))
            val ok = post("http://127.0.0.1:$port/api/report", "{\"summary\":\"fps 29\",\"ua\":\"Tesla/2026.26\"}")
            assertTrue(ok, ok.startsWith("{\"ok\":true,\"id\":1,"))
            val list = get("http://127.0.0.1:$port/api/reports")
            assertTrue(list, list.contains("\"remote\":\"127.0.0.1:") && list.contains("\"report\":{\"summary\":\"fps 29\",\"ua\":\"Tesla/2026.26\"}"))
            val status = get("http://127.0.0.1:$port/api/status")
            assertTrue(status, status.contains("\"reports\":1") && status.contains("\"lastReport\":{\"id\":1,") && status.contains("\"summary\":\"fps 29\""))
            assertEquals(1, dir.listFiles()!!.size)
            val c = URL("http://127.0.0.1:$port/api/report").openConnection() as HttpURLConnection
            assertEquals(404, c.responseCode) // GET on the POST-only endpoint
            // Oversized body: refused from the headers alone, before any of it is read.
            java.net.Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 2000
                sock.getOutputStream().write(
                    "POST /api/report HTTP/1.1\r\nHost: x\r\nContent-Length: ${com.carcast.core.net.HttpServer.MAX_BODY + 1}\r\n\r\n".toByteArray()
                )
                val line = BufferedReader(InputStreamReader(sock.getInputStream())).readLine()
                assertEquals("HTTP/1.1 413 Payload Too Large", line)
            }
        } finally {
            session.stop()
        }
    }

    /**
     * The car must not download the web client on every visit: with a build sha the HTML is revalidated
     * (`no-cache` + ETag → 304), the sha is written into the page, and versioned assets are immutable.
     */
    @Test
    fun staticFilesAreCacheableByBuildVersion() {
        val port = ServerSocket(0).use { it.localPort }
        val f = Files.createTempFile("fake", ".apk").toFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("assets/web/index.html")); z.write("<script src=\"main.js?v=__BUILD__\"></script>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("assets/web/main.js")); z.write("//js".toByteArray()); z.closeEntry()
        }
        val session = StreamSession(ZipAssets(f.path), port, "test", staticVersion = "abc1234")
        session.start()
        try {
            val html = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            html.useCaches = false
            assertEquals(200, html.responseCode)
            assertEquals("<script src=\"main.js?v=abc1234\"></script>", html.inputStream.use { String(it.readBytes()) })
            assertEquals("\"abc1234\"", html.getHeaderField("ETag"))
            assertEquals("no-cache", html.getHeaderField("Cache-Control"))

            val again = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            again.useCaches = false
            again.setRequestProperty("If-None-Match", "\"abc1234\"")
            assertEquals(304, again.responseCode)

            val versioned = URL("http://127.0.0.1:$port/main.js?v=abc1234").openConnection() as HttpURLConnection
            assertEquals("max-age=31536000, immutable", versioned.getHeaderField("Cache-Control"))
            assertEquals("//js", versioned.inputStream.use { String(it.readBytes()) })
            // Another build's URL (an old page) still gets the file, but only with a revalidation.
            val stale = URL("http://127.0.0.1:$port/main.js?v=old").openConnection() as HttpURLConnection
            assertEquals("no-cache", stale.getHeaderField("Cache-Control"))
            assertEquals("\"abc1234\"", stale.getHeaderField("ETag"))
            // The API stays uncacheable.
            val api = URL("http://127.0.0.1:$port/api/status").openConnection() as HttpURLConnection
            assertEquals("no-store", api.getHeaderField("Cache-Control"))
        } finally {
            session.stop()
        }
    }

    @Test
    fun noVersionMeansNoCaching() {
        val port = ServerSocket(0).use { it.localPort }
        val session = StreamSession(ZipAssets(fakeApk().path), port, "test")
        session.start()
        try {
            val c = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            assertEquals("no-store", c.getHeaderField("Cache-Control"))
            assertNull(c.getHeaderField("ETag"))
        } finally {
            session.stop()
        }
    }

    @Test
    fun stopIsLoopbackOnlyAndLogIsServed() {
        val port = ServerSocket(0).use { it.localPort }
        val session = StreamSession(ZipAssets(fakeApk().path), port, "test")
        var stops = 0
        session.onStopRequest = { stops++ }
        session.start()
        try {
            val log = get("http://127.0.0.1:$port/api/log?limit=5")
            assertTrue(log, log.startsWith("[\"") && log.contains("HTTP"))
            assertEquals("{\"ok\":true}", post("http://127.0.0.1:$port/api/stop", ""))
            Thread.sleep(500)
            assertEquals(1, stops)
            // From a non-loopback address the same request must be refused. Use any non-loopback
            // local address if the host has one; otherwise the loopback path above is all we can check.
            val other = java.net.NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
            if (other != null) {
                val r = post("http://${other.hostAddress}:$port/api/stop", "")
                assertTrue(r, r.contains("loopback only"))
                assertEquals(1, stops)
            }
        } finally {
            session.stop()
        }
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        return BufferedReader(InputStreamReader(c.inputStream)).use { it.readText() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        return BufferedReader(InputStreamReader(c.inputStream)).use { it.readText() }
    }
}
