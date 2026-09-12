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
    private static final String SCREEN_OFF_TIMEOUT = "screen_off_timeout";
    /** Nobody is watching: one sample a second is plenty and costs nothing. */
    private static final long WATCH_IDLE_MS = 1000;
    /**
     * The car is watching: every sample is a second the driver may spend staring at a frozen picture,
     * because we only learn the phone slept when we look. Until the shell process can receive
     * ACTION_SCREEN_OFF (it has no Context that can registerReceiver - the upstream scrcpy tree does
     * not do it either), looking more often is the whole fix.
     */
    private static final long WATCH_WATCHING_MS = 250;

    private String previousStayOn;
    private String previousScreenOffTimeout;
    private String screenOffTimeout;
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
    /** Wake only the car's display group instead of the whole phone, when the phone took it down too. */
    private volatile boolean vdWake = true;
    private volatile int vdWakes;
    /** What the car's display group was doing the last time the phone went to sleep (null: unknown). */
    private volatile Boolean lastSleepVdInteractive;
    /** How the panel was last switched (on or off): power-mode / cmd-display / brightness / none. */
    private volatile String panelOffMethod = "";
    private volatile int panelOffFailures;
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
    /**
     * Whether those pokes are actually doing anything. They may not be: PowerManagerService drops
     * userActivity() from a caller without DEVICE_POWER/USER_ACTIVITY *silently* - it logs a warning
     * and returns, no exception. So a rising keptActive proves we called, not that it landed. Once,
     * a couple of seconds after the first poke, read that warning back out of logcat and record the
     * answer here: null = not looked yet, TRUE = no warning (it lands), FALSE = ignored.
     */
    private volatile Boolean keepActiveEffective;
    private volatile long keepActiveCheckAtMs;
    private volatile int keepActiveChecks;
    /**
     * When the pokes are ignored, Castla's fallback: send WAKEUP to the virtual display itself.
     * Off by default because a ROM that ignores "input -d" would wake the whole phone every few
     * seconds - exactly the flashing we are trying to avoid. Turn it on per device once the field
     * above says the clean path does not work there.
     */
    private volatile boolean keepActiveFallback;
    private volatile int keptActiveFallback;
    private java.util.function.IntSupplier keepActiveDisplayId = () -> -1;
    private long lastKeepActiveMs;

    private static final long KEEP_ACTIVE_INTERVAL_MS = 5_000;
    private static final long KEEP_ACTIVE_CHECK_DELAY_MS = 3_000;
    /** If logcat cannot be read, give up after a few tries instead of spawning a process forever. */
    private static final int KEEP_ACTIVE_CHECK_TRIES = 3;

    void setKeepActive(boolean on) {
        keepActive = on;
    }

    void setKeepActiveFallback(boolean on) {
        keepActiveFallback = on;
    }

    void setVdWake(boolean on) {
        vdWake = on;
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

    /**
     * Is the car's display group awake? Only Android 14+ can answer per display (isDisplayInteractive);
     * before that the question does not exist and we return null rather than guess.
     */
    Boolean vdInteractive() {
        int id = keepActiveDisplayId.getAsInt();
        if (id <= 0 || Build.VERSION.SDK_INT < AndroidVersions.API_34_ANDROID_14) {
            return null;
        }
        try {
            return ServiceManager.getPowerManager().isScreenOn(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** How the panel was last switched, for the car and for the device tests. */
    String panelMethod() {
        return panelOffMethod;
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
        // Whether those pokes land. keptActive alone is a call counter, not evidence (see the field).
        m.put("keepActiveEffective", keepActiveEffective);
        m.put("keptActiveFallback", keptActiveFallback);
        // The car's own display group: it can be awake while the phone is not, and that is the
        // difference between "the phone is dark" and "the car's picture is dead".
        m.put("vdInteractive", vdInteractive());
        m.put("vdWakes", vdWakes);
        m.put("lastSleepVdInteractive", lastSleepVdInteractive);
        m.put("screenOffTimeout", screenOffTimeout);
        m.put("screenOffTimeoutWas", previousScreenOffTimeout);
        if (!panelOffMethod.isEmpty()) {
            m.put("panelOffMethod", panelOffMethod);
        }
        m.put("panelOffFailures", panelOffFailures);
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
                    Thread.sleep(carWatching.getAsBoolean() ? WATCH_WATCHING_MS : WATCH_IDLE_MS);
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
        if (keepActiveCheckAtMs != 0 && System.currentTimeMillis() >= keepActiveCheckAtMs) {
            checkKeepActiveEffective();
        }
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
            if (keepActiveCheckAtMs == 0 && keepActiveEffective == null && keepActiveChecks < KEEP_ACTIVE_CHECK_TRIES) {
                // Give the framework a moment to have written its warning, then read it back once.
                keepActiveCheckAtMs = now + KEEP_ACTIVE_CHECK_DELAY_MS;
            }
        } catch (Throwable t) {
            Ln.w("userActivity(" + id + ") failed: " + t);
            keepActive = false; // 이 ROM 에서 안 되면 매초 실패 로그를 남기지 않는다
            keepActiveEffective = Boolean.FALSE;
        }
        if (Boolean.FALSE.equals(keepActiveEffective) && keepActiveFallback) {
            // Castla's fallback: a WAKEUP aimed at the virtual display. See the field comment for why
            // this is not the default.
            try {
                Command.execReadOutput("input", "-d", String.valueOf(id), "keyevent",
                        String.valueOf(KeyEvent.KEYCODE_WAKEUP));
                keptActiveFallback++;
            } catch (Exception e) {
                Ln.w("keep-active fallback failed: " + e);
                keepActiveFallback = false;
            }
        }
    }

    /**
     * Did our userActivity() calls actually land? PowerManagerService answers in logcat and nowhere
     * else: without DEVICE_POWER/USER_ACTIVITY it logs "Ignoring call to PowerManager.userActivity()"
     * with the caller's pid and returns normally. Read that back once and stop guessing.
     */
    private void checkKeepActiveEffective() {
        keepActiveCheckAtMs = 0;
        keepActiveChecks++;
        try {
            String out = Command.execReadOutput("logcat", "-d", "-t", "400", "PowerManagerService:W", "*:S");
            // The warning names the caller, so a line about some other app is not about us. Note the
            // framework rate-limits it to one every 5 minutes, so "no line" is good evidence but not
            // proof - the device test cross-checks the same logcat from outside.
            boolean ignored = out.contains("Ignoring call to PowerManager.userActivity")
                    && out.contains("pid=" + android.os.Process.myPid());
            keepActiveEffective = !ignored;
            Ln.i("keep-active " + (ignored
                    ? "is being IGNORED by PowerManagerService (no DEVICE_POWER/USER_ACTIVITY for this uid)"
                    : "lands (no PowerManagerService warning for our pid)"));
        } catch (Exception e) {
            // Could not read logcat: leave it unknown rather than claim either answer.
            Ln.w("could not check whether keep-active lands: " + e);
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
        // Did the car's display group go down with the phone? On paper it should not - goToSleep()
        // only sleeps the default group - so record the answer, and when it did go down, put just
        // that group back up. That path never lights the panel, so there is no flash to undo.
        Boolean vdBefore = vdInteractive();
        lastSleepVdInteractive = vdBefore;
        if (vdWake && Boolean.FALSE.equals(vdBefore)) {
            int vd = keepActiveDisplayId.getAsInt();
            if (vd > 0 && ServiceManager.getPowerManager().wakeUpDisplay(vd)
                    && Boolean.TRUE.equals(vdInteractive())) {
                sleepRecoveries++;
                vdWakes++;
                Ln.i("the phone slept and took the car's display group with it — woke only that group"
                        + " (the phone stays dark)");
                onWakeSafely();
                return;
            }
        }
        sleepRecoveries++;
        Ln.i("the phone went to sleep while the car was watching — waking it and darkening the panel instead");
        wake(true);
    }

    /** The picture is coming back: ask for an IDR so the car does not wait out the GOP. */
    private void onWakeSafely() {
        try {
            onWake.run();
        } catch (Throwable t) {
            Ln.w("onWake failed: " + t);
        }
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
        return setMainScreen(on, null);
    }

    /**
     * @param via force one way of switching the panel: {@code power-mode}, {@code cmd-display} or
     *     {@code brightness}; null tries them in that order. Forcing exists so the fallbacks can be
     *     exercised on a device instead of sitting there as code nobody has ever run - a fallback that
     *     has never worked once is worse than no fallback, because it hides the failure.
     */
    boolean setMainScreen(boolean on, String via) {
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
            boolean ok;
            if (via == null || "power-mode".equals(via)) {
                ok = setPhysicalDisplaysPower(on);
                if (ok) {
                    panelOffMethod = "power-mode";
                } else {
                    panelOffFailures++;
                    ok = via == null && fallbackDisplayPower(on);
                }
            } else {
                ok = fallbackDisplayPower(on, via);
                if (!ok) {
                    panelOffFailures++;
                }
            }
            Ln.i("physical display power " + (on ? "on" : "off") + ": " + ok + " via " + panelOffMethod);
            if (ok) {
                forcedOff = !on;
            }
            return ok;
        } catch (Throwable t) {
            Ln.e("display power change failed: " + t);
            return false;
        }
    }

    /**
     * When SurfaceControl refuses (it does on some ROMs), try what the others try before giving up:
     * the {@code cmd display power-off} shell command Android 15 added, then dropping the panel's
     * brightness to zero (Extinguish's fallback). A dark panel that is still composing is worse than
     * a powered-off one, but it beats handing the driver a lit phone.
     */
    private boolean fallbackDisplayPower(boolean on) {
        return fallbackDisplayPower(on, null);
    }

    private boolean fallbackDisplayPower(boolean on, String only) {
        if (!"brightness".equals(only) && Build.VERSION.SDK_INT >= AndroidVersions.API_35_ANDROID_15) {
            try {
                String out = Command.execReadOutput("cmd", "display", on ? "power-on" : "power-off", "0");
                if (out == null || !out.toLowerCase(java.util.Locale.ROOT).contains("error")) {
                    panelOffMethod = "cmd-display";
                    return true;
                }
                Ln.w("cmd display power-" + (on ? "on" : "off") + ": " + out.trim());
            } catch (Exception e) {
                Ln.w("cmd display power failed: " + e);
            }
        }
        // Turning it back on this way restores full brightness, not whatever the user had: there is no
        // getter to read the old value first. Only ever reached when the real power switch was refused.
        if (!"cmd-display".equals(only) && setPhysicalDisplaysBrightness(on ? 1.0f : 0.0f)) {
            panelOffMethod = "brightness";
            return true;
        }
        panelOffMethod = "none";
        return false;
    }

    private static boolean setPhysicalDisplaysBrightness(float brightness) {
        if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
            return false;
        }
        boolean useDisplayControl = Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14
                && !SurfaceControl.hasGetPhysicalDisplayIdsMethod();
        long[] ids = useDisplayControl ? DisplayControl.getPhysicalDisplayIds() : SurfaceControl.getPhysicalDisplayIds();
        if (ids == null || ids.length == 0) {
            return false;
        }
        boolean allOk = true;
        for (long id : ids) {
            IBinder token = useDisplayControl ? DisplayControl.getPhysicalDisplayToken(id) : SurfaceControl.getPhysicalDisplayToken(id);
            allOk &= token != null && SurfaceControl.setDisplayBrightness(token, brightness);
        }
        return allOk;
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

    /**
     * Push the phone's own inactivity timer out of the way while we are streaming.
     *
     * {@link #stayAwake()} only works while the phone is charging - that is how Android defines
     * stay_on_while_plugged_in - so a phone carried into the car on battery has nothing holding it
     * up at all. screen_off_timeout does not care about charging, which makes it the only knob that
     * covers that case. scrcpy (--screen-off-timeout) and SecondScreen both do exactly this, and both
     * put the old value back; so do we, in {@link #restore()}.
     *
     * @param millis the value to set, as a string; null or empty leaves the setting alone
     */
    void setScreenOffTimeout(String millis) {
        if (millis == null || millis.isEmpty()) {
            return;
        }
        try {
            Integer.parseInt(millis);
        } catch (NumberFormatException e) {
            Ln.w("ignoring screen_off_timeout=" + millis + " (not a number of milliseconds)");
            return;
        }
        try {
            previousScreenOffTimeout = Settings.getAndPutValue("system", SCREEN_OFF_TIMEOUT, millis);
            screenOffTimeout = millis;
            Ln.i(SCREEN_OFF_TIMEOUT + " = " + millis + " (was " + previousScreenOffTimeout + ")");
        } catch (Throwable e) {
            Ln.w("Could not set " + SCREEN_OFF_TIMEOUT + ": " + e);
        }
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
        if (previousScreenOffTimeout != null) {
            try {
                Settings.putValue("system", SCREEN_OFF_TIMEOUT, previousScreenOffTimeout);
                Ln.i(SCREEN_OFF_TIMEOUT + " restored to " + previousScreenOffTimeout);
            } catch (SettingsException e) {
                Ln.w("Could not restore " + SCREEN_OFF_TIMEOUT + ": " + e);
            }
            previousScreenOffTimeout = null;
            screenOffTimeout = null;
        }
    }
}
