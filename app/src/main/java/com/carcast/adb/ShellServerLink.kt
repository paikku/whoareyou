package com.carcast.adb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.carcast.BuildConfig
import com.carcast.Config
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Makes sure the shell-uid server is running while the session is active.
 *
 * Wireless debugging only works while the phone is a Wi-Fi *client* (Android turns it off with Wi-Fi),
 * so on that path alone there is no adb in the car — mobile data + hotspot — and a server that dies
 * there cannot be brought back. So the first time the app gets in over wireless debugging it switches
 * adbd to **TCP mode** ([AdbLink.tcpip]), whose port is not gated on Wi-Fi and stays open while the
 * phone is a hotspot. From then on [openLink] reaches shell over loopback anywhere, the server can be
 * relaunched in the car, and adbd staying alive also means init never SIGKILLs its cgroup — which is
 * what used to require leaving the USB debugging toggle on. See docs/hotspot-only.md.
 *
 * The server is still started **detached** (`setsid nohup … daemon=true`, see [ServerCommand.detached])
 * so it outlives the adb stream and this app until reboot or `POST /api/stop` from loopback
 * ([stopServer], the kill switch). This class polls /api/status and only touches adb when the server
 * is not answering.
 */
class ShellServerLink(private val context: Context, private val log: (String) -> Unit) {
    enum class State { IDLE, SERVER_UP, FINDING_PORT, CONNECTING, TCP_MODE, NEEDS_PAIRING, NO_WIFI, ADB_WIFI_OFF, STARTING, RETRYING, STOPPED }

    private val prefs = AdbPrefs(context)

    @Volatile var state = State.IDLE
        private set
    @Volatile var detail = ""
        private set
    @Volatile private var active = false
    private var thread: Thread? = null
    private val wake = Object()

    fun start() {
        if (active) { synchronized(wake) { wake.notifyAll() }; return }
        active = true
        thread = Thread({ loop() }, "shell-link").apply { isDaemon = true; start() }
    }

    /** After pairing or a manual port: retry now instead of waiting out the backoff. */
    fun reconnect() {
        if (!active) start() else synchronized(wake) { wake.notifyAll() }
    }

    /** Stops watching. Does NOT stop the server — that is a separate, explicit action ([stopServer]). */
    fun stop() {
        active = false
        set(State.STOPPED, "")
        synchronized(wake) { wake.notifyAll() }
        thread?.interrupt(); thread = null
    }

    private fun set(s: State, d: String) { state = s; detail = d }

