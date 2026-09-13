package com.carcast.server;

import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Settings;
import com.genymobile.scrcpy.util.SettingsException;

import android.content.Context;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Turns the phone's Wi-Fi hotspot on and off, so the app can do "everything off" (and back on) in one
 * press instead of sending the user into Settings while the car waits.
 *
 * Why here and not in the app: there is no public API for the *tethered* hotspot. The app uid has only
 * {@code WifiManager.startLocalOnlyHotspot}, whose SSID and password are random on every call — the car
 * would have to be re-paired each time, which defeats the point. The tethered hotspot is behind
 * {@code android.net.TetheringManager} (@SystemApi, so reflection) and {@code TETHER_PRIVILEGED}, which the
 * Shell package holds and we run as. Same reason the rest of this process exists.
 *
 * {@code cmd wifi start-softap} is not an option either: WifiShellCommand refuses every privileged command
 * unless the caller is root, and uid 2000 is not.
 *
 * Carrier locks: {@code setExemptFromEntitlementCheck(true)} plus clearing {@code tether_dun_required} is what
 * Castla found necessary on carrier-locked Samsung devices (docs/prior-art.md §4); without them a carrier
 * build answers the request with TETHER_ERROR_PROVISIONING_FAILED. Both are best effort and reported.
 * But the exemption is not free — it is also what decides whether the request may only be made by a caller
 * holding TETHER_PRIVILEGED, which shell does not have on every build. {@link #doStart} therefore asks twice,
 * and its comment carries the measurement that settled the order.
 *
 * Note that {@code tether_dun_required} is <b>not</b> restored, unlike the settings {@link ScreenPower}
 * borrows. Putting it back while the AP is up invites the framework to re-run the very check we just
 * skipped, and the thing that would drop is the car's link — mid-drive. What it was before is reported in
 * {@code detail} on every switch, so the change is visible rather than silent; a phone that wants it back
 * needs one {@code settings put global tether_dun_required 1} with the hotspot off.
 *
 * Everything here is reflection against hidden API, so every step reports what it did: a device that
 * refuses must say so in /api/hotspot rather than look like a hotspot that did not come up.
 */
final class Hotspot {
    /** TetheringManager.TETHERING_WIFI. */
    private static final int TETHERING_WIFI = 0;
    /** WifiManager.WIFI_AP_STATE_* (hidden constants, stable since Android 4). */
    private static final int WIFI_AP_STATE_ENABLING = 12;
    private static final int WIFI_AP_STATE_ENABLED = 13;
    /** How long to wait for the tethering callback before answering "still pending". */
    private static final long DEFAULT_WAIT_MS = 15_000;
    private static final long MAX_WAIT_MS = 60_000;
    /**
     * Interface names a Wi-Fi AP is brought up on, when nothing else will tell us the state: swlan0 is
     * Samsung's, ap0/softap0 are Qualcomm/AOSP, wlan1 is the second radio on dual-band devices.
     */
    private static final String[] AP_INTERFACES = {"swlan0", "softap0", "ap0", "wlan1"};

    /**
     * TetheringManager.TETHER_ERROR_* by value. The callback hands back a bare int, and "error=14" in a
     * log tells a reader nothing — the difference between "this phone will not let us" and "the radio
     * failed" is the whole diagnosis.
     */
    private static final String[] TETHER_ERRORS = {
        "NO_ERROR", "UNKNOWN_IFACE", "SERVICE_UNAVAIL", "UNSUPPORTED", "UNAVAIL_IFACE", "INTERNAL_ERROR",
        "TETHER_IFACE_ERROR", "UNTETHER_IFACE_ERROR", "ENABLE_FORWARDING_ERROR", "DISABLE_FORWARDING_ERROR",
        "IFACE_CFG_ERROR", "PROVISIONING_FAILED", "DHCPSERVER_ERROR", "ENTITLEMENT_UNKNOWN",
        "NO_CHANGE_TETHERING_PERMISSION", "NO_ACCESS_TETHERING_PERMISSION", "UNKNOWN_TYPE",
    };

    /** TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION: the one failure we have a second thing to try after. */
    private static final int TETHER_ERROR_NO_CHANGE_PERMISSION = 14;

    static String errorName(Object code) {
        if (!(code instanceof Integer)) {
            return String.valueOf(code);
        }
        int i = (Integer) code;
        String name = i >= 0 && i < TETHER_ERRORS.length ? TETHER_ERRORS[i] : "TETHER_ERROR_" + i;
        return name + "(" + i + ")";
    }

    private Hotspot() {
    }

    /** The last thing start/stop did, so a failure survives long enough for the app to show it. */
    private static volatile String lastAction = "";
    private static volatile String lastError = "";
    /**
     * This phone will not let uid 2000 change tethering at all — both the privileged request and the plain
     * one came back NO_CHANGE_TETHERING_PERMISSION. Worth a flag of its own rather than another error
     * string: it is not a failure to retry but a property of the build, and the only useful thing left to
     * tell the driver is "switch it on in Settings". Measured on the API 36 emulator (run #42); whether
     * One UI grants it is the open question in docs/verification-log.md.
     */
    private static volatile boolean permissionDenied;

    /**
     * /api/status carries the hotspot on every request, and the app polls it twice a second while the car
     * is watching. Reading the state enumerates every network interface, so a short cache keeps a status
     * request from doing that work over and over; anything that changes the state clears it.
     */
    private static final long STATE_CACHE_MS = 750;
    private static volatile State cachedState;
    private static volatile long cachedAt;

    /**
     * The whole {@code /api/hotspot} endpoint, so the rule that matters lives next to what it protects
     * rather than in a lambda nobody can call from a test.
     *
     * Loopback only for writes, exactly like {@code /api/stop}: the car reaches the phone *over* this
     * hotspot, and so does anything else on it. Reads are open — the state is already visible to anyone
     * who can see the SSID.
     */
    static String route(String method, Map<String, String> query, String remote) {
        if (!"POST".equals(method)) {
            return statusJson();
        }
        if (remote == null || !remote.startsWith("127.")) {
            return "{\"ok\":false,\"error\":\"loopback only\"}";
        }
        String on = query == null ? null : query.get("on");
        boolean wanted = !"0".equals(on) && !"false".equals(on);
        return set(wanted, query == null ? null : query.get("wait"));
    }

    /** JSON for {@code GET /api/hotspot}. */
    static String statusJson() {
        return json(info());
    }

    /**
     * The fields that describe the hotspot, in one place so {@code /api/hotspot}, {@code /api/status} and
     * the reply to a switch can never come to disagree about what they are called.
     */
    private static Map<String, Object> fields(State s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", s.on);
        m.put("known", s.known);
        m.put("via", s.via);
        m.put("interfaces", s.interfaces);
        // "Controllable" has to mean the caller can actually switch it, not merely that a service answered:
        // a build that refuses uid 2000 leaves the app nothing to offer but the Settings screen.
        //
        // It starts out optimistic and can only ever go false, because the question cannot be answered
        // without asking: a static permission check on TETHER_PRIVILEGED would say no on a phone where the
        // WRITE_SETTINGS path still works. So the first refusal is what settles it — until one arrives,
        // "true" means "nothing has proven otherwise", not "this will work".
        m.put("controllable", tetheringManager() != null && !permissionDenied);
        if (permissionDenied) {
            m.put("permissionDenied", true);
        }
        return m;
    }

    static Map<String, Object> info() {
        Map<String, Object> m = fields(state());
        if (!lastAction.isEmpty()) {
            m.put("lastAction", lastAction);
        }
        if (!lastError.isEmpty()) {
            m.put("lastError", lastError);
        }
        return m;
    }

    /**
     * {@code POST /api/hotspot?on=0|1[&wait=<ms>]}. Blocks until the tethering callback answers (or the AP
     * state agrees) so the caller can sequence the rest — turning the server off before the hotspot is
     * actually down would leave the hotspot on with nothing able to turn it off.
     */
    static synchronized String set(boolean on, String waitSpec) {
        long wait = DEFAULT_WAIT_MS;
        if (waitSpec != null) {
            try {
                wait = Math.max(0, Math.min(MAX_WAIT_MS, Long.parseLong(waitSpec.trim())));
            } catch (NumberFormatException ignored) {
                // keep the default; a bad wait= is not worth failing the request over
            }
        }
        lastAction = (on ? "start" : "stop") + " @" + System.currentTimeMillis();
        lastError = "";
        State before = freshState();
        if (before.known && before.on == on) {
            return reply(true, "already " + (on ? "on" : "off"), before);
        }
        Object tm = tetheringManager();
        if (tm == null) {
            lastError = "no tethering service";
            return reply(false, "no tethering service (uid " + uid() + "; TETHER_PRIVILEGED is the shell's, not an app's)", before);
        }
        Outcome outcome;
        try {
            outcome = on ? doStart(tm, wait) : doStop(tm, wait);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return reply(false, "reflection failed: " + t, freshState());
        }
        State after = freshState();
        // Three ways to be satisfied, in order of how much they prove: the framework said it worked, the
        // phone now reads the way we asked, or nobody would tell us and the call itself raised nothing.
        // The AP can take longer to come up than the caller was willing to wait, so a confirmed callback
        // must not be overruled by a state that has not caught up yet.
        boolean ok = outcome.confirmed || (after.known ? after.on == on : outcome.accepted);
        if (!ok) {
            lastError = outcome.detail;
        }
        return reply(ok, outcome.detail, after);
    }

    /** What a switch attempt amounted to: what we were told, and what we can say about it. */
    private static final class Outcome {
        /** The framework, or the phone's own state, said the switch happened. */
        final boolean confirmed;
        /** The call was made and raised nothing — all there is to go on when the phone will not report. */
        final boolean accepted;
        final String detail;

        Outcome(boolean confirmed, boolean accepted, String detail) {
            this.confirmed = confirmed;
            this.accepted = accepted;
            this.detail = detail;
        }
    }

    // --- doing it -------------------------------------------------------------------------------

    /**
     * Two attempts, and the order is the finding rather than a guess.
     *
     * {@code setExemptFromEntitlementCheck(true)} is not free: TetheringService passes that very flag as its
     * {@code onlyAllowPrivileged} argument, so asking to skip the carrier check turns the whole request into
     * one that {@code TETHER_PRIVILEGED} alone may make. A caller without it is refused outright with
     * NO_CHANGE_TETHERING_PERMISSION — which is exactly what the API 36 emulator answered us (run #41), and
     * it never reaches the second path, where a plain request is allowed through the caller's WRITE_SETTINGS.
     *
     * So: ask for the exemption first, because a phone that grants us TETHER_PRIVILEGED both accepts it and
     * needs it on carrier-locked builds — and when that is refused for permission, ask again without it.
     * Clearing {@code tether_dun_required} is what keeps that second attempt viable: the WRITE_SETTINGS path
     * is closed while the phone still believes provisioning is required.
     */
    private static Outcome doStart(Object tm, long waitMs) throws Exception {
        String dun = clearDunRequirement();
        long half = Math.max(1_000, waitMs / 2);
        Attempt privileged = attemptStart(tm, true, half);
        if (!privileged.permissionDenied) {
            return new Outcome(privileged.started, !privileged.failed,
                    "startTethering(exempt): " + privileged.detail + "; dun: " + dun);
        }
        Attempt plain = attemptStart(tm, false, waitMs - half);
        if (plain.permissionDenied) {
            // Both doors are shut: this build does not let shell change tethering, and no further call will.
            permissionDenied = true;
        }
        return new Outcome(plain.started, !plain.failed,
                "startTethering(exempt): " + privileged.detail
                        + " → 권한 없음, 면제 없이 재시도: " + plain.detail
                        + (plain.permissionDenied ? " — 이 폰은 앱이 핫스팟을 바꾸는 것을 허용하지 않습니다" : "")
                        + "; dun: " + dun);
    }

    /** One startTethering call and what came back. */
    private static final class Attempt {
        final boolean started;
        final boolean failed;
        final boolean permissionDenied;
        final String detail;

        Attempt(boolean started, boolean failed, boolean permissionDenied, String detail) {
            this.started = started;
            this.failed = failed;
            this.permissionDenied = permissionDenied;
            this.detail = detail;
        }
    }

    private static Attempt attemptStart(Object tm, boolean exempt, long waitMs) throws Exception {
        Class<?> requestBuilder = Class.forName("android.net.TetheringManager$TetheringRequest$Builder");
        Object builder = requestBuilder.getConstructor(int.class).newInstance(TETHERING_WIFI);
        String flags = "";
        if (exempt) {
            // A car is not a place to answer a provisioning dialog, so never let the entitlement UI show.
            flags = "; " + optional(requestBuilder, builder, "setExemptFromEntitlementCheck", true)
                    + ", " + optional(requestBuilder, builder, "setShouldShowEntitlementUi", false);
        }
        Object request = requestBuilder.getMethod("build").invoke(builder);

        Class<?> callbackClass = Class.forName("android.net.TetheringManager$StartTetheringCallback");
        final CountDownLatch done = new CountDownLatch(1);
        final String[] outcome = {"no callback"};
        final int[] errorCode = {-1};
        Object callback = Proxy.newProxyInstance(callbackClass.getClassLoader(), new Class<?>[]{callbackClass},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "onTetheringStarted":
                            outcome[0] = "started";
                            done.countDown();
                            break;
                        case "onTetheringFailed":
                            Object code = args != null && args.length > 0 ? args[0] : null;
                            if (code instanceof Integer) {
                                errorCode[0] = (Integer) code;
                            }
                            outcome[0] = "failed " + errorName(code);
                            done.countDown();
                            break;
                        default:
                            // equals/hashCode/toString reach the proxy too; answer them sanely.
                            if ("toString".equals(method.getName())) {
                                return "HotspotCallback";
                            }
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) {
                                return proxy == (args == null ? null : args[0]);
                            }
                            break;
                    }
                    return null;
                });
        Method start = tm.getClass().getMethod("startTethering", request.getClass(), Executor.class, callbackClass);
        Executor direct = Runnable::run;
        start.invoke(tm, request, direct, callback);
        done.await(waitMs, TimeUnit.MILLISECONDS);
        boolean started = "started".equals(outcome[0]);
        boolean failed = outcome[0].startsWith("failed");
        // A reported failure is a real answer: never fall back to "the call raised nothing" after one.
        return new Attempt(started, failed, errorCode[0] == TETHER_ERROR_NO_CHANGE_PERMISSION,
                outcome[0] + flags);
    }

    private static Outcome doStop(Object tm, long waitMs) throws Exception {
        // stopTethering(int) has no callback; poll the state instead so the caller learns when it is really down.
        tm.getClass().getMethod("stopTethering", int.class).invoke(tm, TETHERING_WIFI);
        long deadline = System.currentTimeMillis() + waitMs;
        State s = freshState();
        while (s.known && s.on && System.currentTimeMillis() < deadline) {
            Thread.sleep(250);
            s = freshState();
        }
        boolean down = s.known && !s.on;
        return new Outcome(down, true,
                "stopTethering: " + (s.known ? (s.on ? "still up after " + waitMs + "ms" : "down") : "state unknown"));
    }

    /**
     * Carrier builds gate tethering behind a DUN provisioning check; clearing the global flag is what makes
     * the entitlement exemption stick on them. Shell may write global settings; if a ROM refuses, say so and
     * carry on — on an unlocked device the flag is already 0.
     */
    private static String clearDunRequirement() {
        try {
            String was = Settings.getAndPutValue(Settings.TABLE_GLOBAL, "tether_dun_required", "0");
            return "tether_dun_required was " + (was == null || was.isEmpty() ? "unset" : was);
        } catch (SettingsException e) {
            return "could not clear tether_dun_required (" + e.getMessage() + ")";
        }
    }

    /** A builder setter that may not exist on this ROM: its absence is worth reporting, never fatal. */
    private static String optional(Class<?> cls, Object target, String name, boolean value) {
        try {
            cls.getMethod(name, boolean.class).invoke(target, value);
            return name + "=" + value;
        } catch (ReflectiveOperationException e) {
            return name + " unavailable (" + e.getClass().getSimpleName() + ")";
        }
    }

    // --- reading the state ----------------------------------------------------------------------

    /** What we could find out about the AP, and which of the three ways answered. */
    static final class State {
        final boolean on;
        final boolean known;
        final String via;
        final List<String> interfaces;

        State(boolean on, boolean known, String via, List<String> interfaces) {
            this.on = on;
            this.known = known;
            this.via = via;
            this.interfaces = interfaces;
        }
    }

    /**
     * Three sources, best first. None of them is guaranteed on every ROM, so the answer carries which one
     * spoke: "the hotspot is off" and "nobody would tell us" are different things to a caller about to
     * shut the server down.
     */
    static State state() {
        State cached = cachedState;
        if (cached != null && System.currentTimeMillis() - cachedAt < STATE_CACHE_MS) {
            return cached;
        }
        return freshState();
    }

    /**
     * Reads the phone rather than the cache: for the moments when the answer is about to change.
     *
     * Deliberately not synchronized. {@link #set} holds this class's monitor for as long as the radio
     * takes — up to 15 seconds — and /api/status carries the hotspot on every request, so a reader that
     * took the same lock would park the car's status behind a hotspot switch. Two readers doing the same
     * cheap work at once costs nothing; a stalled /api/status costs the car its picture.
     */
    private static State freshState() {
        State s = readState();
        cachedState = s;
        cachedAt = System.currentTimeMillis();
        return s;
    }

    private static State readState() {
        List<String> ifaces = apInterfaces();
        Integer apState = wifiApState();
        if (apState != null) {
            boolean on = apState == WIFI_AP_STATE_ENABLED || apState == WIFI_AP_STATE_ENABLING;
            return new State(on, true, "getWifiApState=" + apState, ifaces);
        }
        String[] tethered = tetheredIfaces();
        if (tethered != null) {
            return new State(tethered.length > 0, true, "getTetheredIfaces=" + tethered.length, ifaces);
        }
        if (!ifaces.isEmpty()) {
            return new State(true, true, "interface " + ifaces.get(0), ifaces);
        }
        return new State(false, false, "unknown", ifaces);
    }

    private static Integer wifiApState() {
        try {
            Object wifi = FakeContext.get().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return null;
            }
            Object v = wifi.getClass().getMethod("getWifiApState").invoke(wifi);
            return v instanceof Integer ? (Integer) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String[] tetheredIfaces() {
        try {
            Object cm = FakeContext.get().getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Object v = cm.getClass().getMethod("getTetheredIfaces").invoke(cm);
            return v instanceof String[] ? (String[]) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Up AP-looking interfaces that carry an IPv4 address — the last resort, and the one no ROM can refuse. */
    private static List<String> apInterfaces() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                NetworkInterface ni = e.nextElement();
                String name = ni.getName().toLowerCase(Locale.US);
                boolean looksLikeAp = false;
                for (String candidate : AP_INTERFACES) {
                    if (name.equals(candidate)) {
                        looksLikeAp = true;
                        break;
                    }
                }
                if (!looksLikeAp || !ni.isUp()) {
                    continue;
                }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        out.add(ni.getName() + "=" + a.getHostAddress());
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            // A phone that will not enumerate its interfaces tells us nothing; that is what "unknown" is for.
        }
        return out;
    }

    /** Diagnostics must never be the thing that throws: off a device there is no Process to ask. */
    private static String uid() {
        try {
            return String.valueOf(android.os.Process.myUid());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static volatile Object tetheringService;
    private static volatile boolean tetheringLookedUp;
    /** Its own lock, for the same reason as {@link #freshState}: readers must never wait on {@link #set}. */
    private static final Object TETHERING_LOCK = new Object();

    /** The service handle does not change for the life of the process, and the lookup is a binder call. */
    private static Object tetheringManager() {
        if (!tetheringLookedUp) {
            synchronized (TETHERING_LOCK) {
                if (!tetheringLookedUp) {
                    try {
                        tetheringService = FakeContext.get().getSystemService("tethering");
                    } catch (Throwable t) {
                        tetheringService = null;
                    }
                    tetheringLookedUp = true;
                }
            }
        }
        return tetheringService;
    }

    // --- JSON -----------------------------------------------------------------------------------

    private static String reply(boolean ok, String detail, State s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        m.put("detail", detail);
        m.putAll(fields(s));
        if (!lastError.isEmpty()) {
            m.put("lastError", lastError);
        }
        return json(m);
    }

    private static String json(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Boolean || v instanceof Number) {
            return String.valueOf(v);
        }
        if (v instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(value(o));
            }
            return sb.append(']').toString();
        }
        return quote(String.valueOf(v));
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
