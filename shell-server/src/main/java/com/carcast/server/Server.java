package com.carcast.server;

import com.carcast.core.Log;
import com.carcast.core.ServerMain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point run by {@code app_process} as the shell user.
 *
 * <pre>
 * CLASSPATH=$(pm path com.carcast | cut -d: -f2) app_process / com.carcast.server.Server &lt;build-id&gt; [port=3333]
 *     [display=1280x720/160] [bitrate=4000000] [fps=30] [decorations=false] [app=com.google.android.youtube] [source=clip]
 *     [stay_awake=true] [screen_off=false]
 * </pre>
 * Extra endpoints: {@code POST /api/screen?on=0|1} turns only the phone's main display off/on (the virtual
 * display keeps running); {@code GET /api/screen} reports it.
 * The video comes from a virtual display (scrcpy-style, M4) unless {@code source=clip} forces the bundled test clip.
 *
 * The build id is the git sha the APK was built from ({@link BuildConfig#SERVER_BUILD_ID}); a mismatch
 * means the caller and this dex come from different builds and is refused, like scrcpy does.
 * Why the shell user: Android 14+ silently drops TCP to a VPN's address that arrives on any other
 * interface (hotspot), but only for app-uid sockets. uid 2000 is exempt, so the car can reach us.
 */
public final class Server {
    private Server() {
    }

    /** Like scrcpy: a main Looper so framework classes that assume one (DisplayManagerGlobal, MediaCodec) are happy. */
    private static void prepareMainLooper() {
        android.os.Looper.prepare();
        try {
            java.lang.reflect.Field field = android.os.Looper.class.getDeclaredField("sMainLooper");
            field.setAccessible(true);
            field.set(null, android.os.Looper.myLooper());
        } catch (ReflectiveOperationException e) {
            System.err.println("carcast-server: could not set main looper: " + e);
        }
    }

    /** Progress marker in the log: with only server.log to go on, the last marker says where a startup crash happened. */
    private static void step(String what) {
        System.out.println("carcast-server step: " + what);
    }

    public static void main(String... args) {
        // Detached (daemon) runs redirect stdout/stderr to a log file; make lines flush and be UTF-8,
        // and make sure any uncaught crash lands in that log instead of vanishing.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err), true, java.nio.charset.StandardCharsets.UTF_8));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.out.println("carcast-server: FATAL on thread " + t.getName() + ": " + e);
            e.printStackTrace(System.out);
            System.out.flush();
        });
        prepareMainLooper();
        if (args.length == 0 || !BuildConfig.SERVER_BUILD_ID.equals(args[0])) {
            System.err.println("carcast-server: build id mismatch, expected " + BuildConfig.SERVER_BUILD_ID
                    + " got " + (args.length == 0 ? "(none)" : args[0]));
            System.exit(1);
            return;
        }
        final int uid = android.os.Process.myUid();
        System.out.println("carcast-server uid=" + uid + " build=" + BuildConfig.SERVER_BUILD_ID
                + " android=" + android.os.Build.VERSION.RELEASE);
        if (uid >= 10000) {
            System.out.println("warning: running as an app uid; hotspot clients will not reach the VPN address");
        }
        final ServerMain.Options opts;
        try {
            opts = ServerMain.INSTANCE.parse(args);
        } catch (RuntimeException e) {
            System.err.println("carcast-server: " + e.getMessage());
            System.exit(2);
            return;
        }
        step("options " + opts.getRaw());
        // First thing, before anything slow: get out of adbd's cgroup, or the server dies with adbd when
        // wireless debugging turns off. The report says whether it worked; the app shows it.
        step("cgroup: " + CgroupEscape.apply());
        Map<String, String> raw = opts.getRaw();
        if (!"clip".equals(raw.get("source"))) {
            // Like scrcpy: fake an app context before touching framework classes (KeyCharacterMap, DisplayManager,
            // MediaCodec) from a bare app_process; a failure here is logged, never fatal.
            try {
                com.genymobile.scrcpy.Workarounds.apply();
                step("workarounds applied");
            } catch (Throwable t) {
                System.out.println("carcast-server: workarounds failed (continuing): " + t);
            }
        }
        DisplayVideoSource display = null;
        if (!"clip".equals(raw.get("source"))) {
            try {
                int[] d = parseDisplay(raw.getOrDefault("display", "1280x720/160"));
                int bitRate = Integer.parseInt(raw.getOrDefault("bitrate", "4000000"));
                int fps = Integer.parseInt(raw.getOrDefault("fps", "30"));
                boolean decorations = "true".equals(raw.get("decorations"));
                display = new DisplayVideoSource(d[0], d[1], d[2], decorations, bitRate, fps);
            } catch (RuntimeException e) {
                System.err.println("carcast-server: bad display options: " + e.getMessage());
                System.exit(2);
                return;
            }
        }
        final DisplayVideoSource source = display;
        step(source == null ? "source clip" : "source display " + source.width() + "x" + source.height());
        final String initialApp = raw.get("app");
        final ScreenPower screen = new ScreenPower();
        InputInjector injectorTmp = null;
        if (source != null) {
            try {
                injectorTmp = new InputInjector(source.width(), source.height(), source::displayId);
            } catch (Throwable t) {
                System.out.println("carcast-server: input injector unavailable: " + t);
            }
        }
        final InputInjector injector = injectorTmp;
        step(injector == null ? "no input injector" : "input injector ready");
        try {
            if (!"false".equals(raw.get("stay_awake"))) {
                screen.stayAwake();
            }
            if ("true".equals(raw.get("screen_off"))) {
                screen.setMainScreen(false);
            }
        } catch (Throwable t) {
            System.out.println("carcast-server: screen setup skipped: " + t);
        }
        step("screen setup done");
        if (source != null && initialApp != null) {
            // Give the display a moment to exist, then launch the requested app on it.
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(1500);
                    System.out.println("carcast-server app: " + source.startApp(initialApp, "auto"));
                } catch (Exception e) {
                    System.err.println("carcast-server: could not start " + initialApp + ": " + e);
                }
            }, "start-app");
            t.setDaemon(true);
            t.start();
        }
        step("starting http on port " + opts.getPort());
        final PhoneWatch watch = new PhoneWatch(screen, source);
        ServerMain.INSTANCE.run(opts, () -> {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("uid", uid);
            extra.put("build", BuildConfig.SERVER_BUILD_ID);
            extra.put("screenOn", screen.isMainScreenOn());
            // asleep: the phone went to sleep (power button / timeout) and the virtual display with it — the car
            // is frozen until PhoneWatch recovers it (or 📵 / /api/screen?on=0 does).
            extra.put("asleep", watch.asleep());
            extra.put("keptAwake", screen.isKeptAwake());
            extra.put("recoveries", watch.recoveries);
            if (injector != null) {
                extra.put("injected", injector.injected());
                extra.put("injectFailed", injector.failed());
            }
            return extra;
        }, !opts.getDaemon(), source, source == null ? null : (name, restart) -> {
            try {
                return source.startApp(name, restart);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, injector == null ? null : msg -> {
            injector.handle(msg);
            return kotlin.Unit.INSTANCE;
        }, (method, path, query) -> {
            if (!"/api/screen".equals(path)) {
                return null;
            }
            if ("POST".equals(method)) {
                boolean on = !"0".equals(query.get("on")) && !"false".equals(query.get("on"));
                // 📵 on a sleeping phone: bring the stream back first (wake + fresh display), then the panel as asked.
                String recovered = watch.asleep() ? watch.recover("📵") : null;
                boolean ok = screen.setMainScreen(on);
                return "{\"ok\":" + ok + ",\"screenOn\":" + screen.isMainScreenOn() + ",\"asleep\":" + watch.asleep()
                        + (recovered != null ? ",\"recovered\":" + com.carcast.core.Json.INSTANCE.str(recovered) : "") + "}";
            }
            return "{\"screenOn\":" + screen.isMainScreenOn() + ",\"asleep\":" + watch.asleep() + "}";
        }, () -> {
            watch.stop();
            screen.restore();
            return kotlin.Unit.INSTANCE;
        }, n -> {
            watch.clients(n);
            return kotlin.Unit.INSTANCE;
        });
    }

    /**
     * Watches the phone's power state on behalf of the car.
     * <ul>
     * <li>Keeps the phone from timing out while a car is streaming: the screen timeout goes out when the first
     * video client attaches and comes back {@link #RELEASE_GRACE_MS} after the last one leaves (the car
     * reconnects often; do not flap the setting); PowerManager is poked every
     * {@link ScreenPower#USER_ACTIVITY_INTERVAL_MS} meanwhile.</li>
     * <li>Recovers from the power button: a phone that sleeps takes the virtual display with it ("차가 멈춤").
     * While a car is connected the first sleep is undone within a second or two — wake the device, and since
     * the virtual display's own power group does not wake with it, recreate the display and relaunch the app —
     * and the panel is turned off through SurfaceControl, which is what the driver meant by pressing power.
     * A second sleep within {@link #RECOVER_BACKOFF_MS} is taken as "I want my phone" and left alone
     * (the car shows 😴 and 📵 recovers on request).</li>
     * </ul>
     */
    private static final class PhoneWatch {
        private static final long RELEASE_GRACE_MS = 15_000;
        private static final long RECOVER_BACKOFF_MS = 60_000;
        private static final String TAG = "PhoneWatch";
        private final ScreenPower screen;
        private final DisplayVideoSource source;
        private final Thread thread;
        private volatile int clients;
        private volatile long lastClientGoneAt;
        private volatile long lastRecoverAt;
        private volatile boolean stopped;
        private volatile boolean asleepLogged;
        volatile int recoveries;

        PhoneWatch(ScreenPower screen, DisplayVideoSource source) {
            this.screen = screen;
            this.source = source;
            thread = new Thread(this::loop, "phone-watch");
            thread.setDaemon(true);
            thread.start();
        }

        /** Asleep by either signal: PowerManager says non-interactive, or the virtual display itself is not ON. */
        boolean asleep() {
            return screen.isAsleep() || (source != null && source.displayAsleep());
        }

        void clients(int n) {
            if (n == 0 && clients > 0) {
                lastClientGoneAt = System.currentTimeMillis();
            }
            clients = n;
            if (n > 0) {
                screen.keepAwake(true);
            }
        }

        void stop() {
            stopped = true;
            thread.interrupt();
        }

        /** Wake, get a rendering virtual display back, darken the panel. Serialized; returns what was done. */
        synchronized String recover(String why) {
            lastRecoverAt = System.currentTimeMillis();
            recoveries++;
            screen.slept();
            StringBuilder did = new StringBuilder(why).append(": ");
            did.append(screen.wake() ? "폰 깨움" : "폰이 안 깨어남");
            if (source != null) {
                // Give the display group a moment to follow the device before deciding it did not.
                for (int i = 0; i < 10 && source.displayAsleep(); i++) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                if (source.displayAsleep()) {
                    try {
                        did.append(", VD는 그대로 꺼져 있음 → ").append(source.recoverDisplay());
                    } catch (Exception e) {
                        did.append(", VD 재생성 실패: ").append(e);
                    }
                } else {
                    did.append(", VD 켜짐");
                }
            }
            did.append(", 패널 끄기: ").append(screen.setMainScreen(false));
            Log.INSTANCE.i(TAG, did.toString());
            return did.toString();
        }

        private void loop() {
            long lastPoke = 0;
            while (!stopped) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (clients > 0) {
                    if (now - lastPoke >= ScreenPower.USER_ACTIVITY_INTERVAL_MS) {
                        lastPoke = now;
                        screen.userActivity();
                    }
                    boolean asleep = asleep();
                    if (asleep && now - lastRecoverAt >= RECOVER_BACKOFF_MS) {
                        asleepLogged = false;
                        Log.INSTANCE.i(TAG, "폰이 잠듦 (전원 버튼/시간 초과) — 차가 연결돼 있어 깨우고 패널만 끕니다. 폰을 쓰려면 1분 안에 한 번 더 끄세요");
                        recover("자동 복구");
                    } else if (asleep && !asleepLogged) {
                        asleepLogged = true;
                        Log.INSTANCE.i(TAG, "폰이 1분 안에 다시 잠듦 — 폰을 쓰려는 것으로 보고 그대로 둡니다 (차에서 📵로 복구)");
                    } else if (!asleep) {
                        asleepLogged = false;
                    }
                } else if (screen.isKeptAwake() && now - lastClientGoneAt >= RELEASE_GRACE_MS) {
                    screen.keepAwake(false);
                }
            }
        }
    }

    /** "1280x720/160" → {width, height, dpi}; dpi defaults to 160. */
    static int[] parseDisplay(String spec) {
        String[] sizeAndDpi = spec.split("/");
        String[] wh = sizeAndDpi[0].split("x");
        if (wh.length != 2) {
            throw new IllegalArgumentException("display must be WxH[/dpi], got '" + spec + "'");
        }
        int w = Integer.parseInt(wh[0]);
        int h = Integer.parseInt(wh[1]);
        int dpi = sizeAndDpi.length > 1 ? Integer.parseInt(sizeAndDpi[1]) : 160;
        if (w < 64 || h < 64 || w > 4096 || h > 4096 || dpi < 60 || dpi > 640) {
            throw new IllegalArgumentException("display out of range: " + spec);
        }
        return new int[] {w, h, dpi};
    }
}