    private fun loop() {
        var delay = 5_000L
        var wasUp = false
        var staleWarned = false
        var adbOffWarned = false
        while (active) {
            val status = serverStatus()
            var replaceStale = false
            if (status != null) {
                val build = Regex("\"build\":\"([^\"]*)\"").find(status)?.groupValues?.get(1)
                if (build != null && build != BuildConfig.GIT_SHA) {
                    // The APK was updated but the detached server still runs the old dex. Replace it ONLY once we
                    // can actually connect and launch the new one — never kill a working server on spec.
                    if (canTryAdb()) {
                        if (!staleWarned) { staleWarned = true; log("이전 빌드($build)의 서버가 실행 중 — 이 빌드(${BuildConfig.GIT_SHA})로 교체를 시도합니다 (성공할 때까지 기존 서버는 유지)") }
                        replaceStale = true
                        // fall through to the launch path; the old server keeps running until the new one connects
                    } else {
                        if (!staleWarned) { staleWarned = true; log("이전 빌드($build)의 서버가 실행 중 — 잘 돌면 그대로 써도 됩니다. 교체하려면 Wi-Fi + 무선 디버깅") }
                        set(State.SERVER_UP, "이전 빌드 $build (교체하려면 Wi-Fi)")
                        wasUp = false
                        waitFor(5_000L); delay = 5_000L
                        continue
                    }
                } else {
                    if (!wasUp) log("shell 서버 응답 중 — adb는 쓰지 않음")
                    wasUp = true
                    set(State.SERVER_UP, "")
                    waitFor(5_000L); delay = 5_000L
                    continue
                }
            }
            if (wasUp) {
                // It answered a moment ago and is gone without our /api/stop. init kills adbd's entire cgroup when
                // adbd stops, and adbd stops when wireless debugging goes off (Wi-Fi dropped) with USB debugging off
                // too — the whole process is SIGKILLed, so nothing reaches the log. TCP mode keeps adbd alive, which
                // both prevents that and lets us relaunch right here without Wi-Fi.
                log(if (tcpModeReachable()) "서버가 사라짐 — TCP 모드로 adbd에 붙어 바로 다시 띄웁니다"
                    else "서버가 사라짐 — 무선 디버깅이 꺼지며 adbd가 종료되면 adbd가 띄운 프로세스는 cgroup째 SIGKILL됩니다(setsid/nohup으로 못 막음). " +
                        "TCP 모드가 켜져 있으면 이 일이 없고 차에서도 복구됩니다; 그전까지는 개발자 옵션에서 USB 디버깅을 켜 두세요")
            }
            wasUp = false
            // Wi-Fi is only needed when TCP mode is not up yet; once it is, adbd answers on loopback anywhere.
            if (!tcpModeReachable()) {
                if (!onWifi()) {
                    set(State.NO_WIFI, "Wi-Fi 미연결: 무선 디버깅 불가")
                    if (delay == 5_000L) log("서버가 없고 Wi-Fi도 아님 — Wi-Fi에 연결된 곳에서 '시작'을 한 번 누르면 앱이 adbd를 TCP 모드로 바꿔 다음부터는 Wi-Fi 없이 됩니다 (또는 PC 명령)")
                    waitFor(delay); delay = (delay * 2).coerceAtMost(60_000L)
                    continue
                }
                if (!adbWifiEnabled()) {
                    // Android switches wireless debugging off whenever Wi-Fi drops; the toggle stays off until the user flips it.
                    set(State.ADB_WIFI_OFF, "무선 디버깅 꺼짐 — 개발자 옵션에서 켜세요")
                    if (!adbOffWarned) { adbOffWarned = true; log("무선 디버깅이 꺼져 있음 (Wi-Fi가 끊길 때 자동으로 꺼짐) — '무선 디버깅 설정' 버튼으로 열어 켜면 바로 이어집니다") }
                    waitFor(3_000L); delay = 5_000L
                    continue
                }
            }
            adbOffWarned = false
            val outcome = runCatching { launchOnce(replaceStale) }
            if (!active) break
            val reason = outcome.exceptionOrNull()
            when {
                reason is AdbLink.NotPairedException -> {
                    set(State.NEEDS_PAIRING, "이 앱의 키가 페어링되어 있지 않음")
                    log("adbd가 이 앱의 키를 거부함 (지문 ${AdbIdentity.fingerprint()?.take(16)}…) — '무선 디버깅 페어링'으로 다시 페어링하세요. 설정의 '페어링된 기기'에 CarCast가 없으면 폰이 지운 것")
                    waitFor(Long.MAX_VALUE) // until reconnect() after pairing
                    delay = 5_000L
                    continue
                }
                reason != null -> { set(State.RETRYING, "${reason.message}"); log("서버 기동 실패: ${reason.message ?: reason}"); waitFor(delay); delay = (delay * 2).coerceAtMost(60_000L) }
                else -> delay = 5_000L
            }
        }
    }

