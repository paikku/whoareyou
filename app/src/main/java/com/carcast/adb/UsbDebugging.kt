package com.carcast.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * The USB debugging toggle (`Settings.Global.adb_enabled`), switched by the app itself.
 *
 * Why the app wants it on: adbd only keeps running through a Wi-Fi drop while this toggle is on, and the
 * TCP-mode port it reopens afterwards is how the app reaches adbd without Wi-Fi (docs/hotspot-only.md §2).
 * With the toggle off, leaving Wi-Fi ends adbd, and the driver is back to switching it on by hand before
 * every start — which is what this removes.
 *
 * Why the app *can*: writing the setting needs WRITE_SECURE_SETTINGS, a "development" permission that
 * shell may grant to any app that declares it (`pm grant`), and the grant outlives reboots. The app holds
 * shell whenever it launches the server, so it grants itself then ([grantCommand]); from that point on the
 * toggle can be switched with no adb at all — including right after a reboot, when there is none.
 *
 * Only the USB toggle is touched. Wireless debugging is not: Android switches that one off on its own when
 * Wi-Fi drops, and it is not what keeps adbd alive.
 */
object UsbDebugging {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    private const val SETTING = "adb_enabled"

    /** What one call to [ensureOn] did, so the log and the widget can say it in one line. */
    enum class Outcome {
        /** Was on already; nothing written. */
        ALREADY_ON,
        /** Was off, and is on now. adbd starts (or restarts) as a result. */
        TURNED_ON,
        /** The grant has not happened yet (first run, or the app was reinstalled): only shell can fix that. */
        NO_PERMISSION,
        /** Written, but the setting did not take — a ROM that reverts it, or a device policy forbidding debugging. */
        REFUSED,
    }

    /** The toggle as the phone reports it. World-readable, so this never needs the permission. */
    fun enabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, SETTING, 0) == 1
    }.getOrDefault(false)

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Switches the toggle on if it is off. Reads it back rather than trusting the write: a refusal is silent. */
    fun ensureOn(context: Context): Outcome {
        if (enabled(context)) return Outcome.ALREADY_ON
        if (!canWrite(context)) return Outcome.NO_PERMISSION
        val wrote = runCatching { Settings.Global.putInt(context.contentResolver, SETTING, 1) }.getOrDefault(false)
        return if (wrote && enabled(context)) Outcome.TURNED_ON else Outcome.REFUSED
    }

    /** The one-time shell command that makes [ensureOn] possible. Idempotent; prints nothing on success. */
    fun grantCommand(packageName: String): String = "pm grant $packageName $PERMISSION"

    /** Korean one-liner for the log and the widget. */
    fun describe(o: Outcome): String = when (o) {
        Outcome.ALREADY_ON -> "USB 디버깅: 이미 켜져 있음"
        Outcome.TURNED_ON -> "USB 디버깅: 꺼져 있어서 켰음 (adbd 기동)"
        Outcome.NO_PERMISSION -> "USB 디버깅: 꺼져 있고 앱이 켤 권한이 아직 없음 — 개발자 옵션에서 켜 주세요 (서버가 한 번 뜨면 앱이 권한을 받아 다음부터는 스스로 켭니다)"
        Outcome.REFUSED -> "USB 디버깅: 앱이 켰지만 폰이 되돌림 — 개발자 옵션에서 직접 켜 주세요"
    }
}
