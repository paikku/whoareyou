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

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2000; c.readTimeout = 2000
        return BufferedReader(InputStreamReader(c.inputStream)).use { it.readText() }
    }
}
