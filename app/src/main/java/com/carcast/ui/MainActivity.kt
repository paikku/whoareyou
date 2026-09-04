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
import com.carcast.Config
import com.carcast.R
import com.carcast.service.SelfTest
import com.carcast.service.StreamService
import com.carcast.vpn.CarVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var log: TextView
    private lateinit var selfTest: Button
    private lateinit var useVpn: android.widget.CheckBox
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
        selfTest = findViewById(R.id.selftest)
        useVpn = findViewById(R.id.use_vpn)
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

    private fun startSession() {
        startForegroundService(
            Intent(this, StreamService::class.java).putExtra(StreamService.EXTRA_USE_VPN, useVpn.isChecked)
        )
    }

    private val refresh = object : Runnable {
        override fun run() {
            val running = StreamService.running
            toggle.text = getString(if (running) R.string.stop else R.string.start)
            status.text = buildString {
                append(if (running) "실행 중" else getString(R.string.status_idle)).append('\n')
                append("tun: ").append(CarVpnService.state.name).append('\n')
                append("URL: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/\n")
                append("진단: http://").append(Config.TUN_ADDRESS).append(':').append(Config.HTTP_PORT).append("/diag\n")
                append("control 패킷: ").append(StreamService.controlPackets).append('\n')
                append("인터페이스:\n")
                for (i in SelfTest.interfaces()) append("  ").append(i.name).append(' ').append(i.address).append('\n')
            }
            log.text = StreamService.logLines.joinToString("\n")
            handler.postDelayed(this, 1000)
        }
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(refresh) }
}
