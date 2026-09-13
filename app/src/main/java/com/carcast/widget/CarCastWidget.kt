package com.carcast.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.carcast.R
import com.carcast.service.BulkControl
import com.carcast.service.HotspotState
import com.carcast.service.StreamService
import com.carcast.ui.MainActivity
import com.carcast.vpn.CarVpnService

/**
 * A home screen switch for the whole session: hotspot, shell server and the tun address in one flick,
 * so getting into the car does not mean opening the app and pressing three things.
 *
 * It is a real [android.widget.Switch]: RemoteViews has been able to carry compound buttons since
 * Android 12 (minSdk here is 34), and [RemoteViews.setOnCheckedChangeResponse] delivers the new checked
 * state in the fill-in intent under [RemoteViews.EXTRA_CHECKED]. A widget holds no state of its own, so
 * the switch is drawn from [StreamService] and redrawn by [refresh] whenever that changes — never from
 * what the user last flicked, which would lie the moment a step failed.
 *
 * Starting a foreground service straight from the tap is allowed: widget interaction is one of the
 * listed exemptions from the background-start restriction, which is the whole reason this can work
 * while the app is not open.
 *
 * The one thing a widget cannot do is VPN consent — [VpnService.prepare] needs an Activity — so when
 * consent has never been given the switch opens the app instead of pretending to start.
 */
class CarCastWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, render(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            // false is what a missing extra would give us too, so read "on" explicitly rather than by default.
            val on = intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false)
            toggle(context, on)
            refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    private fun toggle(context: Context, on: Boolean) {
        // VpnService.prepare returns an intent while consent is missing, null once it has been given.
        when (decide(on, consentGiven = VpnService.prepare(context) == null)) {
            Action.ALL_OFF ->
                context.startForegroundService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_ALL_OFF))
            Action.ALL_ON ->
                context.startForegroundService(Intent(context, StreamService::class.java).setAction(StreamService.ACTION_ALL_ON))
            Action.ASK_CONSENT -> context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_NEEDS_VPN_CONSENT, true)
            )
        }
    }

    /** What a flick of the switch should lead to. Separated from the doing so it can be checked. */
    enum class Action { ALL_ON, ALL_OFF, ASK_CONSENT }

    companion object {
        const val ACTION_TOGGLE = "com.carcast.widget.TOGGLE"

        /**
         * Turning off never needs consent — the sequence only takes things down, and refusing to switch off
         * because a permission is missing would be the worst possible moment to ask. Turning on does: the
         * session cannot come up without it, and a widget has no way to present the dialog, so it hands the
         * press to the app rather than starting something that would fall straight back over.
         */
        fun decide(checked: Boolean, consentGiven: Boolean): Action = when {
            !checked -> Action.ALL_OFF
            consentGiven -> Action.ALL_ON
            else -> Action.ASK_CONSENT
        }

        /**
         * What the switch shows. While a sequence runs it shows where that sequence is going, so the switch
         * does not snap back under the user's finger for the twenty seconds the radio takes. Otherwise it
         * shows the truth, and the truth is both parts: a session with a dead server is not "on".
         */
        fun checkedFor(phase: BulkControl.Phase, session: Boolean, server: Boolean): Boolean = when (phase) {
            BulkControl.Phase.TURNING_ON -> true
            BulkControl.Phase.TURNING_OFF -> false
            BulkControl.Phase.IDLE -> session && server
        }

        /** Redraw every placed instance. Cheap enough to call on any state change; a widget cannot poll. */
        fun refresh(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(context, CarCastWidget::class.java))
                if (ids.isEmpty()) return
                val views = render(context)
                for (id in ids) manager.updateAppWidget(id, views)
            }
        }

        /**
         * The switch is on only while the session is actually up; the line under it says which of the three
         * parts answered, because "on" with a dead server is the failure this project spends most of its
         * time on and the widget must not hide it.
         */
        private fun render(context: Context): RemoteViews {
            val busy = BulkControl.phase
            val session = StreamService.running
            val server = StreamService.shellStatus != null
            val vpn = CarVpnService.state == CarVpnService.State.UP
            val checked = checkedFor(busy, session, server)
            val views = RemoteViews(context.packageName, R.layout.widget_switch)
            views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_title))
            views.setTextViewText(
                R.id.widget_detail, when (busy) {
                    BulkControl.Phase.TURNING_ON -> context.getString(R.string.bulk_on_progress)
                    BulkControl.Phase.TURNING_OFF -> context.getString(R.string.bulk_off_progress)
                    // The hotspot is the driver's own switch, so it is shown and never touched.
                    BulkControl.Phase.IDLE -> context.getString(
                        R.string.widget_detail,
                        mark(vpn), mark(server), hotspotMark(),
                    )
                }
            )
            views.setCompoundButtonChecked(R.id.widget_toggle, checked)
            val intent = Intent(context, CarCastWidget::class.java).setAction(ACTION_TOGGLE)
            val pending = PendingIntent.getBroadcast(
                context, 0, intent,
                // Mutable on purpose: the fill-in intent is how the platform hands back EXTRA_CHECKED.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setOnCheckedChangeResponse(R.id.widget_toggle, RemoteViews.RemoteResponse.fromPendingIntent(pending))
            // Tapping anywhere but the switch opens the app, which is where anything that went wrong is explained.
            views.setOnClickPendingIntent(
                R.id.widget_body,
                PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            return views
        }

        private fun mark(up: Boolean) = if (up) "●" else "○"

        /**
         * Costs no network: [HotspotState] reads the last /api/status we already have, and falls back to the
         * phone's own interfaces when no server is running — which is most of the time the driver looks at
         * this, since the switch is for when nothing of ours is up yet.
         */
        private fun hotspotMark(): String = when (HotspotState.on()) {
            true -> "●"
            false -> "○"
            null -> "?"
        }
    }
}