    /** connect (TCP mode, else wireless debugging) → detached launch → wait until /api/status answers. */
    @Throws(IOException::class)
    private fun launchOnce(replaceStale: Boolean = false) {
        val logFile = "$SERVER_DIR/server-${System.currentTimeMillis() / 1000}.log"
        var port = 0
        openLink().use { l ->
            port = l.port
            // Only now that adb is confirmed working do we retire an old-build server (kill switch), then relaunch.
            if (replaceStale) {
                log("이전 빌드 서버 종료 → 이 빌드로 교체")
                stopServer()
                for (i in 1..12) { if (!serverUp()) break; Thread.sleep(300) }
            }
            // One log file per launch, named by this attempt, so what we read back can only be this attempt's output.
            val cmd = ServerCommand.detached(context.applicationInfo.sourceDir, BuildConfig.GIT_SHA, Config.HTTP_PORT, logFile)
            set(State.STARTING, cmd)
            val out = l.shell(cmd).trim()
            log("서버 분리 실행: " + out.ifEmpty { "(출력 없음 — 실행 명령이 돌지 않음)" })
        }
        for (i in 1..20) {
            if (!active) return
            if (serverUp()) { log("서버 기동 확인 (${i * 500}ms)"); return }
            Thread.sleep(500)
        }
        val tail = runCatching { AdbLink(port).use { it.shell("tail -n 60 $logFile 2>&1") } }.getOrNull()?.trim().orEmpty()
        val detail = when {
            tail.isEmpty() || tail.contains("No such file") -> "(이번 실행의 로그 파일이 없음 — 서버 프로세스가 시작조차 안 됨)"
            !tail.contains("build=${BuildConfig.GIT_SHA}") -> "(이번 빌드 ${BuildConfig.GIT_SHA}의 시작 줄이 없음)\n$tail"
            else -> tail
        }
        throw IOException("서버가 10초 안에 응답하지 않음 — 서버 로그 $logFile:\n$detail")
    }

    private data class Candidate(val port: Int, val why: String)

    /**
     * Every port worth dialling, best first. adbd picks a new port on every wireless-debugging toggle and
     * reboot, and mDNS can still carry an old record (or another phone's), so one guess is not enough — the
     * whole list is tried and logged, and the log names what each port was.
     */
    private fun candidates(): List<Candidate> {
        val out = LinkedHashMap<Int, String>()
        // Only when the user opted in: otherwise a port left over from an earlier attempt would probe and log
        // every round, saying "closed" about a mode nobody asked for.
        val tcp = if (prefs.tcpModeOptIn) prefs.tcpPort else 0
        if (tcp > 0) {
            val open = portOpen(tcp)
            log("TCP 모드 포트 $tcp: " + if (open) "열려 있음" else "닫힘 (adbd가 TCP 모드가 아님)")
            if (open) out[tcp] = "TCP 모드"
        }
        val manual = prefs.manualConnectPort
        if (manual > 0) out.putIfAbsent(manual, "수동 입력")
        set(State.FINDING_PORT, "mDNS ${ADB_CONNECT_WAIT_S}s")
        val found = mdnsPorts()
        if (found.isEmpty()) log("mDNS _adb-tls-connect 레코드 없음 — 무선 디버깅이 꺼져 있거나 아직 광고 전입니다")
        for ((port, host, local) in found) {
            log("mDNS _adb-tls-connect: ${host ?: "주소 없음"}:$port${if (local) " (이 폰)" else " (다른 기기)"}")
            out.putIfAbsent(port, if (local) "mDNS" else "mDNS(외부)")
        }
        return out.map { Candidate(it.key, it.value) }
    }

