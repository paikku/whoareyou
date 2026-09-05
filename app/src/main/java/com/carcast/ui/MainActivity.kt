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
    private lateinit var useVpn: android.widget.CheckBox
    private lateinit var serverInApp: android.widget.CheckBox
    private val handler = Handler(Looper.getMainLooper())

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) startSession() else StreamService.log("VPN 권한 거부됨")
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
        findViewById<Button>(R.id.stop_server).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage(R.string.stop_server_confirm)
                .setPositiveButton(R.string.stop_server) { _, _ ->
                    Thread { val r = com.carcast.adb.ShellServerLink.stopServer(); StreamService.log("서버 종료 요청: $r") }.start()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
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

        toggle.setOnClickListener {
            if (StreamService.running) {
                startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            } else {
                if (!useVpn.isChecked) {
                    startSession()
                } else {
                    // VpnService.prepare returns an intent the first time; null means consent already given.
                    val consent = VpnService.prepare(this)
                    if (consent != null) vpnConsent.launch(consent) else startSession()
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

    /** One line about the newest report the car sent, from the /api/status JSON the service polls. */
    private fun lastReportLine(statusJson: String?): String {
        val st = runCatching { JSONObject(statusJson ?: return "-") }.getOrNull() ?: return "-"
        val n = st.optInt("reports", 0)
        val last = st.optJSONObject("lastReport") ?: return if (n == 0) "없음" else "${n}건"
        return "#${last.optInt("id")} ${last.optString("receivedAt").replace("T", " ").removeSuffix("Z")} ${last.optString("remote").substringBefore(':')}\n  ${last.optString("summary")}"
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
                append("adb: ").append(StreamService.linkState ?: if (AdbPrefs(this@MainActivity).paired) "페어링됨, 세션 없음" else "미페어링").append('\n')
                if (!onWifi()) append("※ ").append(getString(R.string.wifi_hint)).append('\n')
                append("URL: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/\n")
                append("진단: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/diag\n")
                append("차에서 보낸 진단: ").append(lastReportLine(st)).append('\n')
                append("인터페이스:\n")
                for (i in SelfTest.interfaces()) append("  ").append(i.name).append(' ').append(i.address).append('\n')
            }
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
}
