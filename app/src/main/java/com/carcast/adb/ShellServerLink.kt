package com.carcast.adb

import android.content.Context
import com.carcast.BuildConfig
import com.carcast.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Keeps the shell-uid server running for as long as the session is active:
 * find adbd's port (mDNS or the user's manual port) → connect with our paired key → `id` →
 * run [ServerCommand] on a shell stream and relay its output → when it exits, retry with backoff.
 * Closing the stream ends the server (it exits on stdin EOF), which is the kill switch.
 */
class ShellServerLink(private val context: Context, private val log: (String) -> Unit) {
    enum class State { IDLE, FINDING_PORT, CONNECTING, NEEDS_PAIRING, STARTING, RUNNING, RETRYING, STOPPED }

    @Volatile var state = State.IDLE
        private set
    @Volatile var detail = ""
        private set
    @Volatile private var active = false
    private var thread: Thread? = null
    private var link: AdbLink? = null
    private var process: AdbLink.ShellProcess? = null
    private val wake = Object()

    fun start() {
        if (active) { synchronized(wake) { wake.notifyAll() }; return }
        active = true
        thread = Thread({ loop() }, "shell-link").apply { isDaemon = true; start() }
    }

    /** Also used after pairing: interrupts a wait and reconnects immediately. */
    fun reconnect() {
        if (!active) start() else synchronized(wake) { wake.notifyAll() }
    }

    fun stop() {
        active = false
        set(State.STOPPED, "")
        process?.close(); process = null
        link?.close(); link = null
        synchronized(wake) { wake.notifyAll() }
        thread?.interrupt(); thread = null
    }

    private fun set(s: State, d: String) { state = s; detail = d }

    private fun loop() {
        var delay = 5_000L
        while (active) {
            val outcome = runCatching { runOnce() }
            if (!active) break
            val reason = outcome.exceptionOrNull()
            when {
                reason is AdbLink.NotPairedException -> {
                    set(State.NEEDS_PAIRING, "이 앱의 키가 페어링되어 있지 않음")
                    log("adbd가 이 앱의 키를 거부함 — '무선 디버깅 페어링' 버튼으로 한 번 페어링하세요")
                    waitFor(Long.MAX_VALUE) // until reconnect() after pairing
                    delay = 5_000L
                    continue
                }
                reason != null -> { set(State.RETRYING, "${reason.message}"); log("shell 링크 실패: ${reason.message ?: reason}") }
                else -> set(State.RETRYING, "서버 종료")
            }
            waitFor(delay)
            delay = (delay * 2).coerceAtMost(60_000L)
        }
    }

    /** One connect → launch → wait-for-exit cycle. Returns normally when the server exits. */
    @Throws(IOException::class)
    private fun runOnce() {
        val port = findPort() ?: throw IOException("adbd 포트를 찾지 못함 — 무선 디버깅이 켜져 있나요? (수동 입력 가능)")
        set(State.CONNECTING, "127.0.0.1:$port")
        val l = AdbLink(port)
        link = l
        try {
            val id = l.whoAmI()
            log("adb 접속: $id")
            if (!id.contains("uid=2000")) log("경고: shell(2000)이 아닌 uid — 핫스팟 클라이언트가 100.99.9.9에 닿지 못할 수 있음")
            val cmd = ServerCommand.build(context.applicationInfo.sourceDir, BuildConfig.GIT_SHA, Config.HTTP_PORT)
            set(State.STARTING, cmd)
            val exited = CountDownLatch(1)
            var failed: String? = null
            val p = l.launch(cmd, { line, err ->
                when (val ev = ServerOutput.parse(line)) {
                    is ServerOutput.Event.Started -> { set(State.RUNNING, "uid=${ev.uid} build=${ev.build} android=${ev.android}"); log("서버 기동 uid=${ev.uid} build=${ev.build}") }
                    is ServerOutput.Event.Ready -> log("서버 준비: 포트 ${ev.port}")
                    is ServerOutput.Event.Failed -> { failed = ev.reason; log("서버 오류: ${ev.reason}") }
                    is ServerOutput.Event.Line -> if (err || ev.text.isNotBlank()) log("[server] ${ev.text}")
                }
            }, { code -> log("서버 프로세스 종료 (exit=${code ?: "?"})"); exited.countDown() })
            process = p
            while (active && !exited.await(1, TimeUnit.SECONDS)) { /* keep the stream open */ }
            if (active && failed != null) throw IOException(failed)
        } finally {
            process?.close(); process = null
            l.close(); if (link === l) link = null
        }
    }

    /** Manual port wins; otherwise the first `_adb-tls-connect` record for one of our own addresses. */
    private fun findPort(): Int? {
        val manual = AdbPrefs(context).manualConnectPort
        if (manual > 0) { set(State.FINDING_PORT, "수동 포트 $manual"); return manual }
        set(State.FINDING_PORT, "mDNS $ADB_CONNECT_WAIT_S s")
        val found = CountDownLatch(1)
        var port = 0
        val mdns = AdbMdns(context, AdbMdns.CONNECT) { p -> port = p; found.countDown() }
        mdns.start()
        try { found.await(ADB_CONNECT_WAIT_S, TimeUnit.SECONDS) } finally { mdns.stop() }
        return port.takeIf { it > 0 }
    }

    private fun waitFor(ms: Long) {
        synchronized(wake) { runCatching { if (ms == Long.MAX_VALUE) wake.wait() else wake.wait(ms) } }
    }

    companion object {
        private const val ADB_CONNECT_WAIT_S = 15L
    }
}
