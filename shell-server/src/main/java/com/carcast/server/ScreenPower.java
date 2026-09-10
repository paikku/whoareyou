package com.carcast.server;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.Command;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.Settings;
import com.genymobile.scrcpy.util.SettingsException;
import com.genymobile.scrcpy.wrappers.DisplayControl;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.os.Build;
import android.os.IBinder;

/**
 * "Phone screen off, virtual display on" — what scrcpy's --turn-screen-off --stay-awake does and what M0
 * confirmed on One UI 8: switch the physical display(s) off through SurfaceControl.setDisplayPowerMode,
 * never the power button (that sleeps the whole device, virtual display included), and keep the device
 * awake while charging via the stay_on_while_plugged_in global setting (restored on exit).
 *
 * This is scrcpy 4.1's Device.setDisplayPower verbatim. Note what it does NOT use: the Android 15
 * DisplayManager.requestDisplayPower API. scrcpy keeps that behind USE_ANDROID_15_DISPLAY_POWER = false
 * because it does not work as expected (scrcpy issue #5530), and our first port used exactly that path —
 * which is why 📵 reported "전환 실패" on the S26 Ultra (d98be88) while stock scrcpy worked in M0.
 */
final class ScreenPower {
    private static final String STAY_ON = "stay_on_while_plugged_in";
    private static final String STAY_ON_ALL = "7"; // AC | USB | wireless
    private static final String SCREEN_OFF_TIMEOUT = "screen_off_timeout";
    /** 12 hours: long enough for any drive, finite so a server that dies without restoring it is not forever. */
    private static final String SCREEN_OFF_TIMEOUT_AWAKE = String.valueOf(12 * 60 * 60 * 1000);
    /** Poke the power manager this often while a car is connected, in case the timeout setting was refused or clamped. */
    static final long USER_ACTIVITY_INTERVAL_MS = 30_000;

    private String previousStayOn;
    private String previousTimeout;
    /** PowerManager keeps reporting the display as interactive after a SurfaceControl power-off, so track it here. */
    private boolean forcedOff;

    boolean isMainScreenOn() {
        if (forcedOff) {
            return false;
        }
        return interactive();
    }

    /** What PowerManager thinks: false once the device went to sleep (power button, screen timeout) — SurfaceControl-off does not change it. */
    private boolean interactive() {
        try {
            return ServiceManager.getPowerManager().isScreenOn(0);
        } catch (Throwable t) {
            Ln.w("isScreenOn failed: " + t);
            return true;
        }
    }

    /**
     * The device is asleep: the user pressed the power button, or the screen timed out. The virtual display
     * shares the device's power state (scrcpy's flags, verified in M0), so the app on it is paused and the
     * encoder gets nothing — the car freezes on its last frame. Only the phone waking up brings it back.
     */
    boolean isAsleep() {
        // Not gated on forcedOff: PowerManager reports non-interactive after a real sleep whether or not the
        // panel was already dark through SurfaceControl — and after that sleep our forced-off state is stale
        // anyway (DisplayManager owns the panel again on wake), see slept().
        return !interactive();
    }

    /** The device slept underneath us: DisplayManager will drive the panel on wake, our override is gone. */
    void slept() {
        forcedOff = false;
    }

    /** Whether we are holding the panel dark through SurfaceControl (PowerManager still counts it as on). */
    boolean isForcedOff() {
        return forcedOff;
    }

    /**
     * Wakes the device the way a key press does, then waits for PowerManager to agree. Returns whether it did.
     * The lock screen comes up on the phone; the virtual display is ALWAYS_UNLOCKED, so the car needs nothing.
     */
    boolean wake() {
        long t0 = System.currentTimeMillis();
        try {
            // In-process key injection first (tens of ms); the `input` command is the fallback (about a second).
            java.util.function.BooleanSupplier fast = wakeKey;
            boolean sent = fast != null && fast.getAsBoolean();
            if (!sent) {
                Command.exec("input", "keyevent", "KEYCODE_WAKEUP");
            }
            for (int i = 0; i < 40; i++) {
                if (interactive()) {
                    Ln.i("device woken up in " + (System.currentTimeMillis() - t0) + " ms" + (sent ? "" : " (input command)"));
                    return true;
                }
                Thread.sleep(50);
            }
            Ln.w("device did not wake up");
            return false;
        } catch (Throwable t) {
            Ln.e("wake failed: " + t);
            return false;
        }
    }

    /** Fast wake path (in-process KEYCODE_WAKEUP injection), set by the server when an input injector exists. */
    volatile java.util.function.BooleanSupplier wakeKey;
    /** Same for KEYCODE_SLEEP. */
    volatile java.util.function.BooleanSupplier sleepKey;

    /**
     * Puts the device to sleep and wakes it again — the OFF→ON pass of the display controller that lights the
     * panel for real. Needed when the panel is dark from our SurfaceControl override: the controller still
     * thinks the panel is on, so neither the power button nor a plain NORMAL request lights it (seen on the
     * S26U: "패널 켜기: true (7 ms)" and a panel that stayed dark; two physical presses always worked, and
     * two presses is exactly sleep + wake). Returns true when the device is interactive at the end.
     */
    boolean cycleSleepWake() {
        long t0 = System.currentTimeMillis();
        try {
            forcedOff = false; // the controller will own the panel after this
            if (interactive()) {
                java.util.function.BooleanSupplier s = sleepKey;
                if (s == null || !s.getAsBoolean()) {
                    Command.exec("input", "keyevent", "KEYCODE_SLEEP");
                }
                for (int i = 0; i < 30 && interactive(); i++) {
                    Thread.sleep(50);
                }
                Ln.i("sleep for panel cycle: interactive=" + interactive() + " after " + (System.currentTimeMillis() - t0) + " ms");
            }
            return wake();
        } catch (Throwable t) {
            Ln.e("sleep/wake cycle failed: " + t);
            return false;
        }
    }

