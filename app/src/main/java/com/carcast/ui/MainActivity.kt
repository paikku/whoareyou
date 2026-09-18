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
import androidx.core.content.FileProvider
import com.carcast.BuildConfig
import com.carcast.Config
import com.carcast.R
import com.carcast.adb.AdbIdentity
import com.carcast.adb.AdbLink
import com.carcast.adb.AdbPairingService
import com.carcast.adb.AdbPrefs
import com.carcast.service.BulkControl
import com.carcast.service.CertInstall
import com.carcast.service.HotspotState
import com.carcast.service.NetDiag
import com.carcast.service.SelfTest
import com.carcast.service.StreamService
import com.carcast.vpn.CarVpnService
import java.io.File
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
    private lateinit var certInstall: Button
    private lateinit var pair: Button
    private lateinit var logScroll: android.widget.ScrollView
    private var lastLog = ""
    private var lastCommand = ""
    private lateinit var manual: Button
    private lateinit var tcpMode: Button
    private lateinit var useVpn: android.widget.CheckBox
    private lateinit var serverInApp: android.widget.CheckBox
    private lateinit var setupWireless: Button
    private lateinit var setupPair: Button
    private lateinit var setupStart: Button
    private lateinit var setupWidget: Button
    private lateinit var setupNote: TextView
    private lateinit var advanced: android.view.View
    private lateinit var advancedToggle: Button
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
        certInstall = findViewById(R.id.cert_install)
        certInstall.setOnClickListener { certDialog() }
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

        // The front page: four steps, four lights (SetupSteps). Each button is the same action as the
        // corresponding control under "자세히", so a light going green means the same thing in both places.
        setupWireless = findViewById(R.id.setup_wireless)
        setupWireless.setOnClickListener {
            runCatching { startActivity(AdbPrefs.wirelessDebuggingIntent()) }
                .onFailure { StreamService.log("개발자 옵션을 열지 못함: $it") }
        }
        setupPair = findViewById(R.id.setup_pair)
        setupPair.setOnClickListener { startPairing() }
        setupStart = findViewById(R.id.setup_start)
        setupStart.setOnClickListener { toggle.performClick() }
        setupNote = findViewById(R.id.setup_note)
        setupWidget = findViewById(R.id.setup_widget)
        setupWidget.setOnClickListener { pinWidget() }
        advanced = findViewById(R.id.advanced)
        advancedToggle = findViewById(R.id.advanced_toggle)
        advancedToggle.setOnClickListener {
            val show = advanced.visibility != android.view.View.VISIBLE
            advanced.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
            advancedToggle.setText(if (show) R.string.advanced_hide else R.string.advanced_show)
        }
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
    /** Asks the launcher to place the widget (Android 8+); launchers that cannot get told where to find it. */
    private fun pinWidget() {
        val manager = getSystemService(android.appwidget.AppWidgetManager::class.java)
        val ok = runCatching {
            manager.isRequestPinAppWidgetSupported &&
                manager.requestPinAppWidget(android.content.ComponentName(this, com.carcast.widget.CarCastWidget::class.java), null, null)
        }.getOrDefault(false)
        if (!ok) android.widget.Toast.makeText(this, R.string.widget_pin_unsupported, android.widget.Toast.LENGTH_LONG).show()
    }

    /** "● 1  페어링됨" in green, "○ 1  페어링" in the default colour: the light is the first character. */
    private fun stepLabel(n: Int, done: Boolean, text: String): CharSequence {
        val s = android.text.SpannableString("${if (done) "●" else "○"}  $n  $text")
        if (done) s.setSpan(android.text.style.ForegroundColorSpan(0xFF2E7D32.toInt()), 0, 1, 0)
        return s
    }

    /** SetupSteps.read probes a socket, which the main thread may not do; one reader at a time, results posted back. */
    private val setupReader = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "setup-read").apply { isDaemon = true } }
    private val setupBusy = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun refreshSetup() {
        if (!setupBusy.compareAndSet(false, true)) return
        setupReader.execute {
            val st = runCatching { SetupSteps.read(this) }.getOrNull()
            setupBusy.set(false)
            if (st != null) runOnUiThread { if (!isFinishing) renderSetup(st) }
        }
    }

    private fun renderSetup(st: SetupSteps.State) {
        val running = StreamService.running
        setupWireless.text = stepLabel(
            1, st.wireless,
            getString(when { st.wirelessByApp -> R.string.setup_wireless_by_app; st.wireless -> R.string.setup_wireless_done; else -> R.string.setup_wireless }),
        )
        setupPair.text = stepLabel(2, st.paired, getString(if (st.paired) R.string.setup_pair_done else R.string.setup_pair))
        setupStart.text = stepLabel(
            3, st.server,
            getString(when { st.server -> R.string.setup_start_running; running -> R.string.setup_start_waiting; else -> R.string.setup_start }),
        )
        val note = SetupSteps.note(st, blocker = if (running && !st.server) StreamService.linkSummary else null)
        setupNote.visibility = if (note == null) android.view.View.GONE else android.view.View.VISIBLE
        if (note != null) setupNote.text = note
        setupWidget.text = stepLabel(4, st.widget, getString(if (st.widget) R.string.setup_widget_done else R.string.setup_widget))
    }

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
            // 텍스트를 Intent 에 통째로 싣던 예전 방식은 리포트가 쌓이자(상한 50 건, 펼치면 수백 KB)
            // Binder 한도에 걸려 **아무 일도 일어나지 않았다**. 파일로 첨부하면 크기가 문제되지 않는다.
            // 넉넉한 타임아웃도 같이 준다 — 그만한 응답을 1 초 안에 읽으라는 것도 무리였다.
            val body = runCatching { StreamService.fetchLocal("/api/reports", timeoutMs = 15_000) }
                .map { runCatching { JSONArray(it).toString(2) }.getOrDefault(it) }
                .getOrElse { "서버 응답 없음 (127.0.0.1:${Config.HTTP_PORT}): $it" }
            val file = runCatching { writeSharedFile(body) }.getOrNull()
            runOnUiThread {
                shareReports.isEnabled = true
                val send = Intent(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_SUBJECT, "CarCast car reports ${BuildConfig.GIT_SHA}")
                if (file != null) {
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                    send.setType("application/json")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        // 첨부를 못 여는 앱도 있으니 한 줄 요약은 본문으로도 같이 보낸다.
                        .putExtra(Intent.EXTRA_TEXT, lastReportLine(StreamService.shellStatus))
                } else {
                    send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                }
                startActivity(Intent.createChooser(send, getString(R.string.share_reports)))
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * 인증서를 받아 심는다 — PC 없이.
     *
     * 공개 CA 인증서는 90일이면 만료되고, 만료되는 날 차가 보여 주는 것은 넘길 수 없는 경고다. 그날
     * 운전자가 들고 있는 것은 폰뿐이라, 지금까지의 유일한 길(PC 에서 adb push, 또는 APK 재빌드)은
     * 차 안에서는 길이 아니었다. 서버의 창구(`POST /api/tls`)는 loopback 전용이므로 — 차가 무엇을 믿고
     * 열지를 바꾸는 일이다 — 부를 수 있는 것은 폰 위에서 도는 이 앱뿐이다.
     *
     * 주소는 기억해 둔다. 우리 도메인으로 발급받는 쪽이 제품 경로이고, 그때 이 화면에서 매번 같은 주소를
     * 다시 입력하게 만들 이유가 없다. 같은 주소로 **자동 갱신**도 돈다(`CertInstall.autoRenew`, 세션이
     * 서버 상태를 읽을 때마다 물어보고 하루 한 번만 움직인다) — 이 버튼은 그것을 지금 당장 시키는 손잡이다.
     */
    private fun certDialog() {
        val prefs = getSharedPreferences(CertInstall.PREFS, MODE_PRIVATE)
        val saved = prefs.getString(CertInstall.PREF_SOURCE, null) ?: CertInstall.LOCAL_IP_SH
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val hint = TextView(this).apply { text = getString(R.string.cert_install_hint) }
        val now = TextView(this).apply {
            text = tlsLine(StreamService.shellStatus)?.let { "지금: $it" } ?: "지금: 서버 응답 없음"
            setPadding(0, pad / 2, 0, 0)
        }
        val field = android.widget.EditText(this).apply {
            setText(saved)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        for (v in listOf(hint, now, field)) box.addView(v)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.cert_install_title)
            .setView(box)
            .setPositiveButton(R.string.cert_install_go) { _, _ ->
                val source = field.text.toString().trim()
                prefs.edit().putString(CertInstall.PREF_SOURCE, source).apply()
                runCertInstall(source)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 받는 것도 심는 것도 네트워크다: 메인 스레드에서 하지 않는다. 진행과 결과는 화면의 로그로 나간다. */
    private fun runCertInstall(source: String) {
        certInstall.isEnabled = false
        Thread {
            if (CertInstall.isPublicKeySource(source)) {
                StreamService.log("주의: $source 는 개인키까지 공개된 진단용 인증서입니다 — 누구나 같은 이름으로 서버를 세울 수 있습니다")
            }
            val outcome = runCatching { CertInstall.install(source, StreamService::log) }
                .getOrElse { CertInstall.Outcome(false, "실패: $it") }
            StreamService.log("인증서: ${outcome.message}")
            runOnUiThread {
                certInstall.isEnabled = true
                if (!isFinishing) {
                    android.widget.Toast.makeText(this, outcome.message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 공유용 캐시 파일 하나. 매번 덮어써서 쌓이지 않는다(FileProvider 경로: res/xml/shared_files.xml). */
    private fun writeSharedFile(body: String): File {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        return File(dir, "carcast-reports-${BuildConfig.GIT_SHA}.json").apply { writeText(body) }
    }

    /**
     * The hotspot, which the user switches and we only report. "?" whenever nothing would say — not the
     * same as off, and the difference matters when the car cannot reach the phone.
     */
    private fun hotspotLine(statusJson: String?): String {
        val state = when (HotspotState.on()) {
            true -> "켜짐"
            false -> "꺼짐 — 차가 붙으려면 설정에서 켜세요"
            null -> "알 수 없음"
        }
        val via = runCatching { JSONObject(statusJson ?: return state).optJSONObject("hotspot")?.optString("via") }
            .getOrNull().orEmpty()
        return if (via.isEmpty()) state else "$state ($via)"
    }

    /** One line about the newest report the car sent, from the /api/status JSON the service polls. */
    /**
     * 차가 열어야 할 **빠른 주소**. 공개 CA 가 서명한 인증서를 서버가 쓰고 있을 때만 있다 —
     * 자체서명은 이 차에서 경고를 넘을 수 없고(2026-09-17 실측), 경고를 넘지 못하면 secure context 도,
     * 하드웨어 디코더도 없다. 없으면 null 이고 화면에는 평문 주소만 남는다.
     */
    private fun fastUrl(statusJson: String?): String? {
        val st = runCatching { JSONObject(statusJson ?: return null) }.getOrNull() ?: return null
        if (!st.optBoolean("tlsTrusted")) return null
        val host = st.optString("tlsHost").ifBlank { return null }
        val port = st.optInt("httpsPort").takeIf { it > 0 } ?: return null
        return "https://$host:$port/"
    }

    /** 무슨 인증서로, 언제까지. 90일짜리를 쓰면 이 날짜가 곧 "차에서 갑자기 경고가 뜨는 날"이다. */
    private fun tlsLine(statusJson: String?): String? {
        val st = runCatching { JSONObject(statusJson ?: return null) }.getOrNull() ?: return null
        if (st.optInt("httpsPort") <= 0) return null
        val subject = st.optString("tlsSubject").ifBlank { "?" }
        val until = st.optString("tlsNotAfter").take(10).ifBlank { "?" }
        return if (st.optBoolean("tlsTrusted")) "$subject, $until 까지" else "자체서명 ($subject) — 이 차는 경고를 넘지 못한다"
    }

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
            // Once the app may write the toggle, "on" switches USB debugging on itself, so there is nothing to warn about.
            if (BulkControl.precondition(this) == BulkControl.Survival.NONE && !com.carcast.adb.UsbDebugging.canWrite(this)) {
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
            refreshSetup()
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
                // 차가 하드웨어 디코더에 닿으려면 https 여야 한다(VideoDecoder 는 secure context 전용). 그래서
                // 신뢰받는 인증서가 있으면 **그 주소를 먼저** 보여 준다 — 차의 북마크는 한 번 정해지면 그대로다.
                fastUrl(st)?.let { append("URL(빠름·하드웨어 디코더): ").append(it).append("\n") }
                append("URL: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/\n")
                append("진단: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/diag\n")
                tlsLine(st)?.let { append("인증서: ").append(it).append('\n') }
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
