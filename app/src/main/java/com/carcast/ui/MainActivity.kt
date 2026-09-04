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
import com.carcast.service.NetDiag
import com.carcast.service.SelfTest
import com.carcast.service.StreamService
import com.carcast.vpn.CarVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var log: TextView
    private lateinit var command: TextView
    private lateinit var selfTest: Button
    private lateinit var netDiag: Button
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
                        st == null -> "응답 없음 — PC에서 shell 서버를 띄우세요 (아래 명령)"
                        else -> "응답 중 " + Regex("\"process\":\"(\\w+)\"").find(st)?.groupValues?.get(1) +
                            " uid=" + (Regex("\"uid\":(\\d+)").find(st)?.groupValues?.get(1) ?: "?") +
                            " clients=" + (Regex("\"videoClients\":(\\d+)").find(st)?.groupValues?.get(1) ?: "?")
                    }
                ).append('\n')
                append("URL: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/\n")
                append("진단: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/diag\n")
                append("인터페이스:\n")
                for (i in SelfTest.interfaces()) append("  ").append(i.name).append(' ').append(i.address).append('\n')
            }
            command.text = StreamService.shellCommand()
            log.text = StreamService.logLines.joinToString("\n")
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresh) }
}