    /** Collects every advertised connect port for the window, ours first; stops early once one of ours shows up. */
    private fun mdnsPorts(): List<Triple<Int, String?, Boolean>> {
        val found = java.util.concurrent.CopyOnWriteArrayList<Triple<Int, String?, Boolean>>()
        val ours = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.CONNECT, requireLocal = false) { port, host, local ->
            found.add(Triple(port, host, local))
            if (local) ours.countDown()
        }
        mdns.start()
        try { ours.await(ADB_CONNECT_WAIT_S, TimeUnit.SECONDS) } finally { mdns.stop() }
        return found.sortedByDescending { it.third }
    }

    /**
     * A connected adb link, shell confirmed: dials [candidates] in turn until one answers `id`. Being unpaired is
     * reported ahead of connection failures, because that is the one thing the user can act on directly.
     */
    @Throws(IOException::class)
    private fun openLink(): AdbLink {
        val tried = StringBuilder()
        var notPaired: AdbLink.NotPairedException? = null
        for (c in candidates()) {
            if (!active) throw IOException("중지됨")
            set(State.CONNECTING, "${c.why} 127.0.0.1:${c.port}")
            val l = AdbLink(c.port, connectTimeoutMs = CONNECT_TIMEOUT_MS)
            val id = runCatching { l.whoAmI() }
            id.getOrNull()?.let {
                if (c.port == prefs.manualConnectPort) prefs.manualPortFailures = 0
                checkShell(it, c.why)
                return maybeSwitchToTcpMode(l, c)
            }
            l.close()
            val e = id.exceptionOrNull()
            if (e is AdbLink.NotPairedException) notPaired = e
            tried.append("\n  · ${c.port} (${c.why}): ${e?.message?.take(80)}")
        }
        notPaired?.let { throw it }
        dropManualPortIfHopeless()
        throw IOException(
            if (tried.isEmpty()) "adbd 접속 포트를 찾지 못함 — 개발자 옵션에서 무선 디버깅을 켜세요"
            else "adbd에 붙지 못함. 시도한 포트:$tried\n무선 디버깅은 껐다 켤 때마다 포트가 바뀝니다 — 토글을 껐다 켜고 다시 시도해 보세요"
        )
    }

    /**
     * TCP mode is only entered when the user asked for it ([AdbPrefs.tcpModeOptIn]): the switch restarts adbd,
     * so if adbd does not come back serving wireless debugging, the only way in is gone until the user toggles it.
     */
    @Throws(IOException::class)
    private fun maybeSwitchToTcpMode(link: AdbLink, via: Candidate): AdbLink {
        if (via.why == "TCP 모드") return link
        if (!prefs.tcpModeOptIn) return link
        if (prefs.tcpModeFailures >= TCP_MODE_MAX_TRIES) return link
        return switchToTcpMode(link)
    }

    /** A manual port that keeps refusing is worse than none: it hides mDNS. Drop it after a few rounds. */
    private fun dropManualPortIfHopeless() {
        val manual = prefs.manualConnectPort
        if (manual <= 0) return
        prefs.manualPortFailures++
        if (prefs.manualPortFailures >= MANUAL_PORT_MAX_FAILURES) {
            prefs.manualConnectPort = 0
            prefs.manualPortFailures = 0
            log("수동 포트 $manual 이 계속 거부됨 — 지우고 자동(mDNS) 탐색으로 되돌립니다")
        }
    }

    private fun checkShell(id: String, how: String) {
        log("adb 접속($how): $id")
        if (!id.contains("uid=2000")) log("경고: shell(2000)이 아닌 uid — 핫스팟 클라이언트가 100.99.9.9에 닿지 못할 수 있음")
    }

    /**
     * Switches adbd to TCP mode and comes back on the new port. adbd re-executes itself here, so [tls] and
     * everything adbd had started die — which is why this runs before the server is launched, never after,
     * and why a failure costs this round's working link. After [TCP_MODE_MAX_TRIES] failures we stop trying
     * and stay on wireless debugging rather than breaking it every time.
     */
    @Throws(IOException::class)
    private fun switchToTcpMode(tls: AdbLink): AdbLink {
        val port = prefs.tcpPort.takeIf { it > 0 } ?: newTcpPort().also { prefs.tcpPort = it }
        set(State.TCP_MODE, "127.0.0.1:$port")
        log("adbd를 TCP 모드로 전환합니다 (포트 $port) — 성공하면 이후에는 Wi-Fi도 무선 디버깅 토글도 필요 없고, 차에서도 서버를 다시 띄울 수 있습니다. " +
            "실패하면 무선 디버깅을 껐다 켜야 할 수 있습니다")
        val reply = try { tls.use { it.tcpip(port) } } catch (e: Exception) {
            prefs.tcpModeFailures++
            throw IOException("TCP 모드 전환 요청 실패: ${e.message}", e)
        }
        log("adbd 응답: " + reply.ifEmpty { "(없음 — 응답 전에 재시작함)" })
        var last: Throwable? = null
        for (i in 1..TCP_MODE_WAIT_S) {
            if (!active) throw IOException("중지됨")
            Thread.sleep(1000)
            val l = AdbLink(port)
            val id = runCatching { l.whoAmI() }
            id.getOrNull()?.let {
                prefs.tcpModeFailures = 0
                checkShell(it, "TCP 모드 ${i}초 만에 전환 완료")
                persistTcpPort(l, port)
                return l
            }
            last = id.exceptionOrNull()
            l.close()
        }
        prefs.tcpModeFailures++
        throw IOException(
            "TCP 모드 포트 ${port}에 붙지 못함 (${last?.message}) — 폰에 'USB 디버깅을 허용하시겠습니까?'가 떴다면 '항상 허용'으로 수락하세요. " +
                "adbd가 재시작되며 무선 디버깅이 꺼졌을 수 있으니 개발자 옵션에서 다시 켜면 이어집니다 (${prefs.tcpModeFailures}/${TCP_MODE_MAX_TRIES}회 실패)",
            last,
        )
    }

    /**
     * Experiment from docs/hotspot-only.md §3: if shell may set this property, adbd opens the port by itself
     * on every boot and Wi-Fi is never needed again, not even after a reboot. Recent One UI is expected to
     * refuse it; it costs one command, and either way the next boot answers the question — the loop tries
     * the TCP port before it asks for Wi-Fi.
     */
    private fun persistTcpPort(l: AdbLink, port: Int) {
        val out = runCatching { l.shell("setprop persist.adb.tcp.port $port 2>&1; getprop persist.adb.tcp.port").trim() }
            .getOrElse { "명령 실패: ${it.message}" }
        if (out.lines().lastOrNull()?.trim() == port.toString()) {
            log("persist.adb.tcp.port=$port 설정됨 — 재부팅 후에도 Wi-Fi 없이 붙는지 확인해 주세요 (되면 Wi-Fi 요구가 완전히 사라집니다)")
        } else {
            log("persist.adb.tcp.port 설정 불가 ($out) — 재부팅하면 Wi-Fi에서 '시작'을 한 번 눌러야 합니다")
        }
    }

    /** adbd binds the TCP port on every interface, so avoid 5555: a random high port is one less thing on the hotspot to find. */
    private fun newTcpPort(): Int = 30_000 + java.security.SecureRandom().nextInt(15_000)

    /** Cheap gate: is anything listening there? A full adb handshake is far too slow for the poll loop. */
    private fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), PORT_PROBE_MS) }
        true
    }.getOrDefault(false)

    private fun tcpModeReachable(): Boolean = prefs.tcpModeOptIn && prefs.tcpPort > 0 && portOpen(prefs.tcpPort)

    /** The /api/status body when a server answers on loopback, else null. */
    private fun serverStatus(): String? = try {
        val c = URL("http://127.0.0.1:${Config.HTTP_PORT}/api/status").openConnection() as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 1000
        c.inputStream.use { String(it.readBytes()) }.takeIf { it.contains("\"running\":true") }
    } catch (_: Exception) { null }

    private fun serverUp(): Boolean = serverStatus() != null

    /** Settings.Global.adb_wifi_enabled is world-readable; 0 means the Wireless debugging toggle is off. */
    private fun adbWifiEnabled(): Boolean =
        runCatching { android.provider.Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(true)

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    private fun canTryAdb(): Boolean = tcpModeReachable() || (onWifi() && adbWifiEnabled())

    private fun waitFor(ms: Long) {
        synchronized(wake) { runCatching { if (ms == Long.MAX_VALUE) wake.wait() else wake.wait(ms) } }
    }

    companion object {
        private const val ADB_CONNECT_WAIT_S = 15L
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val PORT_PROBE_MS = 300
        private const val MANUAL_PORT_MAX_FAILURES = 3
        private const val TCP_MODE_WAIT_S = 20
        private const val TCP_MODE_MAX_TRIES = 2
        private const val SERVER_DIR = "/data/local/tmp/carcast"

        /** Kill switch: asks the server (whoever started it) to exit. Loopback only, so only this phone can. */
        fun stopServer(): String = try {
            val c = URL("http://127.0.0.1:${Config.HTTP_PORT}/api/stop").openConnection() as HttpURLConnection
            c.connectTimeout = 1000; c.readTimeout = 2000; c.requestMethod = "POST"; c.doOutput = true
            c.outputStream.close()
            c.inputStream.use { String(it.readBytes()) }
        } catch (e: Exception) { "서버 응답 없음: $e" }
    }
}
