package com.carcast.server;

import com.genymobile.scrcpy.FakeContext;

import android.content.Context;

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

/**
 * Reports whether the phone's Wi-Fi hotspot is up. Read only — the user switches it themselves.
 *
 * It used to try to switch it too, and could not: {@code TetheringManager.startTethering} answers uid 2000
 * with TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION on both the API 36 emulator and the S26U (One UI 8),
 * with and without the entitlement exemption — shell has no TETHER_PRIVILEGED and the WRITE_SETTINGS path is
 * shut on these builds. {@code cmd wifi start-softap} needs root. The whole attempt is recorded in
 * docs/verification-log.md (open question 0); what is left here is the half that works.
 *
 * Reading has no such problem, and three ways are tried because none is guaranteed: {@code getWifiApState},
 * then {@code getTetheredIfaces}, then the interface names an AP is brought up on. Which one answered is
 * reported, because **"nobody would tell us" is not "it is off"** — a caller that cannot tell those apart
 * would show the driver a confident lie about the thing they have to switch by hand.
 */
final class Hotspot {
    /** WifiManager.WIFI_AP_STATE_* (hidden constants, stable since Android 4). */
    private static final int WIFI_AP_STATE_ENABLING = 12;
    private static final int WIFI_AP_STATE_ENABLED = 13;
    /**
     * Interface names a Wi-Fi AP is brought up on, when nothing else will tell us the state: swlan0 is
     * Samsung's, ap0/softap0 are Qualcomm/AOSP, wlan1 is the second radio on dual-band devices.
     */
    private static final String[] AP_INTERFACES = {"swlan0", "softap0", "ap0", "wlan1"};

    /**
     * /api/status carries this on every request, and the app polls it while the car is watching. Reading the
     * state enumerates every network interface, so a short cache keeps a status request from redoing that.
     * The hotspot is switched by hand, so it changes on a human timescale and a stale second costs nothing.
     */
    private static final long STATE_CACHE_MS = 750;
    private static volatile State cachedState;
    private static volatile long cachedAt;

    private Hotspot() {
    }

    /** {@code GET /api/hotspot}. Nothing here writes, so there is nothing to restrict to loopback. */
    static String route(String method) {
        if (!"POST".equals(method)) {
            return statusJson();
        }
        // An older build of the app may still ask. Say why rather than leaving it to time out on a 404.
        return "{\"ok\":false,\"error\":\"read only — the hotspot is switched by the user "
                + "(TETHER_ERROR_NO_CHANGE_TETHERING_PERMISSION, see docs/verification-log.md)\"}";
    }

    static String statusJson() {
        return json(info());
    }

    static Map<String, Object> info() {
        State s = state();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("on", s.on);
        m.put("known", s.known);
        m.put("via", s.via);
        m.put("interfaces", s.interfaces);
        return m;
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

    static State state() {
        State cached = cachedState;
        if (cached != null && System.currentTimeMillis() - cachedAt < STATE_CACHE_MS) {
            return cached;
        }
        State s = readState();
        cachedState = s;
        cachedAt = System.currentTimeMillis();
        return s;
    }

    /**
     * Three sources, best first. The answer carries which one spoke: "the hotspot is off" and "nobody would
     * tell us" are different things to the app, which shows the driver one dot for a switch they own.
     */
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

    // --- JSON -----------------------------------------------------------------------------------

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
