package com.carcast.server;

import com.carcast.core.Log;
import com.carcast.core.TaskList;
import com.carcast.core.media.EncodedH264Sink;
import com.carcast.core.media.MediaHub;
import com.carcast.core.media.VideoSource;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.util.Command;

import android.view.Surface;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * The live source for the shell process: virtual display → H.264 encoder → fMP4 → clients.
 * Also starts Android apps on that display (`am start --display N`).
 *
 * <h3>One app, two displays</h3>
 * Android keeps one task per app (task affinity), whatever display it is on. {@code am start --display N} for an
 * app that already has a task does not start a second copy: ActivityStarter finds the task on any display and
 * <em>reparents it</em> to display N. So the app the user is using on the phone jumps to the car, mid-state,
 * re-laid-out for 1280x720 — and the phone's launcher pulls it back the same way the moment the user taps its
 * icon there, leaving the car with an empty display (black picture, idle encoder). That is the "conflict" seen
 * when the phone and the car use the same app. {@link #startApp} therefore looks up where the app's task is first
 * ({@code am stack list}) and, by default, force-stops it when it lives on another display so the car gets a fresh
 * copy and the phone keeps nothing half-moved; a watcher then reports in {@code /api/status} when the phone takes
 * the app away, so the car can say so instead of showing black.
 */
public final class DisplayVideoSource implements VideoSource {
    private static final String TAG = "DisplayVideoSource";
    /** How often the watcher asks Android where the launched app's task is (one `am stack list` per tick). */
    private static final long APP_WATCH_INTERVAL_MS = 5_000;
    private static final int APP_WATCH_MAX_FAILURES = 3;

    private final DisplayCapture display;
    private final int bitRate;
    private final int maxFps;
    private H264Encoder encoder;
    private EncodedH264Sink sink;
    private volatile String lastApp = "";
    private volatile String lastPackage = "";
    /** Display the launched app's task was last seen on; null when it has no task (or was never looked up). */
    private volatile Integer appDisplay;
    private Thread appWatcher;

    public DisplayVideoSource(int width, int height, int dpi, boolean systemDecorations, int bitRate, int maxFps) {
        this.display = new DisplayCapture(width, height, dpi, systemDecorations);
        this.bitRate = bitRate;
        this.maxFps = maxFps;
    }

    @Override
    public void start(@NotNull MediaHub hub) {
        Workarounds.apply();
        sink = new EncodedH264Sink(hub, 33_333);
        EncodedH264Sink s = sink;
        encoder = new H264Encoder(display.width, display.height, bitRate, maxFps, new H264Encoder.Output() {
            @Override
            public void onCodecConfig(byte[] annexB) {
                s.onCodecConfig(annexB);
            }

            @Override
            public void onFrame(byte[] annexB, long ptsUs, boolean keyframe) {
                s.onFrame(annexB, ptsUs, keyframe);
            }
        });
        try {
            Surface surface = encoder.open();
            display.start(surface);
            encoder.start();
        } catch (Throwable e) {
            stop();
            throw new RuntimeException("display/encoder start failed: " + e, e);
        }
    }

    @Override
    public void stop() {
        Thread w = appWatcher;
        if (w != null) {
            w.interrupt();
            appWatcher = null;
        }
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        display.release();
    }

    @Override
    public void requestKeyframe() {
        H264Encoder e = encoder;
        if (e != null) {
            e.requestKeyframe();
        }
    }

    public int displayId() {
        return display.displayId();
    }

    /** The virtual display is not rendering (its power group slept): the car is frozen on its last frame. */
    public boolean displayAsleep() {
        return display.isAsleep();
    }

    /** Raw android.view.Display state of the virtual display (2 = ON), -1 without a display. */
    public int displayState() {
        return display.state();
    }

    /**
     * Cheaper than recreating: ask DisplayManager to power this display on (Android 15+ requestDisplayPower —
     * the API scrcpy keeps disabled for the *phone* panel, but for a sleeping virtual display it is the one
     * that may switch the display's own power group back on without destroying the app). Returns whether the
     * display is ON afterwards; false lets the caller fall back to {@link #recoverDisplay()}.
     */
    public boolean tryDisplayPowerOn() {
        int id = display.displayId();
        if (id < 0 || android.os.Build.VERSION.SDK_INT < 35) {
            return false;
        }
        try {
            boolean ok = com.genymobile.scrcpy.wrappers.ServiceManager.getDisplayManager().requestDisplayPower(id, true);
            for (int i = 0; ok && i < 10; i++) {
                if (!display.isAsleep()) {
                    Log.INSTANCE.i(TAG, "requestDisplayPower(" + id + ", on): 가상 디스플레이 켜짐 (" + (i * 100) + " ms)");
                    return true;
                }
                Thread.sleep(100);
            }
            Log.INSTANCE.i(TAG, "requestDisplayPower(" + id + ", on) = " + ok + ", state " + display.state() + " — 재생성으로 넘어감");
        } catch (Throwable t) {
            Log.INSTANCE.w(TAG, "requestDisplayPower failed: " + t, null);
        }
        return false;
    }

    /**
     * Recovery after the phone slept: a fresh virtual display on the same encoder surface, then the last app
     * started on it again (its previous instance died with the old display). Returns a log line.
     */
    public synchronized String recoverDisplay() throws Exception {
        int old = display.displayId();
        display.recreate();
        int id = display.displayId();
        appDisplay = null;
        String msg = "가상 디스플레이 재생성: id " + old + " → " + id;
        String app = lastApp;
        if (!app.isEmpty()) {
            try {
                Map<String, Object> r = startApp(app, "auto");
                msg += ", 앱 " + app + " 다시 실행 (" + r.get("action") + ")";
            } catch (Exception e) {
                msg += ", 앱 " + app + " 재실행 실패: " + e.getMessage();
            }
        }
        requestKeyframe();
        Log.INSTANCE.i(TAG, msg);
        return msg;
    }

    public int width() {
        return display.width;
    }

    public int height() {
        return display.height;
    }

    /**
     * Starts an app on the virtual display. {@code name} is a package (its launcher activity is resolved)
     * or an explicit component {@code pkg/.Activity}. {@code restart} is {@code auto} (force-stop the app first
     * only when its task is on another display), {@code always} or {@code never} (see the class comment).
     * Returns the fields for the JSON reply: {@code result} (the {@code am} output), {@code action} — one of
     * {@code started} (no task existed), {@code restarted} (force-stopped, then started), {@code moved} (Android
     * reparented a task from another display, {@code restart=never}) or {@code front} (already on this display,
     * brought to front) — plus {@code package}, {@code fromDisplay} (where the task was, or null) and {@code display}.
     */
    public Map<String, Object> startApp(String name, String restart) throws IOException, InterruptedException {
        int id = display.displayId();
        if (id < 0) {
            throw new IOException("no virtual display");
        }
        String component = name;
        if (!name.contains("/")) {
            // `cmd package resolve-activity --brief <pkg>` prints the component on its last line
            String out = Command.execReadOutput("cmd", "package", "resolve-activity", "--brief", name);
            String[] lines = out.trim().split("\n");
            component = lines[lines.length - 1].trim();
            if (!component.contains("/")) {
                throw new IOException("no launcher activity for " + name + ": " + out.trim());
            }
        }
        String pkg = component.substring(0, component.indexOf('/'));
        Integer from = whereIs(pkg);
        boolean forceStop;
        if ("always".equals(restart)) {
            forceStop = true;
        } else if ("never".equals(restart)) {
            forceStop = false;
        } else {
            // auto: a task on another display would be moved, not copied — restart instead. Unknown (null) means
            // no task, or `am stack list` said nothing usable; either way a plain start is the old behaviour.
            forceStop = from != null && from != id;
        }
        String action = forceStop ? "restarted" : from == null ? "started" : from == id ? "front" : "moved";
        List<String> cmd = new ArrayList<>();
        cmd.add("am");
        cmd.add("start");
        if (forceStop) {
            cmd.add("-S"); // force-stop the target package before starting (what the user did by hand: close it on the phone)
        }
        cmd.add("--display");
        cmd.add(String.valueOf(id));
        cmd.add("-n");
        cmd.add(component);
        cmd.add("-a");
        cmd.add("android.intent.action.MAIN");
        cmd.add("-c");
        cmd.add("android.intent.category.LAUNCHER");
        String result = Command.execReadOutput(cmd.toArray(new String[0])).trim();
        // core Log, not Ln: these lines must reach /api/log (what the app and the laptop read), not only the file.
        Log.INSTANCE.i(TAG, "start app " + component + " on display " + id + " (" + action + ", was on display " + from + ", restart=" + restart + "): " + result);
        lastApp = component;
        lastPackage = pkg;
        appDisplay = id;
        startAppWatcher();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", result);
        m.put("action", action);
        m.put("package", pkg);
        m.put("fromDisplay", from);
        m.put("display", id);
        return m;
    }

    /**
     * The display the app's front-most task is on, or null when it has none. Any failure (no {@code am stack}
     * on this ROM, unexpected output) is logged and treated as "no task", which keeps the old plain-start path.
     */
    private Integer whereIs(String pkg) {
        try {
            String out = Command.execReadOutput("am", "stack", "list");
            return TaskList.INSTANCE.displayOf(out, pkg);
        } catch (Exception e) {
            Log.INSTANCE.w(TAG, "am stack list failed: " + e, null);
            return null;
        }
    }

    /** True when the launched app has a task that is not on our display: the car is looking at an empty display. */
    private boolean appOnPhone() {
        Integer d = appDisplay;
        return d != null && d != display.displayId();
    }

    /**
     * Polls where the launched app's task is and logs each change, so "the car went black" reads as "the phone
     * took the app" in the server log and in {@code /api/status} (appDisplay / appOnPhone). It only reports —
     * pulling the task back on its own would just fight the user for the app.
     */
    private synchronized void startAppWatcher() {
        if (appWatcher != null && appWatcher.isAlive()) {
            return;
        }
        Thread t = new Thread(() -> {
            int failures = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(APP_WATCH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
                String pkg = lastPackage;
                if (pkg.isEmpty()) {
                    continue;
                }
                Integer now;
                try {
                    now = TaskList.INSTANCE.displayOf(Command.execReadOutput("am", "stack", "list"), pkg);
                    failures = 0;
                } catch (Exception e) {
                    if (++failures >= APP_WATCH_MAX_FAILURES) {
                        Log.INSTANCE.w(TAG, "app watcher stopped, am stack list keeps failing: " + e, null);
                        return;
                    }
                    continue;
                }
                Integer before = appDisplay;
                if (now == null ? before == null : now.equals(before)) {
                    continue;
                }
                appDisplay = now;
                int vd = display.displayId();
                if (now == null) {
                    Log.INSTANCE.i(TAG, "앱 " + pkg + " 의 task가 사라짐 (종료됨)");
                } else if (now == vd) {
                    Log.INSTANCE.i(TAG, "앱 " + pkg + " 이 차 화면(display " + vd + ")으로 돌아옴");
                } else {
                    Log.INSTANCE.i(TAG, "폰이 앱 " + pkg + " 을 가져감 (display " + now + ") — 차 화면은 비어 있음. 차에서 ▶를 누르면 폰 쪽을 종료하고 다시 띄움");
                }
            }
        }, "app-watch");
        t.setDaemon(true);
        t.start();
        appWatcher = t;
    }

    @NotNull
    @Override
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "display");
        m.put("width", display.width);
        m.put("height", display.height);
        m.put("dpi", display.dpi);
        m.put("displayId", display.displayId());
        m.put("displayState", display.state()); // android.view.Display: 2 = ON, 1 = OFF; the VD sleeps with the phone
        m.put("displayAsleep", display.isAsleep());
        m.put("encoder", encoder != null ? encoder.name() : null);
        m.put("frames", sink != null ? sink.getFrames() : 0);
        m.put("keyframes", sink != null ? sink.getKeyframes() : 0);
        m.put("app", lastApp);
        // Where the launched app's task is now (watcher, every APP_WATCH_INTERVAL_MS): appOnPhone means Android
        // moved it to another display — the phone — and the car is streaming an empty display.
        m.put("appDisplay", appDisplay);
        m.put("appOnPhone", appOnPhone());
        return m;
    }
}
