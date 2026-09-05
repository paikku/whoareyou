package com.carcast.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class ServerCommandTest {
    @Test
    fun buildsTheSameCommandTheGuideUses() {
        val cmd = ServerCommand.build("/data/app/~~x==/com.carcast-y==/base.apk", "abc1234", 3333)
        assertEquals("CLASSPATH='/data/app/~~x==/com.carcast-y==/base.apk' exec app_process / com.carcast.server.Server abc1234 port=3333", cmd)
        assertEquals(
            "CLASSPATH='/a/base.apk' exec app_process / com.carcast.server.Server dev port=3333 daemon=true",
            ServerCommand.build("/a/base.apk", "dev", 3333, mapOf("daemon" to "true")),
        )
        assertEquals("adb shell 'CLASSPATH=\$(pm path com.carcast | cut -d: -f2) app_process / com.carcast.server.Server abc1234 port=3333'", ServerCommand.forPc("com.carcast", "abc1234", 3333))
    }

    @Test
    fun detachedFormRunsInItsOwnSessionWithDaemonFlag() {
        val cmd = ServerCommand.detached("/a/base.apk", "abc1234", 3333)
        assertEquals(
            "pkill -f '[c]om.carcast.server.Server' 2>/dev/null; sleep 1; mkdir -p /data/local/tmp/carcast; rm -f /data/local/tmp/carcast/server.log; " +
                "CLASSPATH='/a/base.apk' setsid nohup app_process / com.carcast.server.Server abc1234 port=3333 daemon=true >/data/local/tmp/carcast/server.log 2>&1 </dev/null & " +
                "echo \$! >/data/local/tmp/carcast/server.pid; sleep 2; echo launched pid=\$(cat /data/local/tmp/carcast/server.pid); head -c 4000 /data/local/tmp/carcast/server.log",
            cmd,
        )
        // The kill pattern must match a running server's command line but never the launching shell's own.
        val pattern = Regex("[c]om.carcast.server.Server")
        assertTrue(pattern.containsMatchIn("app_process / com.carcast.server.Server abc1234 port=3333 daemon=true"))
        assertTrue(!pattern.containsMatchIn("sh -c pkill -f '[c]om.carcast.server.Server' 2>/dev/null"))
    }

    @Test
    fun rejectsShellMetacharacters() {
        for (bad in listOf({ ServerCommand.build("/a'b.apk", "x", 1) }, { ServerCommand.build("/a.apk", "x;rm", 1) }, { ServerCommand.build("/a.apk", "x", 1, mapOf("daemon" to "true; id")) })) {
            assertTrue(runCatching(bad).exceptionOrNull() is IllegalArgumentException)
        }
    }
}

class ServerOutputTest {
    @Test
    fun parsesServerLines() {
        assertEquals(ServerOutput.Event.Started(2000, "75ff76a", "16"), ServerOutput.parse("carcast-server uid=2000 build=75ff76a android=16"))
        assertEquals(ServerOutput.Event.Ready(3333), ServerOutput.parse("carcast-server ready build=75ff76a port=3333"))
        assertEquals(ServerOutput.Event.Failed("build id mismatch, expected a got b"), ServerOutput.parse("carcast-server: build id mismatch, expected a got b"))
        assertEquals(ServerOutput.Event.Failed("java.net.BindException: Address already in use"), ServerOutput.parse("java.net.BindException: Address already in use"))
        assertEquals(ServerOutput.Event.Line("\tat java.net.Socket.bind"), ServerOutput.parse("\tat java.net.Socket.bind"))
        assertEquals(ServerOutput.Event.Line("HTTP 서버 시작"), ServerOutput.parse("HTTP 서버 시작"))
    }

    @Test
    fun assemblesLinesFromArbitraryChunks() {
        val lines = ArrayList<String>()
        val a = ServerOutput.LineAssembler { lines += it }
        a.feed("carcast-ser"); a.feed("ver ready\r\nsecond"); a.feed("\nthird")
        assertEquals(listOf("carcast-server ready", "second"), lines)
        a.flush()
        assertEquals(listOf("carcast-server ready", "second", "third"), lines)
    }
}

class LocalHostTest {
    @Test
    fun onlyOurOwnAddressesCount() {
        // hostAddress spells IPv6 out in full on both sides (NetworkInterface and NSD), so compare that form.
        val local = setOf("10.136.114.168", "192.0.0.2", "fe80:0:0:0:0:0:0:1")
        val linkLocalWithScope = java.net.Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 3)
        assertTrue(LocalHost.isLocal(InetAddress.getByName("127.0.0.1"), local))
        assertTrue(LocalHost.isLocal(InetAddress.getByName("10.136.114.168"), local))
        assertTrue(linkLocalWithScope.hostAddress, LocalHost.isLocal(linkLocalWithScope, local))
        assertFalse(LocalHost.isLocal(InetAddress.getByName("10.136.114.7"), local))
        assertFalse(LocalHost.isLocal(null, local))
    }
}
