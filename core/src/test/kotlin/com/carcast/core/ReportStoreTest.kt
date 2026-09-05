package com.carcast.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReportStoreTest {
    @Test
    fun keepsReportsInMemoryAndRejectsNonJson() {
        val store = ReportStore()
        assertNull(store.add("not json", "1.2.3.4:5"))
        assertNull(store.add("{\"a\":1} trailing", "1.2.3.4:5"))
        val r = store.add(" {\"summary\":\"fps 29, lag 120ms\",\"ws\":{\"ok\":20}} ", "10.136.114.7:5000")!!
        assertEquals(1, r.id)
        assertEquals("fps 29, lag 120ms", r.summary)
        assertEquals("10.136.114.7:5000", r.remote)
        assertEquals(1, store.size)
        val json = store.listJson()
        assertTrue(json, json.startsWith("[{\"id\":1,\"receivedAt\":\"") && json.contains("\"report\":{\"summary\":\"fps 29, lag 120ms\",\"ws\":{\"ok\":20}}}]"))
    }

    @Test
    fun persistsToDirectoryAndReloads() {
        val dir = Files.createTempDirectory("reports").toFile()
        val a = ReportStore(dir)
        a.add("{\"summary\":\"first\"}", "r1")
        a.add("{\"summary\":\"second\",\"nested\":{\"x\":[1,2]}}", "r2")
        assertEquals(2, dir.listFiles()!!.size)

        val b = ReportStore(dir)
        assertEquals(2, b.size)
        assertEquals("second", b.last!!.summary)
        assertEquals("r2", b.last!!.remote)
        assertEquals("{\"summary\":\"second\",\"nested\":{\"x\":[1,2]}}", b.last!!.body)
        val c = b.add("{\"summary\":\"third\"}", "r3")!!
        assertEquals(3, c.id) // ids continue after the reloaded ones
        assertEquals(listOf("third", "second", "first"), b.list().map { it.summary })
    }

    @Test
    fun capsAtMax() {
        val store = ReportStore()
        repeat(ReportStore.MAX + 5) { store.add("{\"summary\":\"$it\"}", "r") }
        assertEquals(ReportStore.MAX, store.size)
        assertEquals("${ReportStore.MAX + 4}", store.last!!.summary)
    }
}
