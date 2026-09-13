package com.carcast.service

import android.content.Context
import com.carcast.Config
import com.carcast.adb.AdbPrefs
import com.carcast.adb.ShellServerLink
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Everything on" and "everything off" in one press: hotspot, shell server and the tun address.
 *
 * The three are not independent, and the order is not a preference:
 *
 *  - **Off** goes hotspot → server → session. The hotspot can only be switched by the shell server
 *    ([Hotspot] in shell-server: there is no app-uid API for the *tethered* hotspot), so killing the
 *    server first would strand the hotspot with nothing left able to turn it off.
 *  - **On** goes session → server → hotspot, for the same reason backwards: the server has to answer
 *    before anything can ask it for a hotspot.
 *
 * The one real hazard is turning the hotspot on while the server owes its life to Wi-Fi. Wireless
 * debugging goes off with Wi-Fi, and if USB debugging is off too, init SIGKILLs adbd's whole cgroup and
 * the server with it (docs/verification-log.md §3.5). So [precondition] says up front which of the three
 * ways out the phone currently has — TCP mode, USB debugging, or neither — and the caller logs it. We do
 * not refuse the press: the user asked, and on a phone with TCP mode this is the normal daily path.
 */
object BulkControl {

    enum class Phase { IDLE, TURNING_ON, TURNING_OFF }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

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
        return true
    }

    private fun release() {
        phase = Phase.IDLE
        busy.set(false)
    }

    /** Turning the hotspot on drops Wi-Fi; this says what would keep the server alive through that. */
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

    // --- the hotspot, as seen through the shell server ------------------------------------------

    /** What the server says about the hotspot: null when no server answers or the phone would not say. */
    fun hotspotOn(): Boolean? = runCatching {
        val j = JSONObject(get("/api/hotspot", 2000))
        if (j.optBoolean("known", false)) j.optBoolean("on", false) else null
    }.getOrNull()

    /**
     * Has this phone already told us it will not let an app switch the hotspot? Measured on both the emulator
     * and the S26U: `NO_CHANGE_TETHERING_PERMISSION`. When that is the answer there is nothing to attempt and
     * nothing to report but where the driver can do it by hand.
     */
    fun hotspotRefused(): Boolean = runCatching {
        JSONObject(get("/api/hotspot", 2000)).optBoolean("permissionDenied", false)
    }.getOrDefault(false)

    /**
     * Asks the server to switch the hotspot. [waitMs] is how long the server may wait for the radio
     * before answering; our own read timeout has to outlast it or we would give up on a call that worked.
     */
    fun setHotspot(on: Boolean, waitMs: Int = 15_000): Result {
        val body = runCatching { post("/api/hotspot?on=${if (on) 1 else 0}&wait=$waitMs", waitMs + 5_000) }
            .getOrElse { return Result(false, "서버에 닿지 못함: ${it.message ?: it}") }
        val j = runCatching { JSONObject(body) }.getOrNull()
            ?: return Result(false, "서버 응답을 읽지 못함: ${body.take(120)}")
        val ok = j.optBoolean("ok", false)
        val detail = j.optString("detail").ifEmpty { j.optString("error") }
        return Result(ok, detail.ifEmpty { body.take(160) })
    }

    data class Result(val ok: Boolean, val detail: String)

    // --- the sequences --------------------------------------------------------------------------

    /**
     * Hotspot → server → session, each step reported. Runs on the caller's thread (never the main one);
     * [log] is StreamService's on-screen log.
     */
    fun allOff(log: (String) -> Unit, stopSession: () -> Unit): Boolean {
        if (!claim(Phase.TURNING_OFF, log)) return false
        try {
            log("일괄 끄기: 핫스팟 → 서버 → 세션 순서로 끕니다")
            if (hotspotRefused()) {
                log("1/3 핫스팟: 이 폰은 앱이 바꾸는 것을 허용하지 않습니다 — 설정 > 모바일 핫스팟에서 직접 꺼 주세요")
            } else when (hotspotOn()) {
                null -> {
                    // Either no server, or a phone that will not report AP state. Try anyway when a server
                    // answers at all — a blind stop is harmless — and say so when there is nothing to ask.
                    if (serverUp()) {
                        val r = setHotspot(false)
                        log("1/3 핫스팟: 상태를 알 수 없어 그냥 끄기 시도 — ${r.detail}")
                    } else {
                        log("1/3 핫스팟: 서버가 없어 끄지 못했습니다 — 설정 > 모바일 핫스팟에서 직접 꺼 주세요")
                    }
                }
                false -> log("1/3 핫스팟: 이미 꺼져 있음")
                true -> {
                    val r = setHotspot(false)
                    log("1/3 핫스팟: " + (if (r.ok) "껐습니다" else "끄지 못했습니다") + " — ${r.detail}")
                }
            }
            val stopped = ShellServerLink.stopServer()
            log("2/3 서버 종료: $stopped")
            stopSession()
            log("3/3 세션·VPN 종료 완료")
            return true
        } finally {
            release()
        }
    }

    /**
     * Session+server (started by the caller, which owns the service) → wait for the server → hotspot.
     * [startSession] must be the caller's normal start path so VPN consent and the ADB link behave the same.
     */
    fun allOn(context: Context, log: (String) -> Unit, startSession: () -> Unit): Boolean {
        if (!claim(Phase.TURNING_ON, log)) return false
        try {
            val survival = precondition(context)
            log(
                "일괄 켜기: 세션 → 서버 → 핫스팟 순서로 켭니다 — " + when (survival) {
                    Survival.TCP_MODE -> "TCP 모드가 열려 있어 핫스팟을 켜도 서버를 다시 띄울 수 있습니다"
                    Survival.USB_DEBUGGING -> "TCP 모드는 없지만 USB 디버깅이 켜져 있어 adbd가 살아 있습니다 — 지금 뜬 서버는 유지되지만 차에서 죽으면 되살릴 수 없습니다"
                    Survival.NONE -> "⚠️ TCP 모드도 USB 디버깅도 꺼져 있습니다 — 핫스팟을 켜면 Wi-Fi가 끊기며 서버가 adbd와 함께 종료될 수 있습니다(§3.5). 'TCP 모드' 버튼을 먼저 켜 두세요"
                }
            )
            if (serverUp()) {
                log("1/3 세션·서버: 서버가 이미 응답 중")
                startSession()
            } else {
                startSession()
                log("1/3 세션 시작 — 서버 응답을 기다립니다")
            }
            val up = awaitServer(SERVER_WAIT_MS)
            log("2/3 서버: " + if (up) "응답 확인" else "${SERVER_WAIT_MS / 1000}초 안에 응답 없음 — 핫스팟은 건너뜁니다 (로그의 adb 상태를 보세요)")
            if (!up) return true
            when {
                hotspotRefused() ->
                    log("3/3 핫스팟: 이 폰은 앱이 바꾸는 것을 허용하지 않습니다 — 설정 > 모바일 핫스팟에서 직접 켜 주세요")
                hotspotOn() == true -> log("3/3 핫스팟: 이미 켜져 있음")
                else -> {
                    val r = setHotspot(true)
                    log("3/3 핫스팟: " + (if (r.ok) "켰습니다" else "켜지 못했습니다") + " — ${r.detail}")
                }
            }
            return true
        } finally {
            release()
        }
    }

    /** How long "everything on" waits for the shell server before giving up on the hotspot step. */
    const val SERVER_WAIT_MS = 30_000L

    private fun awaitServer(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (serverUp()) return true
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

    private fun get(path: String, timeoutMs: Int): String = open(path, "GET", timeoutMs)

    private fun post(path: String, timeoutMs: Int): String = open(path, "POST", timeoutMs)

    private fun open(path: String, method: String, timeoutMs: Int): String {
        val c = URL("http://127.0.0.1:${Config.HTTP_PORT}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = timeoutMs
        c.requestMethod = method
        if (method == "POST") {
            c.doOutput = true
            c.outputStream.close()
        }
        return c.inputStream.use { String(it.readBytes()) }
    }
}
