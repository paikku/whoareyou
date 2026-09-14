package com.carcast.service

import android.content.Context
import com.carcast.Config
import com.carcast.adb.AdbPrefs
import com.carcast.adb.ShellServerLink
import com.carcast.adb.UsbDebugging
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Everything on" and "everything off" in one press: the shell server and the tun address.
 *
 * The hotspot is not in here. It cannot be — the phone answers uid 2000 with
 * TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION on every build we have (docs/verification-log.md open
 * question 0) — so the driver switches it themselves and we only show whether it is up ([HotspotState]).
 *
 * What is left still has an order, and it is not a preference: **off** stops the server before the session,
 * because the kill switch is an HTTP request to that server over the tun-less loopback and the session is
 * what the app uses to talk to it; **on** brings the session up first, because the server is started
 * through it. Each step says what it did, so a half-finished sequence is legible in the log rather than
 * showing up later as "it did not work".
 *
 * [precondition] still reports which way back the phone has — TCP mode, USB debugging, or neither — because
 * that decides whether a server that dies in the car can be brought back at all (docs/verification-log.md
 * §3.5, §3.8). It is reported, never enforced — except that **on** first switches USB debugging on when it
 * can ([UsbDebugging]): that is the one precondition the app is able to meet by itself, and the one the
 * driver was meeting by hand before every start.
 *
 * Progress is published as it happens ([step], [onChange]) because the widget is the only thing the driver
 * is looking at, and a switch that sits on "turning on…" for thirty seconds and then quietly snaps back has
 * told them nothing.
 */
object BulkControl {

    enum class Phase { IDLE, TURNING_ON, TURNING_OFF }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    /** Where the running sequence is, in one line; empty while idle. */
    @Volatile
    var step: String = ""
        private set

    /** What the last **on** found the USB debugging toggle to be; null until a sequence has run. */
    @Volatile
    var usbOutcome: UsbDebugging.Outcome? = null
        private set

    /** Why the last sequence ended without what it was after, or null. Cleared when the next one starts. */
    @Volatile
    var lastFailure: String? = null
        private set

    /** Called on every [phase]/[step]/[lastFailure] change; the widget redraws from it. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private fun changed() { runCatching { onChange?.invoke() } }

    /** A step is one line in the log and the same line on the widget. */
    private fun progress(s: String, log: (String) -> Unit) { step = s; log(s); changed() }

