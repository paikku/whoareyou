package com.carcast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.carcast.adb.AdbIdentity

class CarCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        // High importance so the pairing notification (with its code input) shows over the Settings screen.
        nm.createNotificationChannel(
            NotificationChannel(PAIRING_CHANNEL_ID, getString(R.string.pair_channel), NotificationManager.IMPORTANCE_HIGH)
        )
        AdbIdentity.init(filesDir)
    }

    companion object {
        const val CHANNEL_ID = "carcast.stream"
        const val PAIRING_CHANNEL_ID = "carcast.pairing"
    }
}
