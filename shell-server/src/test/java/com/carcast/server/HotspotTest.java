package com.carcast.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Off a phone there is no tethering service, no WifiManager and no ActivityThread, so this is the
 * "nobody would tell us" path — and that is the one worth pinning. The whole class is reflection into
 * hidden API, and the failure that would hurt is not an exception: it is answering "the hotspot is off"
 * when the truth is that nothing answered. The bulk-off sequence reads that flag to decide whether it may
 * shut the server down, and a confident wrong "off" leaves the hotspot up with nothing able to switch it.
 */
public class HotspotTest {
    @Test
    public void unknownIsNotTheSameAsOff() {
        Hotspot.State s = Hotspot.state();
        // A build machine has no swlan0/ap0 with an address, so nothing may claim to know.
        assertFalse("state must be reported as unknown, not as a confident 'off': via=" + s.via, s.known);
        assertFalse(s.on);
    }

    @Test
    public void statusIsJsonAndSaysItCannotControlAnything() {
        String json = Hotspot.statusJson();
        assertTrue(json, json.startsWith("{") && json.endsWith("}"));
        assertTrue(json, json.contains("\"controllable\":false"));
        assertTrue(json, json.contains("\"known\":false"));
        assertTrue(json, json.contains("\"interfaces\":["));
    }

    /** A request that cannot be carried out must fail loudly rather than throw or claim success. */
    @Test
    public void switchingWithoutATetheringServiceFailsWithAReason() {
        String json = Hotspot.set(true, null);
        assertTrue(json, json.contains("\"ok\":false"));
        assertTrue(json, json.contains("no tethering service"));
    }

    /**
     * The car is on the hotspot it would be switching off, and so is anything else sharing it. Writes are
     * loopback-only for the same reason /api/stop is; reads stay open, since the state is visible to
     * anyone who can already see the SSID.
     */
    @Test
    public void writesAreLoopbackOnlyAndReadsAreNot() {
        Map<String, String> on = new HashMap<>();
        on.put("on", "1");
        String fromCar = Hotspot.route("POST", on, "192.168.43.17:41234");
        assertTrue(fromCar, fromCar.contains("\"ok\":false") && fromCar.contains("loopback only"));

        String noRemote = Hotspot.route("POST", on, null);
        assertTrue(noRemote, noRemote.contains("loopback only"));

        // A loopback write gets as far as the tethering service — which is absent here, so it fails on that
        // instead of on the address. Different failure, and that difference is the point.
        String fromPhone = Hotspot.route("POST", on, "127.0.0.1:5555");
        assertFalse(fromPhone, fromPhone.contains("loopback only"));
        assertTrue(fromPhone, fromPhone.contains("no tethering service"));

        // Reading is allowed from the car: it is how the car's page could show the state.
        String read = Hotspot.route("GET", Collections.emptyMap(), "192.168.43.17:41234");
        assertTrue(read, read.contains("\"controllable\":false") && !read.contains("loopback only"));
    }

    /** ?on= is the switch: only "0"/"false" mean off, and a missing query must not crash the route. */
    @Test
    public void onParameterParsing() {
        Map<String, String> off = new HashMap<>();
        off.put("on", "0");
        assertTrue(Hotspot.route("POST", off, "127.0.0.1:1").contains("\"ok\":false"));
        assertTrue(Hotspot.route("POST", null, "127.0.0.1:1").contains("\"ok\":false"));
    }

    /** A junk wait= from a caller must not fail the request; it falls back to the default. */
    @Test
    public void badWaitIsIgnoredRatherThanFatal() {
        String json = Hotspot.set(false, "not-a-number");
        assertTrue(json, json.contains("\"ok\":false"));
    }
}
