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

class JsonObjectCheckTest {
    @Test
    fun acceptsCompleteObjectsOnly() {
        org.junit.Assert.assertTrue(Json.isObject("{}"))
        org.junit.Assert.assertTrue(Json.isObject("{\"a\":[1,{\"b\":\"}\"}],\"c\":\"\\\"\"}"))
        org.junit.Assert.assertFalse(Json.isObject("{\"a\":1}}"))
        org.junit.Assert.assertFalse(Json.isObject("{\"a\":1} x"))
        org.junit.Assert.assertFalse(Json.isObject("{\"a\":1"))
        org.junit.Assert.assertFalse(Json.isObject("[1]"))
        org.junit.Assert.assertFalse(Json.isObject("{\"a\":\"line\nbreak\"}"))
    }

    @Test
    fun unescapeRoundTrip() {
        val original = "fps 29 \"lag\" 120ms\n한글 \\ tab\t"
        val quoted = Json.str(original)
        assertEquals(original, Json.unescape(quoted.substring(1, quoted.length - 1)))
    }
}
