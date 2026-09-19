package com.carcast.server;

import com.carcast.core.ServerMain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point run by {@code app_process} as the shell user.
 *
 * <pre>
 * CLASSPATH=$(pm path com.carcast | cut -d: -f2) app_process / com.carcast.server.Server &lt;build-id&gt; [port=3333]
 *     [display=1280x720/160] [bitrate=4000000] [fps=30] [profile=baseline|default] [decorations=false]
 *     [bitrate_mode=cbr|vbr|default] [intra_refresh=&lt;frames&gt;] [qp_i_min=&lt;0..51&gt;] [qp_i_max=&lt;0..51&gt;] [app=com.google.android.youtube] [source=clip]
 *     [stay_awake=true] [screen_off=false] [sleep_recovery=true] [keep_active=true]
 *     [screen_off_timeout=&lt;ms&gt;] [keep_active_fallback=false] [vd_wake=true]
 * </pre>
 * Extra endpoints: {@code POST /api/screen?on=0|1[&via=power-mode|cmd-display|brightness]} turns only the
 * phone's main display off/on (the virtual display keeps running); {@code GET /api/screen} reports it.
 * {@code GET /api/hotspot} reports whether the phone's hotspot is up — read only; see {@link Hotspot} for
 * why switching it is not ours to do.
 * {@code GET /api/apps[?refresh=1]} lists the apps the car can start (the car's own home),
 * {@code GET /api/icon?pkg=…} returns one app's icon, and {@code GET /api/tasks} lists what is running
 * and on which display (the car's own recents).
 * The video comes from a virtual display (scrcpy-style, M4) unless {@code source=clip} forces the bundled test clip.
 *
 * The build id is the git sha the APK was built from ({@link BuildConfig#SERVER_BUILD_ID}); a mismatch
 * means the caller and this dex come from different builds and is refused, like scrcpy does.
 * Why the shell user: Android 14+ silently drops TCP to a VPN's address that arrives on any other
 * interface (hotspot), but only for app-uid sockets. uid 2000 is exempt, so the car can reach us.
 */
public final class Server {
    /**
     * Default floor on the I-frame QP (H.264 QP 0..51; higher is coarser), i.e. the cap on how many bytes an IDR may
     * be: the encoder may not spend a finer QP than this on an I-frame. Why: the vendor's rate control spent 260~575 KB
     * on a 1080p IDR when the car asked for one (report #76) and that was the stall amplifier. Overridable per run
     * (`qp_i_min=`), per car (`POST /api/encoder?qp_i_min=`), 0 to leave it to the vendor. The value is a first guess —
     * `POST /api/bench?codec=avc&qp_i_min=N` measures what it buys on this phone (car-tests/model-y §13).
     *
     * <p>Builds e0ee6d2 ~ 966a320 shipped `qp_i_max=28` for this purpose, which is the opposite bound (a quality
     * floor) and made requested IDRs twice as big; `qp_i_max` stays a knob, default off.
     */
    static final int DEFAULT_QP_I_MIN = 28;
    static final int DEFAULT_QP_I_MAX = 0;

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
                // profile=baseline (default) asks the encoder for Constrained Baseline so a JS decoder on
                // the car side can read the stream; profile=default leaves the vendor's choice (High on
                // S26U) alone. A rejected request falls back on its own — see H264Encoder.open().
                boolean constrainedBaseline = !"default".equals(raw.getOrDefault("profile", "baseline"));
                // bitrate_mode=cbr (default) asks for steady frame sizes; "default" leaves the vendor's choice.
                // intra_refresh=N spreads intra macroblocks over N frames instead of periodic IDRs (vendor-dependent, off by default).
                String mode = raw.getOrDefault("bitrate_mode", "cbr");
                String bitrateMode = "default".equals(mode) ? "" : mode;
                if (!bitrateMode.isEmpty() && !"cbr".equals(bitrateMode) && !"vbr".equals(bitrateMode)) {
                    throw new IllegalArgumentException("bitrate_mode must be cbr, vbr or default");
                }
                int intraRefresh = Integer.parseInt(raw.getOrDefault("intra_refresh", "0"));
                // qp_i_min=N caps how many bytes an I-frame may be by forbidding a finer QP (0: vendor's choice). Default on:
                // the S26U's requested IDRs at 1080p ran to 575 KB and that was what stalled the car (report #76).
                // qp_i_max=N is the opposite bound (a quality floor), off by default — see DEFAULT_QP_I_MIN.
                int qpIMin = Integer.parseInt(raw.getOrDefault("qp_i_min", String.valueOf(DEFAULT_QP_I_MIN)));
                int qpIMax = Integer.parseInt(raw.getOrDefault("qp_i_max", String.valueOf(DEFAULT_QP_I_MAX)));
                display = new DisplayVideoSource(d[0], d[1], d[2], decorations, bitRate, fps, constrainedBaseline, bitrateMode, intraRefresh, qpIMin, qpIMax);
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
                injectorTmp = new InputInjector(source::width, source::height, source::displayId);
                // Touches are stamped into the same ledger the encoder reports into, so "how long did Android
                // take to answer this touch" and "how long did the encoder take" are two numbers, not one.
                injectorTmp.timing(source.timing());
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
            // The power button moves the same panel 📵 does; watch for it so our bookkeeping never lies,
            // and resync the car's picture the moment the phone comes back.
            if (source != null) {
                screen.setOnWake(source::requestKeyframe);
            }
            // Unlike stay_awake, this one works on battery too - the phone carried into the car
            // without a cable is exactly the case stay_on_while_plugged_in does not cover.
            screen.setScreenOffTimeout(raw.get("screen_off_timeout"));
            screen.setSleepRecovery(!"false".equals(raw.get("sleep_recovery")));
            screen.setKeepActive(!"false".equals(raw.get("keep_active")));
            screen.setKeepActiveFallback("true".equals(raw.get("keep_active_fallback")));
            screen.setVdWake(!"false".equals(raw.get("vd_wake")));
            if (source != null) {
                screen.setKeepActiveDisplay(source::displayId);
            }
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
            // 차 홈이 비어 보일 때 "앱이 없다"인지 "못 읽었다"인지를 리포트만 보고 가를 수 있게.
            extra.putAll(AppList.info());
            // One line in /api/status so the app (and the widget) can show hotspot state without a second request.
            extra.put("hotspot", Hotspot.info());
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
            // The car's own home: the apps it can start. Names only — see AppList's class comment for
            // why icons are not allowed to ride along.
            if ("/api/apps".equals(path) && "GET".equals(method)) {
                return AppList.json("1".equals(query.get("refresh")));
            }
            // One icon, fetched as the car draws each tile.
            if ("/api/icon".equals(path) && "GET".equals(method)) {
                return AppList.iconJson(query.get("pkg"));
            }
            // The car's own recents: what is running, and on which display.
            if ("/api/tasks".equals(path) && "GET".equals(method)) {
                return RunningTasks.json(source == null ? -1 : source.displayId());
            }
            // The phone's own hotspot, so "everything off" is one press instead of a trip to Settings.
            // Loopback only, exactly like /api/stop: the car reaches us *over* that hotspot and must not
            // be able to switch it off underneath itself, and neither may anything else sharing it.
            if ("/api/hotspot".equals(path)) {
                return Hotspot.route(method);
            }
            // Encoder bench: one codec, a few seconds of synthetic frames, its latency and IDR sizes — the phone's half
            // of "would HEVC or AV1 help", without a car and without touching the live encoder (EncoderBench).
            if ("/api/bench".equals(path) && "POST".equals(method)) {
                return com.carcast.core.Json.INSTANCE.obj(EncoderBench.run(query));
            }
            // The encoder at runtime: GET says what it is, POST ?width=&height=&fps=&bitrate=&profile=&intra_refresh=
            // rebuilds it (the display and the app stay) — except a bitrate-only POST, which the running codec takes
            // live (see DisplayVideoSource.reconfigure). The car's quality picker, its automatic step-down and its
            // adaptive bitrate all come through here, and a car whose renderer is WebCodecs asks for profile=high —
            // Baseline only exists for the WASM decoder, and High is the same picture for fewer bits.
            if ("/api/encoder".equals(path)) {
                if (source == null) {
                    return "{\"ok\":false,\"error\":\"no display source\"}";
                }
                if (!"POST".equals(method)) {
                    return com.carcast.core.Json.INSTANCE.obj(source.encoderInfo());
                }
                try {
                    Map<String, Object> info = source.reconfigure(
                            intOrNull(query.get("width")), intOrNull(query.get("height")),
                            intOrNull(query.get("fps")), intOrNull(query.get("bitrate")), query.get("profile"),
                            intOrNull(query.get("intra_refresh")), intOrNull(query.get("qp_i_min")), intOrNull(query.get("qp_i_max")));
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", true);
                    out.putAll(info);
                    return com.carcast.core.Json.INSTANCE.obj(out);
                } catch (Exception e) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("ok", false);
                    out.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
                    return com.carcast.core.Json.INSTANCE.obj(out);
                }
            }
            if (!"/api/screen".equals(path)) {
                return null;
            }
            if ("POST".equals(method)) {
                boolean on = !"0".equals(query.get("on")) && !"false".equals(query.get("on"));
                // ?via=power-mode|cmd-display|brightness forces one way, so a device can be asked which
                // of them actually works on it instead of us guessing. Omitted: try them in order.
                boolean ok = screen.setMainScreen(on, query.get("via"));
                return "{\"ok\":" + ok + ",\"screenOn\":" + screen.isMainScreenOn()
                        + ",\"via\":\"" + screen.panelMethod() + "\"}";
            }
            return "{\"screenOn\":" + screen.isMainScreenOn() + "}";
        }, () -> {
            screen.restore();
            return kotlin.Unit.INSTANCE;
        }, session -> {
            // Only undo a sleep while someone is actually watching from the car.
            screen.setCarWatching(() -> session.getVideoClients() > 0);
            return kotlin.Unit.INSTANCE;
        });
    }

    private static Integer intOrNull(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        return Integer.parseInt(s);
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
