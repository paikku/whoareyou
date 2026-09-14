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
 * The Wireless debugging toggle (`adb_wifi_enabled`) is the same kind of setting and gets the same treatment
 * ([ensureWirelessOn]), for a different moment: after a reboot the TCP-mode port is gone (its property does
 * not persist) and wireless debugging is the only way back to adbd — and Android has switched it off at boot.
 * Whether AdbService honours a write from us the way it honours the Developer options switch is exactly what
 * this is in here to find out; the read-back says which (docs/verification-log.md).
 */
object UsbDebugging {
    const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
    private const val SETTING = "adb_enabled"
    private const val WIRELESS = "adb_wifi_enabled"

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
    fun ensureOn(context: Context): Outcome = ensure(context, SETTING)

    fun wirelessEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, WIRELESS, 0) == 1
    }.getOrDefault(false)

    /**
     * Same for Wireless debugging. Only meaningful on Wi-Fi — the platform side needs a network to bind to —
     * so the caller checks that first; here the setting is written and read back like the other one.
     */
    fun ensureWirelessOn(context: Context): Outcome = ensure(context, WIRELESS)

    private fun ensure(context: Context, setting: String): Outcome {
        val on = { runCatching { Settings.Global.getInt(context.contentResolver, setting, 0) == 1 }.getOrDefault(false) }
        if (on()) return Outcome.ALREADY_ON
        if (!canWrite(context)) return Outcome.NO_PERMISSION
        val wrote = runCatching { Settings.Global.putInt(context.contentResolver, setting, 1) }.getOrDefault(false)
        return if (wrote && on()) Outcome.TURNED_ON else Outcome.REFUSED
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

    fun describeWireless(o: Outcome): String = when (o) {
        Outcome.ALREADY_ON -> "무선 디버깅: 이미 켜져 있음"
        Outcome.TURNED_ON -> "무선 디버깅: 꺼져 있어서 켰음 — 접속 포트가 광고되는지 봅니다"
        Outcome.NO_PERMISSION -> "무선 디버깅: 꺼져 있고 앱이 켤 권한이 아직 없음 — 개발자 옵션에서 켜 주세요"
        Outcome.REFUSED -> "무선 디버깅: 앱이 켰지만 폰이 되돌림 — 개발자 옵션에서 직접 켜 주세요 (실측 기록 대상)"
    }
}
