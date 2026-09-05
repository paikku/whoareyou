package com.carcast.server;

import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.Settings;
import com.genymobile.scrcpy.util.SettingsException;
import com.genymobile.scrcpy.wrappers.ServiceManager;

/**
 * "Phone screen off, virtual display on" — what scrcpy's --turn-screen-off --stay-awake does and
 * what M0 confirmed on One UI 8: turn only the main display off with requestDisplayPower (Android
 * 15+), never the power button (that sleeps the whole device, virtual display included), and keep
 * the device awake while charging via the stay_on_while_plugged_in global setting (restored on exit).
 */
final class ScreenPower {
    private static final int MAIN_DISPLAY = 0;
    private static final String STAY_ON = "stay_on_while_plugged_in";
    private static final String STAY_ON_ALL = "7"; // AC | USB | wireless

    private String previousStayOn;

    boolean isMainScreenOn() {
        try {
            return ServiceManager.getPowerManager().isScreenOn(MAIN_DISPLAY);
        } catch (Throwable t) {
            Ln.w("isScreenOn failed: " + t);
            return true;
        }
    }

    boolean setMainScreen(boolean on) {
        try {
            boolean ok = ServiceManager.getDisplayManager().requestDisplayPower(MAIN_DISPLAY, on);
            Ln.i("main display power " + (on ? "on" : "off") + ": " + ok);
            return ok;
        } catch (Throwable t) {
            Ln.e("requestDisplayPower failed: " + t);
            return false;
        }
    }

    void stayAwake() {
        try {
            previousStayOn = Settings.getAndPutValue("global", STAY_ON, STAY_ON_ALL);
            Ln.i(STAY_ON + " = " + STAY_ON_ALL + " (was " + previousStayOn + ")");
        } catch (SettingsException e) {
            Ln.w("Could not set " + STAY_ON + ": " + e);
        }
    }

    void restore() {
        if (previousStayOn != null) {
            try {
                Settings.putValue("global", STAY_ON, previousStayOn);
            } catch (SettingsException e) {
                Ln.w("Could not restore " + STAY_ON + ": " + e);
            }
            previousStayOn = null;
        }
    }
}
