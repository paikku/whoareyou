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
 * Wireless debugging only works while the phone is a Wi-Fi *client* (Android turns it off with
 * Wi-Fi), so in the car — mobile data + hotspot — adb is unavailable. Therefore the server is
 * started **detached** (`setsid nohup … daemon=true`, see [ServerCommand.detached]) whenever adb is
 * reachable (at home on Wi-Fi), and it then outlives the adb stream, wireless debugging and this
 * app until reboot or `POST /api/stop` from loopback ([stopServer], the kill switch).
 * This class polls /api/status and only touches adb when the server is not answering.
 */
class ShellServerLink(private val context: Context, private val log: (String) -> Unit) {
    enum class State { IDLE, SERVER_UP, FINDING_PORT, CONNECTING, NEEDS_PAIRING, NO_WIFI, ADB_WIFI_OFF, STARTING, RETRYING, STOPPED }

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

    /** Stops watching. Does NOT stop the server: restarting it needs Wi-Fi, so that is a separate, explicit action. */
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
            wasUp = false
            if (!onWifi()) {
                set(State.NO_WIFI, "Wi-Fi 미연결: 무선 디버깅 불가")
                if (delay == 5_000L) log("서버가 없고 Wi-Fi도 아님 — Wi-Fi에 연결된 곳에서 '시작'을 누르면 앱이 서버를 띄웁니다 (또는 PC 명령)")
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

    /** find port → connect → `id` → detached launch → wait until /api/status answers. */
    @Throws(IOException::class)
    private fun launchOnce(replaceStale: Boolean = false) {
        val port = findPort() ?: throw IOException("adbd 접속 포트를 찾지 못함 — 무선 디버깅 화면의 'IP 주소 및 포트'의 포트를 '포트 수동…'에 넣으세요")
        set(State.CONNECTING, "127.0.0.1:$port")
        val logFile = "$SERVER_DIR/server-${System.currentTimeMillis() / 1000}.log"
        AdbLink(port).use { l ->
            val id = l.whoAmI()
            log("adb 접속: $id")
            if (!id.contains("uid=2000")) log("경고: shell(2000)이 아닌 uid — 핫스팟 클라이언트가 100.99.9.9에 닿지 못할 수 있음")
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

    private fun canTryAdb(): Boolean = onWifi() && adbWifiEnabled()

    /**
     * Manual port wins; otherwise a discovered `_adb-tls-connect` record. Locality is NOT required here
     * because we always dial 127.0.0.1 (adbd listens on loopback too); an unrelated device's advert just
     * fails the `id` check and we retry.
     */
    private fun findPort(): Int? {
        val manual = AdbPrefs(context).manualConnectPort
        if (manual > 0) { set(State.FINDING_PORT, "수동 포트 $manual"); return manual }
        set(State.FINDING_PORT, "mDNS ${ADB_CONNECT_WAIT_S}s")
        val found = CountDownLatch(1)
        var port = 0
        val mdns = AdbMdns(context, AdbMdns.CONNECT, requireLocal = false) { p -> port = p; found.countDown() }
        mdns.start()
        try { found.await(ADB_CONNECT_WAIT_S, TimeUnit.SECONDS) } finally { mdns.stop() }
        return port.takeIf { it > 0 }
    }

    private fun waitFor(ms: Long) {
        synchronized(wake) { runCatching { if (ms == Long.MAX_VALUE) wake.wait() else wake.wait(ms) } }
    }

    companion object {
        private const val ADB_CONNECT_WAIT_S = 15L
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
