package com.carcast.core

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test
    fun flatObject() {
        val s = Json.obj(linkedMapOf("type" to "status", "running" to true, "port" to 3333, "n" to null, "q" to "a\"b\n"))
        assertEquals("{\"type\":\"status\",\"running\":true,\"port\":3333,\"n\":null,\"q\":\"a\\\"b\\n\"}", s)
    }

    @Test
    fun nested() {
        assertEquals("{\"a\":[1,2],\"b\":{\"c\":\"d\"}}", Json.obj(linkedMapOf("a" to listOf(1, 2), "b" to mapOf("c" to "d"))))
    }
}