    /**
     * One sequence at a time. The phase alone cannot be that guard: two presses a few milliseconds apart
     * (a widget is easy to double-tap) would both read IDLE before either wrote to it, and the second
     * would then be switching the hotspot back on while the first was still taking it down.
     */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun claim(target: Phase, log: (String) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) {
            log("일괄 동작이 이미 진행 중입니다 ($phase) — 이번 요청은 무시합니다")
            return false
        }
        phase = target
        lastFailure = null
        step = ""
        changed()
        return true
    }

    private fun release() {
        phase = Phase.IDLE
        step = ""
        busy.set(false)
        changed()
    }

    private fun fail(why: String, log: (String) -> Unit) { lastFailure = why; log(why); changed() }

    /** Losing Wi-Fi kills the server on some setups; this says what would keep it alive through that. */
    enum class Survival {
        /** adbd listens on a fixed port regardless of Wi-Fi: the app can relaunch the server anywhere. */
        TCP_MODE,

        /** USB debugging keeps adbd (and its cgroup) alive, so the running server survives — but cannot be relaunched in the car. */
        USB_DEBUGGING,

        /** Neither: the server dies with Wi-Fi and only a Wi-Fi network can bring it back. */
        NONE,
    }

    fun precondition(context: Context): Survival = when {
        ShellServerLink.tcpModeReachable(context) -> Survival.TCP_MODE
        usbDebugging(context) -> Survival.USB_DEBUGGING
        else -> Survival.NONE
    }

    /** Settings.Global.adb_enabled is world-readable; 1 means the USB debugging toggle is on. */
    private fun usbDebugging(context: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(context.contentResolver, "adb_enabled", 0) == 1
    }.getOrDefault(false)

    // --- the sequences --------------------------------------------------------------------------

    /**
     * Server → session, each step reported. Runs on the caller's thread (never the main one); [log] is
     * StreamService's on-screen log.
     */
    fun allOff(log: (String) -> Unit, stopSession: () -> Unit): Boolean {
        if (!claim(Phase.TURNING_OFF, log)) return false
        try {
            log("일괄 끄기: 서버 → 세션 순서로 끕니다 (핫스팟은 앱이 못 바꿉니다 — 설정에서 직접)")
            progress("1/2 서버 종료 중", log)
            val stopped = ShellServerLink.stopServer()
            log("1/2 서버 종료: $stopped")
            progress("2/2 세션·VPN 종료 중", log)
            stopSession()
            log("2/2 세션·VPN 종료 완료")
            return true
        } finally {
            release()
        }
    }

    /**
     * Session (started by the caller, which owns the service) → wait for the server to answer.
     * [startSession] must be the caller's normal start path so VPN consent and the ADB link behave the same.
     */
    fun allOn(context: Context, log: (String) -> Unit, startSession: () -> Unit): Boolean {
        if (!claim(Phase.TURNING_ON, log)) return false
        try {
            // USB debugging keeps adbd alive through a Wi-Fi drop and reopens the TCP-mode port when it comes
            // back — so it goes first, before anything tries to reach adbd. Once the app has been granted
            // WRITE_SECURE_SETTINGS (ShellServerLink does that while it holds shell) this needs no adb at all.
            progress("1/3 USB 디버깅 확인", log)
            val usb = UsbDebugging.ensureOn(context)
            usbOutcome = usb
            log("1/3 " + UsbDebugging.describe(usb))
            val survival = precondition(context)
            log(
                "일괄 켜기: 세션 → 서버 순서로 켭니다 — " + when (survival) {
                    Survival.TCP_MODE -> "TCP 모드가 열려 있어 Wi-Fi가 끊겨도 서버를 다시 띄울 수 있습니다"
                    Survival.USB_DEBUGGING -> "TCP 모드는 없지만 USB 디버깅이 켜져 있어 adbd가 살아 있습니다 — 지금 뜬 서버는 유지되지만 차에서 죽으면 되살릴 수 없습니다"
                    Survival.NONE -> "⚠️ TCP 모드도 USB 디버깅도 꺼져 있습니다 — Wi-Fi가 끊기면(핫스팟을 켤 때가 그렇습니다) 서버가 adbd와 함께 종료될 수 있습니다(§3.5). \'TCP 모드\' 버튼을 먼저 켜 두세요"
                }
            )
            if (serverUp()) {
                progress("2/3 세션 시작 (서버는 이미 응답 중)", log)
                startSession()
            } else {
                progress("2/3 세션 시작 — 서버 응답을 기다립니다", log)
                startSession()
            }
            val up = awaitServer(SERVER_WAIT_MS) { waited ->
                step = "3/3 서버 응답 대기 ${waited / 1000}/${SERVER_WAIT_MS / 1000}초"
                changed()
            }
            if (up) log("3/3 서버: 응답 확인")
            else fail("3/3 서버: ${SERVER_WAIT_MS / 1000}초 안에 응답 없음 (로그의 adb 상태를 보세요)", log)
            // The hotspot is the driver's to switch; say where it stands rather than leaving them to guess
            // whether the car can reach us at all.
            log("핫스팟: " + when (HotspotState.on()) {
                true -> "켜져 있음"
                false -> "꺼져 있음 — 차가 붙으려면 설정에서 켜 주세요"
                null -> "상태를 알 수 없음"
            })
            return true
        } finally {
            release()
        }
    }

    /** How long "everything on" waits for the shell server before giving up on the hotspot step. */
    const val SERVER_WAIT_MS = 30_000L

    /** [tick] gets the elapsed milliseconds every couple of seconds, so a long wait is visibly a wait. */
    private fun awaitServer(timeoutMs: Long, tick: (Long) -> Unit = {}): Boolean {
        val start = System.currentTimeMillis()
        var lastTick = 0L
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (serverUp()) return true
            val waited = System.currentTimeMillis() - start
            if (waited - lastTick >= 2_000) { lastTick = waited; tick(waited) }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                return serverUp()
            }
        }
        return serverUp()
    }

    fun serverUp(): Boolean = runCatching { get("/api/status", 1000).contains("\"running\":true") }.getOrDefault(false)

    // --- loopback HTTP --------------------------------------------------------------------------

    private fun get(path: String, timeoutMs: Int): String {
        val c = URL("http://127.0.0.1:${Config.HTTP_PORT}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = timeoutMs
        return c.inputStream.use { String(it.readBytes()) }
    }
}
