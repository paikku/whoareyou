package com.carcast.server;

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
            // The power button moves the same panel 📵 does; watch for it so our bookkeeping never lies.
            screen.startWatching();
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
        ServerMain.INSTANCE.run(opts, () -> {
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("uid", uid);
            extra.put("build", BuildConfig.SERVER_BUILD_ID);
            extra.putAll(screen.info());
            if (injector != null) {
                extra.put("injected", injector.injected());
                extra.put("injectFailed", injector.failed());
                extra.put("pointersDown", injector.pointersDown());
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
        }, injector == null ? null : () -> {
            // The car's socket died: let go of anything it was holding, or the next tap is a phantom pinch.
            injector.cancelAll();
            return kotlin.Unit.INSTANCE;
        }, (method, path, query) -> {
            if (!"/api/screen".equals(path)) {
                return null;
            }
            if ("POST".equals(method)) {
                boolean on = !"0".equals(query.get("on")) && !"false".equals(query.get("on"));
                boolean ok = screen.setMainScreen(on);
                return "{\"ok\":" + ok + ",\"screenOn\":" + screen.isMainScreenOn() + "}";
            }
            return "{\"screenOn\":" + screen.isMainScreenOn() + "}";
        }, () -> {
            screen.restore();
            return kotlin.Unit.INSTANCE;
        });
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
