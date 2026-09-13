package com.carcast.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.carcast.BuildConfig
import com.carcast.Config
import com.carcast.R
import com.carcast.adb.AdbIdentity
import com.carcast.adb.AdbLink
import com.carcast.adb.AdbPairingService
import com.carcast.adb.AdbPrefs
import com.carcast.service.BulkControl
import com.carcast.service.NetDiag
import com.carcast.service.SelfTest
import com.carcast.service.StreamService
import com.carcast.vpn.CarVpnService
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var log: TextView
    private lateinit var command: TextView
    private lateinit var selfTest: Button
    private lateinit var netDiag: Button
    private lateinit var shareReports: Button
    private lateinit var pair: Button
    private lateinit var logScroll: android.widget.ScrollView
    private var lastLog = ""
    private var lastCommand = ""
    private lateinit var manual: Button
    private lateinit var tcpMode: Button
    private lateinit var useVpn: android.widget.CheckBox
    private lateinit var serverInApp: android.widget.CheckBox
    private val handler = Handler(Looper.getMainLooper())

    /** What to do once consent comes back: the plain session, or the whole bulk sequence. */
    private var afterConsent: () -> Unit = { startSession() }
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) afterConsent() else StreamService.log("VPN 권한 거부됨")
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        toggle = findViewById(R.id.toggle)
        log = findViewById(R.id.log)
        command = findViewById(R.id.command)
        selfTest = findViewById(R.id.selftest)
        netDiag = findViewById(R.id.netdiag)
        netDiag.setOnClickListener { shareDiagnostics() }
        shareReports = findViewById(R.id.share_reports)
        shareReports.setOnClickListener { shareCarReports() }
        pair = findViewById(R.id.pair)
        pair.setOnClickListener { startPairing() }
        manual = findViewById(R.id.manual)
        manual.setOnClickListener { manualDialog() }
        findViewById<Button>(R.id.adb_settings).setOnClickListener {
            runCatching { startActivity(AdbPrefs.wirelessDebuggingIntent()) }
                .onFailure { StreamService.log("개발자 옵션을 열지 못함: $it") }
        }
        tcpMode = findViewById(R.id.tcp_mode)
        tcpMode.setOnClickListener { if (AdbPrefs(this).tcpModeOptIn) confirmTcpModeOff() else confirmTcpModeOn() }
        findViewById<Button>(R.id.stop_server).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.stop_server_confirm)
                .setPositiveButton(R.string.stop_server) { _, _ ->
                    Thread { val r = com.carcast.adb.ShellServerLink.stopServer(); StreamService.log("서버 종료 요청: $r") }.start()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
        findViewById<Button>(R.id.bulk_on).setOnClickListener { bulkOn() }
        findViewById<Button>(R.id.bulk_off).setOnClickListener { confirmBulkOff() }
        logScroll = findViewById(R.id.log_scroll)
        findViewById<Button>(R.id.copy_log).setOnClickListener {
            val lines = StreamService.logLines.toList()
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("CarCast log", lines.joinToString("\n")))
            android.widget.Toast.makeText(this, getString(R.string.copied, lines.size), android.widget.Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.clear_log).setOnClickListener { StreamService.logLines.clear(); lastLog = "" ; log.text = "" }
        useVpn = findViewById(R.id.use_vpn)
        serverInApp = findViewById(R.id.server_in_app)
        selfTest.setOnClickListener {
            StreamService.log("self-test 시작 (인터페이스: ${SelfTest.interfaces().joinToString { "${it.name}=${it.address}" }})")
            SelfTest.run(StreamService::log)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // The home screen switch cannot ask for VPN consent (that needs an Activity), so it sends us here
        // instead of pretending the session started. Handle it once the views exist.
        if (intent?.getBooleanExtra(EXTRA_NEEDS_VPN_CONSENT, false) == true) {
            intent.removeExtra(EXTRA_NEEDS_VPN_CONSENT)
            StreamService.log("위젯에서 켜기 — VPN 권한이 아직 없어 앱에서 물어봅니다")
            bulkOn()
        }

        toggle.setOnClickListener {
            if (StreamService.running) {
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            } else {
                if (!useVpn.isChecked) {
                    startSession()
                } else {
                    // VpnService.prepare returns an intent the first time; null means consent already given.
                    val consent = VpnService.prepare(this)
                    if (consent != null) {
                        afterConsent = { startSession() }
                        vpnConsent.launch(consent)
                    } else {
                        startSession()
                    }
                }
            }
        }
    }

    /**
     * Collects routing rules, tables, sysctls and the app log into one text and opens the share
     * sheet, so the whole picture can be pasted into a chat instead of typed from a screenshot.
     * A short summary (VPN/prohibit rules, sysctls) also goes into the on-screen log.
     */
    private fun shareDiagnostics() {
        netDiag.isEnabled = false
        Thread {
            val summary = runCatching { NetDiag.summary() }.getOrElse { listOf("진단 실패: $it") }
            for (l in summary) StreamService.log("netdiag $l")
            val text = runCatching { NetDiag.dump() }.getOrElse { "dump failed: $it" } +
                "\n### app log\n" + StreamService.logLines.joinToString("\n")
            runOnUiThread {
                netDiag.isEnabled = true
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "CarCast netdiag ${BuildConfig.GIT_SHA}")
                    .putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, getString(R.string.netdiag)))
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Starts the pairing notification (which takes the code) and jumps to Developer options →
     * Wireless debugging, where the user opens "Pair device with pairing code".
     */
    private fun startPairing() {
        startForegroundService(Intent(this, AdbPairingService::class.java))
        runCatching { startActivity(AdbPrefs.wirelessDebuggingIntent()) }
            .onFailure { StreamService.log("개발자 옵션을 열지 못함: $it — 설정에서 직접 무선 디버깅으로 가세요") }
    }

    /** Fallback when mDNS finds nothing: type the ports the Wireless debugging screen shows. */
    private fun manualDialog() {
        val prefs = AdbPrefs(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0) }
        val help = TextView(this).apply { text = getString(R.string.manual_hint) }
        fun field(hintRes: Int, value: String = "") = android.widget.EditText(this).apply {
            setHint(hintRes); inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText(value)
        }
        val connectPort = field(R.string.manual_connect_port, if (prefs.manualConnectPort > 0) prefs.manualConnectPort.toString() else "")
        val pairPort = field(R.string.manual_pair_port)
        val pairCode = field(R.string.manual_pair_code)
        for (v in listOf(help, connectPort, pairPort, pairCode)) box.addView(v)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.manual_title)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                prefs.manualConnectPort = connectPort.text.toString().toIntOrNull() ?: 0
                StreamService.log("접속 포트 수동 설정: ${prefs.manualConnectPort} (0=자동)")
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_CONNECT))
            }
            .setNeutralButton(R.string.manual_pair_now) { _, _ ->
                val port = pairPort.text.toString().toIntOrNull() ?: 0
                if (port <= 0) { StreamService.log("페어링 포트가 비어 있음"); return@setNeutralButton }
                startForegroundService(
                    Intent(this, AdbPairingService::class.java).setAction(AdbPairingService.ACTION_CODE)
                        .putExtra(AdbPairingService.KEY_CODE, pairCode.text.toString())
                        .putExtra(AdbPairingService.EXTRA_PORT, port)
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * The car's /diag page posts what it measured to the shell server (POST /api/report); this pulls
     * the stored list back over loopback and opens the share sheet, so the car visit needs no photos.
     */
    private fun shareCarReports() {
        shareReports.isEnabled = false
        Thread {
            val text = runCatching { StreamService.fetchLocal("/api/reports") }
                .map { runCatching { JSONArray(it).toString(2) }.getOrDefault(it) }
                .getOrElse { "서버 응답 없음 (127.0.0.1:${Config.HTTP_PORT}): $it" }
            runOnUiThread {
                shareReports.isEnabled = true
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "CarCast car reports ${BuildConfig.GIT_SHA}")
                    .putExtra(Intent.EXTRA_TEXT, text)
                startActivity(Intent.createChooser(send, getString(R.string.share_reports)))
            }
        }.apply { isDaemon = true }.start()
    }

    /** The hotspot as the shell server sees it; "?" whenever nothing would say, which is not the same as off. */
    private fun hotspotLine(statusJson: String?): String {
        if (statusJson == null) return "서버 응답 없음"
        val st = runCatching { JSONObject(statusJson) }.getOrNull() ?: return "상태를 읽지 못함"
        // A server that answers but carries no hotspot block is an older build, not a phone without a hotspot.
        val h = st.optJSONObject("hotspot") ?: return "이전 빌드의 서버 — 핫스팟 제어 없음"
        if (!h.optBoolean("controllable", false)) return "제어 불가 (${h.optString("via")})"
        val state = if (!h.optBoolean("known", false)) "알 수 없음" else if (h.optBoolean("on", false)) "켜짐" else "꺼짐"
        val err = h.optString("lastError")
        return state + " (" + h.optString("via") + ")" + if (err.isEmpty()) "" else "\n  마지막 오류: $err"
    }

    /** One line about the newest report the car sent, from the /api/status JSON the service polls. */
    private fun lastReportLine(statusJson: String?): String {
        val st = runCatching { JSONObject(statusJson ?: return "-") }.getOrNull() ?: return "-"
        val n = st.optInt("reports", 0)
        val last = st.optJSONObject("lastReport") ?: return if (n == 0) "없음" else "${n}건"
        return "#${last.optInt("id")} ${last.optString("receivedAt").replace("T", " ").removeSuffix("Z")} ${last.optString("remote").substringBefore(':')}\n  ${last.optString("summary")}"
    }

    /** Opt in: the switch restarts adbd, so it never happens on its own — see AdbPrefs.tcpModeOptIn. */
    private fun confirmTcpModeOn() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.tcp_mode_on_confirm)
            .setPositiveButton(R.string.tcp_mode_on_action) { _, _ ->
                AdbPrefs(this).apply { tcpModeOptIn = true; tcpModeFailures = 0 }
                StreamService.log("TCP 모드 시도를 켰습니다 — 다음 adb 접속에서 전환합니다")
                startSession()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmTcpModeOff() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.tcp_mode_off_confirm)
            .setPositiveButton(R.string.tcp_mode_off_action) { _, _ ->
                val prefs = AdbPrefs(this)
                val port = prefs.tcpPort
                Thread {
                    // adbd restarts, so this also takes the server down; say so rather than leaving the log silent.
                    val r = if (port > 0) runCatching { com.carcast.adb.AdbLink(port).use { it.usbOnly() } } else null
                    prefs.tcpModeOptIn = false
                    prefs.tcpPort = 0
                    prefs.tcpModeFailures = 0
                    StreamService.log("TCP 모드 해제: " + (r?.fold({ it.ifEmpty { "(응답 없음 — adbd 재시작)" } }, { "adbd에 못 붙음 ($it) — 앱 쪽 기억만 지웠습니다" })
                        ?: "설정된 포트 없음 — 앱 쪽 기억만 지웠습니다"))
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /**
     * VPN → server → hotspot in one press. The order and the hazard are [BulkControl]'s; this only makes
     * sure consent exists first (a widget cannot ask for it) and that the one case where the hotspot can
     * take the server down with it is said out loud before it happens, not afterwards in the log.
     */
    private fun bulkOn() {
        val go: () -> Unit = {
            if (BulkControl.precondition(this) == BulkControl.Survival.NONE) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setMessage(R.string.bulk_on_warn_none)
                    .setPositiveButton(R.string.bulk_on) { _, _ -> sendBulk(StreamService.ACTION_ALL_ON) }
                    .setNegativeButton(android.R.string.cancel, null).show()
            } else {
                sendBulk(StreamService.ACTION_ALL_ON)
            }
        }
        val consent = if (useVpn.isChecked) VpnService.prepare(this) else null
        if (consent == null) {
            go()
            return
        }
        afterConsent = go
        vpnConsent.launch(consent)
    }

    private fun confirmBulkOff() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(R.string.bulk_off_confirm)
            .setPositiveButton(R.string.bulk_off) { _, _ -> sendBulk(StreamService.ACTION_ALL_OFF) }
            .setNeutralButton(R.string.hotspot_settings) { _, _ -> openTetherSettings() }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    /** The way out when no server is left to switch the hotspot: the phone's own tethering screen. */
    private fun openTetherSettings() {
        val tries = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (i in tries) {
            if (runCatching { startActivity(i) }.isSuccess) return
        }
        StreamService.log("핫스팟 설정 화면을 열지 못했습니다 — 설정 > 연결 > 모바일 핫스팟에서 직접 꺼 주세요")
    }

    private fun sendBulk(action: String) {
        startForegroundService(
            Intent(this, StreamService::class.java).setAction(action)
                .putExtra(StreamService.EXTRA_USE_VPN, useVpn.isChecked)
                .putExtra(StreamService.EXTRA_SERVER_IN_APP, serverInApp.isChecked)
        )
    }

    private fun startSession() {
        startForegroundService(
            Intent(this, StreamService::class.java)
                .putExtra(StreamService.EXTRA_USE_VPN, useVpn.isChecked)
                .putExtra(StreamService.EXTRA_SERVER_IN_APP, serverInApp.isChecked)
        )
    }

    private val refresh = object : Runnable {
        override fun run() {
            val running = StreamService.running
            toggle.text = getString(if (running) R.string.stop else R.string.start)
            status.text = buildString {
                append(if (running) "실행 중" else getString(R.string.status_idle))
                append("  (빌드 ").append(BuildConfig.GIT_SHA).append(")\n")
                append("tun: ").append(CarVpnService.state.name).append('\n')
                val st = StreamService.shellStatus
                append("서버: ").append(
                    when {
                        !running -> "-"
                        st == null -> "응답 없음 (앱이 adb로 기동 시도 중; 안 되면 PC에서 아래 명령)"
                        else -> "응답 중 " + Regex("\"process\":\"(\\w+)\"").find(st)?.groupValues?.get(1) +
                            " uid=" + (Regex("\"uid\":(\\d+)").find(st)?.groupValues?.get(1) ?: "?") +
                            " clients=" + (Regex("\"videoClients\":(\\d+)").find(st)?.groupValues?.get(1) ?: "?")
                    }
                ).append('\n')
                val prefs = AdbPrefs(this@MainActivity)
                append("adb: ").append(StreamService.linkState ?: if (prefs.paired) "페어링됨, 세션 없음" else "미페어링").append('\n')
                append("TCP 모드: ").append(
                    when {
                        // The port alone does not mean adbd is serving it — the log's probe line is the truth.
                        prefs.tcpModeOptIn && prefs.tcpPort > 0 -> "포트 ${prefs.tcpPort} (전환 시도됨 — 로그의 '열려 있음/닫힘' 확인)"
                        prefs.tcpModeOptIn -> "켜는 중 — 다음 adb 접속에서 전환합니다"
                        else -> "꺼짐 (아래 버튼으로 시도)"
                    }
                ).append('\n')
                if (!onWifi() && prefs.tcpPort == 0) append("※ ").append(getString(R.string.wifi_hint)).append('\n')
                append("핫스팟: ").append(hotspotLine(st)).append('\n')
                append("URL: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/\n")
                append("진단: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/diag\n")
                append("차에서 보낸 진단: ").append(lastReportLine(st)).append('\n')
                append("인터페이스:\n")
                for (i in SelfTest.interfaces()) append("  ").append(i.name).append(' ').append(i.address).append('\n')
            }
            tcpMode.setText(if (AdbPrefs(this@MainActivity).tcpModeOptIn) R.string.tcp_mode_off else R.string.tcp_mode_on)
            val cmd = StreamService.shellCommand()
            if (cmd != lastCommand) { lastCommand = cmd; command.text = cmd }
            val lines = StreamService.logLines.joinToString("\n")
            if (lines != lastLog) {
                // Re-setting the text drops the selection and scroll, so only do it when a line was added,
                // and follow the tail only if the user was already looking at the tail.
                val atBottom = !logScroll.canScrollVertically(1)
                lastLog = lines
                log.text = lines
                if (atBottom) logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun onWifi(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        return cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true }
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresh) }

    companion object {
        /** Set by the widget when VPN consent is missing: the app asks, then runs the same bulk start. */
        const val EXTRA_NEEDS_VPN_CONSENT = "needsVpnConsent"
    }
}