    /** One line of what PowerManager and the display controller think, for the recovery log. */
    String describe() {
        String dpc = "";
        try {
            String out = Command.execReadOutput("sh", "-c", "dumpsys display 2>/dev/null | grep -E 'mScreenState=|mPowerState=|mScreenBrightness=|mWakefulness=' | head -4");
            dpc = out.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            dpc = "dumpsys failed: " + t;
        }
        return "interactive=" + interactive() + " forcedOff=" + forcedOff + " " + dpc;
    }

    /**
     * While a car is connected the phone must not time out and sleep (which stops the virtual display too):
     * push the screen timeout out, and give it back when the last client leaves or the server exits.
     * `stay_on_while_plugged_in` alone only covers the phone on a charger.
     */
    synchronized void keepAwake(boolean on) {
        try {
            if (on && previousTimeout == null) {
                previousTimeout = Settings.getAndPutValue(Settings.TABLE_SYSTEM, SCREEN_OFF_TIMEOUT, SCREEN_OFF_TIMEOUT_AWAKE);
                Ln.i(SCREEN_OFF_TIMEOUT + " = " + SCREEN_OFF_TIMEOUT_AWAKE + " (was " + previousTimeout + ") — car connected, phone stays awake");
            } else if (!on && previousTimeout != null) {
                Settings.putValue(Settings.TABLE_SYSTEM, SCREEN_OFF_TIMEOUT, previousTimeout);
                Ln.i(SCREEN_OFF_TIMEOUT + " restored to " + previousTimeout);
                previousTimeout = null;
            }
        } catch (Throwable e) {
            Ln.w("Could not set " + SCREEN_OFF_TIMEOUT + ": " + e);
        }
    }

    boolean isKeptAwake() {
        return previousTimeout != null;
    }

    /** Resets the inactivity timer without touching settings; harmless if the shell may not (the wrapper only logs). */
    void userActivity() {
        try {
            ServiceManager.getPowerManager().userActivity(0);
        } catch (Throwable t) {
            Ln.w("userActivity failed: " + t);
        }
    }

    /**
     * Phone panel off/on. Off while the device is asleep first wakes it (the car pressed 📵 after a power-button
     * sleep, or wants the stream back): awake with the panel dark is the state 📵 promises.
     */
    boolean setMainScreen(boolean on) {
        try {
            if (isAsleep() && !wake()) {
                return false;
            }
            boolean ok = setPhysicalDisplaysPower(on);
            Ln.i("physical display power " + (on ? "on" : "off") + ": " + ok);
            if (ok) {
                forcedOff = !on;
            }
            return ok;
        } catch (Throwable t) {
            Ln.e("display power change failed: " + t);
            return false;
        }
    }

    /** scrcpy Device.setDisplayPower, minus the disabled Android 15 branch and the Honor workaround. */
    private static boolean setPhysicalDisplaysPower(boolean on) {
        int mode = on ? SurfaceControl.POWER_MODE_NORMAL : SurfaceControl.POWER_MODE_OFF;
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_29_ANDROID_10) {
            // On Android 14, these internal methods have been moved to DisplayControl
            boolean useDisplayControl = Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14
                    && !SurfaceControl.hasGetPhysicalDisplayIdsMethod();
            long[] ids = useDisplayControl ? DisplayControl.getPhysicalDisplayIds() : SurfaceControl.getPhysicalDisplayIds();
            if (ids == null) {
                Ln.e("Could not get physical display ids");
                return false;
            }
            boolean allOk = true;
            for (long id : ids) {
                IBinder token = useDisplayControl ? DisplayControl.getPhysicalDisplayToken(id) : SurfaceControl.getPhysicalDisplayToken(id);
                if (token == null) {
                    Ln.e("No token for physical display " + id);
                    allOk = false;
                    continue;
                }
                allOk &= SurfaceControl.setDisplayPowerMode(token, mode);
            }
            return allOk;
        }
        IBinder d = SurfaceControl.getBuiltInDisplay();
        if (d == null) {
            Ln.e("Could not get built-in display");
            return false;
        }
        return SurfaceControl.setDisplayPowerMode(d, mode);
    }

    void stayAwake() {
        try {
            previousStayOn = Settings.getAndPutValue("global", STAY_ON, STAY_ON_ALL);
            Ln.i(STAY_ON + " = " + STAY_ON_ALL + " (was " + previousStayOn + ")");
        } catch (Throwable e) {
            // Never fatal: the server must come up even if this settings write is rejected.
            Ln.w("Could not set " + STAY_ON + ": " + e);
        }
    }

    /** On exit: never leave the phone dark, and put the settings back. */
    void restore() {
        if (forcedOff) {
            setMainScreen(true);
        }
        keepAwake(false);
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
