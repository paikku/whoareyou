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
 * when the phone and the car use the same app. {@link #startApp} looks up where the app's task is first
 * ({@code am stack list}) and reports it; the move itself is what the driver asks for ("bring what I was watching
 * to the car"), so the default ({@code restart=never}, {@link com.carcast.core.StreamSession#DEFAULT_RESTART})
 * keeps it. {@code auto}/{@code always} force-stop first so the car gets a fresh copy instead. Either way a watcher
 * then reports in {@code /api/status} when the phone takes the app away, so the car can say so instead of showing
 * black.
 */
public final class DisplayVideoSource implements VideoSource {
    private static final String TAG = "DisplayVideoSource";
    /** How often the watcher asks Android where the launched app's task is (one `am stack list` per tick). */
    private static final long APP_WATCH_INTERVAL_MS = 5_000;
    /** While the picture is frozen, or just after the car launched an app, look sooner — the driver is waiting. */
    private static final long APP_WATCH_BUSY_INTERVAL_MS = 1_000;
    /** How long after a launch the ping-pong is likely, so the fast poll is worth its cost. */
    private static final long APP_WATCH_FAST_WINDOW_MS = 20_000;
    private static final int APP_WATCH_MAX_FAILURES = 3;

    private final DisplayCapture display;
    private final int bitRate;
    private final int maxFps;
    private final boolean constrainedBaseline;
    private H264Encoder encoder;
    private EncodedH264Sink sink;
    private volatile String lastApp = "";
    private volatile String lastPackage = "";
    /** Display the launched app's task was last seen on; null when it has no task (or was never looked up). */
    private volatile Integer appDisplay;
    private volatile long fastWatchUntilMs;
    private Thread appWatcher;

    public DisplayVideoSource(int width, int height, int dpi, boolean systemDecorations, int bitRate, int maxFps,
                              boolean constrainedBaseline) {
        this.display = new DisplayCapture(width, height, dpi, systemDecorations);
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.constrainedBaseline = constrainedBaseline;
    }

    @Override
    public void start(@NotNull MediaHub hub) {
        Workarounds.apply();
        sink = new EncodedH264Sink(hub, 33_333);
        EncodedH264Sink s = sink;
        encoder = new H264Encoder(display.width, display.height, bitRate, maxFps, constrainedBaseline, new H264Encoder.Output() {
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

    public int width() {
        return display.width;
    }

    public int height() {
        return display.height;
    }

    /**
     * Starts an app on the virtual display. {@code name} is a package (its launcher activity is resolved)
     * or an explicit component {@code pkg/.Activity}. {@code restart} is {@code never} (move or bring to front, the
     * default), {@code auto} (force-stop the app first only when its task is on another display) or {@code always}
     * (see the class comment).
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
        // 차에서 띄운 것만 센다. 홈과 최근앱을 최신순으로 세우는 근거이고, 서버를 껐다 켜도 남는다.
        AppHistory.used(pkg);
        fastWatchUntilMs = System.currentTimeMillis() + APP_WATCH_FAST_WINDOW_MS;
        startAppWatcher();
        // The picture is about to change completely. Without this the car waits up to I_FRAME_INTERVAL (2 s)
        // for the next IDR and shows the *previous* app's last frame meanwhile — the "app switch is slow"
        // feeling. Asking for a sync frame now cuts that to one frame time.
        requestKeyframe();
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
     *
     * The poll is adaptive, because the delay here is what the driver feels: a fixed 5 s poll means up to
     * five seconds of a stale picture before the car can say why. Two cheap hints tell us when to look often:
     *
     *  - the encoder's frame counter stopped (an empty virtual display has nothing to compose, so the
     *    picture is dead — but this is not reliable: on some ROMs the display keeps composing something
     *    after the task leaves, which is exactly what the virtual phone showed);
     *  - the car has just launched an app, which is when the ping-pong happens. For a short window after
     *    that, look every second.
     *
     * Otherwise stay lazy: `am stack list` is a process spawn and this thread runs for the whole drive.
     */
    private synchronized void startAppWatcher() {
        if (appWatcher != null && appWatcher.isAlive()) {
            return;
        }
        Thread t = new Thread(() -> {
            int failures = 0;
            long lastFrames = -1;
            while (!Thread.currentThread().isInterrupted()) {
                long frames = sink != null ? sink.getFrames() : 0;
                boolean stalled = frames == lastFrames;
                lastFrames = frames;
                boolean justLaunched = System.currentTimeMillis() < fastWatchUntilMs;
                try {
                    Thread.sleep(stalled || justLaunched ? APP_WATCH_BUSY_INTERVAL_MS : APP_WATCH_INTERVAL_MS);
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
        // Flags the display really carries, not the ones we asked for (see DisplayCapture.displayFlags).
        m.put("displayFlags", display.displayFlags());
        m.put("displayOwnGroup", display.has(DisplayCapture.FLAG_OWN_DISPLAY_GROUP));
        m.put("displayAlwaysUnlocked", display.has(DisplayCapture.FLAG_ALWAYS_UNLOCKED));
        m.put("encoder", encoder != null ? encoder.name() : null);
        m.put("encoderProfile", encoder != null ? encoder.profileNote() : null);
        // The profile the SPS really carries. The web client declares Baseline 3.0 regardless, so this
        // is the only place the encoder's actual profile shows up — and it decides whether a JS decoder
        // (Baseline only) can be a fallback for the car's Drive mode, where <video> is paused for us.
        m.put("codec", sink != null ? sink.getCodec() : null);
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
