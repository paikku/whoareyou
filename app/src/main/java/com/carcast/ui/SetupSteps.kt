package com.carcast.ui

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import com.carcast.adb.AdbPrefs
import com.carcast.adb.ShellServerLink
import com.carcast.adb.UsbDebugging
import com.carcast.service.StreamService
import com.carcast.widget.CarCastWidget

/**
 * The four things a new phone needs, in order, and whether each is done — what the front of the app shows.
 *
 * Everything else the app can do lives behind "자세히": a first-time user should see four buttons and four
 * lights, not twelve controls and a log. Three of the things the first session does by itself (the secure-
 * settings grant, USB debugging, TCP mode) are shown as a note under step 3 rather than as steps, because
 * there is nothing to press for them — they either happened or the log says why not.
 */
object SetupSteps {

    data class State(
        /**
         * Wireless debugging is on, or nothing is left for the user to do about it: the TCP-mode port or the
         * server answers, or the app holds the grant and will switch it on itself when step 3 runs. After a
         * reboot the toggle is off (Android does that) but this is what a phone that was set up once looks
         * like — the light must not send the user back to Developer options.
         */
        val wireless: Boolean,
        /** True when [wireless] is green only because the app will do it, so the label can say so. */
        val wirelessByApp: Boolean,
        val paired: Boolean,
        /** The shell server answers on loopback. */
        val server: Boolean,
        /** At least one CarCast widget is placed on a home screen. */
        val widget: Boolean,
        val granted: Boolean,
        val usbDebugging: Boolean,
        val tcpMode: Boolean,
    ) {
        val allDone get() = wireless && paired && server && widget
    }

    fun read(context: Context): State {
        val prefs = AdbPrefs(context)
        val server = StreamService.shellStatus != null
        val tcp = ShellServerLink.tcpModeReachable(context)
        val granted = UsbDebugging.canWrite(context)
        val wirelessOn = server || tcp || UsbDebugging.wirelessEnabled(context)
        return State(
            wireless = wirelessOn || granted,
            wirelessByApp = !wirelessOn && granted,
            paired = prefs.paired,
            server = server,
            widget = widgetPlaced(context),
            granted = granted,
            usbDebugging = UsbDebugging.enabled(context),
            tcpMode = tcp,
        )
    }

    fun widgetPlaced(context: Context): Boolean = runCatching {
        AppWidgetManager.getInstance(context)
            ?.getAppWidgetIds(ComponentName(context, CarCastWidget::class.java))?.isNotEmpty() == true
    }.getOrDefault(false)

    /**
     * What to say under step 3 for the three automatic things. Nothing while none has happened yet (before
     * the first session there is nothing to report); afterwards each is a light, and a missing one names the
     * only case the user has to act on.
     */
    fun note(s: State, blocker: String? = null): String? {
        // A session that has no server yet: what it is waiting on beats the three lights — that is the line
        // that says "connect to Wi-Fi" after a reboot.
        if (blocker != null) return "⏳ $blocker"
        if (!s.granted && !s.usbDebugging && !s.tcpMode) return null
        val parts = listOf("권한" to s.granted, "USB 디버깅" to s.usbDebugging, "TCP 모드" to s.tcpMode)
        return parts.joinToString("  ") { (name, on) -> "${if (on) "●" else "○"} $name" }
    }
}
