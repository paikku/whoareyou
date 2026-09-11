package com.carcast.server;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.Command;
import com.genymobile.scrcpy.util.Settings;
import com.genymobile.scrcpy.util.SettingsException;
import com.genymobile.scrcpy.wrappers.DisplayControl;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.os.Build;
import android.os.IBinder;
import android.view.KeyEvent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final long WATCH_INTERVAL_MS = 1000;

    private String previousStayOn;
    /** PowerManager keeps reporting the display as interactive after a SurfaceControl power-off, so track it here. */
    private volatile boolean forcedOff;
    /** What `dumpsys display` last said the panel is doing: ON / OFF / DOZE / null when unread. */
    private volatile String panelState;
    /** How many times the user's power button contradicted our bookkeeping (see the watcher). */
    private volatile int reconciled;
    private volatile boolean lastInteractive = true;
    private volatile String lastTransition = "";
    private Thread watcher;

    // ---- 차가 보는 동안 폰이 잠들었을 때 ----------------------------------------------------------
    /** Off with `sleep_recovery=false`. */
    private volatile boolean sleepRecovery = true;
    private volatile int sleepRecoveries;
    /** While this is in the future, a power press is left alone (the driver asked for the phone back). */
    private volatile long recoveryPausedUntilMs;
    /** Our own wake, so the watcher does not read it as the user pressing power. */
    private volatile boolean selfWaking;
    private final long[] recentPresses = new long[ESCAPE_PRESSES];
    private int pressIndex;
    private java.util.function.BooleanSupplier carWatching = () -> false;

    // ---- 가상 디스플레이를 깨어 있게 두기 (scrcpy --keep-active) -----------------------------------
    /**
     * 물리 화면이 꺼지면 안드로이드는 **가상 디스플레이도** 유휴로 보고 약 10초 뒤 검은 면으로 덮는다.
     * 앱은 그 아래에서 계속 그려지지만 화면이 변하지 않으니 인코더가 멈추고, 차에는 얼어붙은 그림만 남는다.
     * scrcpy 에도 열려 있는 문제이며(Genymobile/scrcpy#6787), 거기서도 확실한 회피는 두 가지뿐이다:
     * 충전 중에만 듣는 stay_on_while_plugged_in, 그리고 주기적으로 사용자 활동을 알리는 --keep-active.
     *
     * 여기서는 **가상 디스플레이의 id 로** userActivity 를 보낸다. 그 디스플레이는 자기 display group 을
     * 가지므로(OWN_DISPLAY_GROUP / DEVICE_DISPLAY_GROUP) 폰 본체를 깨우지 않고 그 그룹의 유휴 시계만 되돌린다.
     * 차가 보고 있는 동안에만 보낸다 — 아무도 안 보는데 폰을 붙잡고 있을 이유가 없다.
     */
    private volatile boolean keepActive = true;
    private volatile int keptActive;
    private java.util.function.IntSupplier keepActiveDisplayId = () -> -1;
    private long lastKeepActiveMs;

    private static final long KEEP_ACTIVE_INTERVAL_MS = 5_000;

    void setKeepActive(boolean on) {
        keepActive = on;
    }

    void setKeepActiveDisplay(java.util.function.IntSupplier displayId) {
        keepActiveDisplayId = displayId;
    }

    private static final int ESCAPE_PRESSES = 3;
    private static final long ESCAPE_WINDOW_MS = 10_000;
    private static final long ESCAPE_PAUSE_MS = 60_000;

    void setSleepRecovery(boolean on) {
        sleepRecovery = on;
    }

    /** True while a car client is attached: only then is a sleep worth undoing. */
    void setCarWatching(java.util.function.BooleanSupplier s) {
        carWatching = s;
    }

    boolean isMainScreenOn() {
        if (forcedOff) {
            return false;
        }
        return interactive();
    }

    /** PowerManager's view: false while the device is asleep. Stays true after a SurfaceControl power-off. */
    boolean interactive() {
        try {
            return ServiceManager.getPowerManager().isScreenOn(0);
        } catch (Throwable t) {
            Ln.w("isScreenOn failed: " + t);
            return true;
        }
    }

    /** Fields for /api/status: what the car (and the lifecycle harness) needs to see the two views disagree. */
    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("screenOn", isMainScreenOn());
        m.put("interactive", lastInteractive);
        m.put("forcedOff", forcedOff);
        m.put("panelState", panelState);
        m.put("powerReconciled", reconciled);
        m.put("sleepRecoveries", sleepRecoveries);
        m.put("keptActive", keptActive);
        long paused = recoveryPausedUntilMs - System.currentTimeMillis();
        m.put("recoveryPausedMs", paused > 0 ? paused : 0);
        if (!lastTransition.isEmpty()) {
            m.put("lastPowerEvent", lastTransition);
        }
        return m;
    }

    /**
     * The phone's power button is not ours to intercept, and it moves the same panel 📵 does. Without this
     * watcher the two views drift apart: 📵 turns the panel off (forcedOff = true), the user presses power,
     * the panel comes back on — and we keep telling the car the screen is off, so the next 📵 press does
     * nothing visible and the button reads inverted from then on.
     *
     * So: sample the device's own view, and whenever it contradicts our flag, believe the device.
     * Only reports and reconciles — it never presses anything back, which would be a fight with the user.
     */
    synchronized void startWatching() {
        if (watcher != null) {
            return;
        }
        watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    sample();
                } catch (Throwable t) {
                    Ln.w("power watch failed: " + t);
                }
            }
        }, "screen-power-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    void stopWatching() {
        Thread t = watcher;
        watcher = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private void sample() {
        boolean now = interactive();
        if (now != lastInteractive) {
            lastTransition = (now ? "awake" : "asleep") + "@" + System.currentTimeMillis();
            Ln.i("phone went " + (now ? "awake" : "asleep") + " (forcedOff=" + forcedOff + ")");
            // Waking is the moment our bookkeeping goes stale: the system lights the panel itself, so a
            // forced-off flag from an earlier 📵 is now a lie. 📵 alone never moves this flag (PowerManager
            // keeps reporting interactive after a SurfaceControl power-off), which is what makes the
            // transition — not the absolute state — the safe signal to key on. Our own wake during a sleep
            // recovery is not the user, so it must not clear the flag.
            if (now && forcedOff && !selfWaking) {
                forcedOff = false;
                reconciled++;
                Ln.i("the phone woke on its own (power button) while we thought the panel was off — dropping our flag");
            }
            lastInteractive = now;
            if (now) {
                // Waking redraws everything. Ask for an IDR now instead of letting the car wait out the GOP:
                // measured 2.4 s of stale picture after a wake before this (lifecycle "전원 버튼 × 📵" row 5).
                try {
                    onWake.run();
                } catch (Throwable t) {
                    Ln.w("onWake failed: " + t);
                }
            } else {
                onWentToSleep();
            }
        }
        if (forcedOff) {
            panelState = readPanelState();
        }
        pokeVirtualDisplay();
    }

    /** 가상 디스플레이의 유휴 시계를 되돌린다 (위 keepActive 주석). */
    private void pokeVirtualDisplay() {
        if (!keepActive || !carWatching.getAsBoolean()) {
            return;
        }
        int id = keepActiveDisplayId.getAsInt();
        if (id <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastKeepActiveMs < KEEP_ACTIVE_INTERVAL_MS) {
            return;
        }
        lastKeepActiveMs = now;
        try {
            ServiceManager.getPowerManager().userActivity(id);
            keptActive++;
        } catch (Throwable t) {
            Ln.w("userActivity(" + id + ") failed: " + t);
            keepActive = false; // 이 ROM 에서 안 되면 매초 실패 로그를 남기지 않는다
        }
    }

    /**
     * The phone just went to sleep. If the car is watching, that kills the picture: a sleeping device stops
     * composing every display, the virtual one included, so the encoder goes silent and the car is left with
     * a frozen frame and no idea why (real-car session report #26, 2026-09-11: idle 6.9 s, 0 fps, no error).
     *
     * Almost always the driver pressed power meaning "darken my phone", which is what 📵 does — they just
     * used the button they always use. So turn the press into 📵: wake the device back up and switch the
     * panel off through SurfaceControl, which leaves the car untouched.
     *
     * The one reading this must not steal: someone who really wants the phone back presses power again and
     * again. {@link #ESCAPE_PRESSES} presses inside {@link #ESCAPE_WINDOW_MS} stop the recovery for a minute
     * and leave the phone lit — after that the phone is theirs and the car says why it is dark.
     */
    private void onWentToSleep() {
        if (!sleepRecovery) {
            return;
        }
        long now = System.currentTimeMillis();
        recentPresses[pressIndex] = now;
        pressIndex = (pressIndex + 1) % ESCAPE_PRESSES;
        long oldest = Long.MAX_VALUE;
        for (long t : recentPresses) {
            oldest = Math.min(oldest, t);
        }
        boolean escaping = oldest > 0 && now - oldest < ESCAPE_WINDOW_MS;
        if (escaping) {
            recoveryPausedUntilMs = now + ESCAPE_PAUSE_MS;
            java.util.Arrays.fill(recentPresses, 0);
            Ln.i("power pressed " + ESCAPE_PRESSES + " times quickly — the phone is yours; pausing recovery for "
                    + (ESCAPE_PAUSE_MS / 1000) + "s");
            wake(false); // 화면을 켠 채로 돌려준다
            return;
        }
        if (now < recoveryPausedUntilMs) {
            return; // 방금 escape 했다: 손대지 않는다
        }
        if (!carWatching.getAsBoolean()) {
            return; // 아무도 안 보고 있으면 그냥 자게 둔다
        }
        sleepRecoveries++;
        Ln.i("the phone went to sleep while the car was watching — waking it and darkening the panel instead");
        wake(true);
    }

    /** Wake the device; with {@code darken}, immediately put the panel back off (the 📵 state). */
    private void wake(boolean darken) {
        selfWaking = darken;
        try {
            Command.execReadOutput("input", "keyevent", String.valueOf(KeyEvent.KEYCODE_WAKEUP));
            for (int i = 0; i < 20 && !interactive(); i++) {
                Thread.sleep(50);
            }
            if (darken) {
                if (setPhysicalDisplaysPower(false)) {
                    forcedOff = true;
                }
            } else {
                forcedOff = false;
            }
            lastInteractive = interactive();
        } catch (Exception e) {
            Ln.w("wake failed: " + e);
        } finally {
            selfWaking = false;
        }
    }

    /**
     * The built-in panel's power state, for the record only — never to decide anything.
     *
     * `dumpsys display` was the first try and it was wrong: its first `mScreenState=` belongs to whichever
     * display is printed first, which on a phone streaming to a virtual display is not the built-in one. That
     * made the watcher clear the 📵 flag the instant it was set (caught by the lifecycle scenario, 2026-09-11).
     * `dumpsys power` prints one "Display Power: state=" line for the device's own panel, which is the thing
     * 📵 and the power button both move.
     */
    private static String readPanelState() {
        try {
            String out = Command.execReadOutput("dumpsys", "power");
            Matcher m = DISPLAY_POWER.matcher(out);
            if (m.find()) {
                return m.group(1);
            }
            // Android 16 (and the emulator) do not print that line; wakefulness is the next best record.
            Matcher w = WAKEFULNESS.matcher(out);
            return w.find() ? "wakefulness=" + w.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern DISPLAY_POWER = Pattern.compile("Display Power: state=([A-Z_]+)");
    private static final Pattern WAKEFULNESS = Pattern.compile("mWakefulness=([A-Za-z]+)");

    /** Called when the phone wakes on its own, so the car can resync its picture without waiting for a GOP. */
    private Runnable onWake = () -> { };

    void setOnWake(Runnable r) {
        onWake = r;
    }

    boolean setMainScreen(boolean on) {
        try {
            // Turning the panel on while the device is asleep does nothing the driver can see: the display
            // controller keeps it dark until the device is interactive again. That left 📵 dead after the
            // phone had been put to sleep with its own power button — press it and nothing happens, with no
            // way out from the car (lifecycle scenario "전원 버튼 × 📵" row 3, 2026-09-11).
            // The driver asked for the screen, so wake the phone first. This is not fighting the user:
            // it only ever happens on an explicit press of 📵.
            if (on && !interactive()) {
                Ln.i("phone is asleep and the car asked for its screen: waking it first");
                try {
                    Command.execReadOutput("input", "keyevent", String.valueOf(KeyEvent.KEYCODE_WAKEUP));
                    Thread.sleep(300);
                } catch (Exception e) {
                    Ln.w("could not wake the phone: " + e);
                }
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

    /** On exit: never leave the phone dark, and put the setting back. */
    void restore() {
        stopWatching();
        if (forcedOff) {
            setMainScreen(true);
        }
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
