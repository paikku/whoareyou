package com.carcast.server;

import static org.junit.Assert.assertEquals;
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

    /**
     * With no tethering service nothing can be confirmed and nothing can be accepted, so both a start and a
     * stop have to come back as failures — the bulk sequence reads this to decide whether to tell the user
     * the hotspot was dealt with.
     */
    @Test
    public void neitherDirectionClaimsSuccessWithoutAService() {
        Map<String, String> on = new HashMap<>();
        on.put("on", "1");
        Map<String, String> off = new HashMap<>();
        off.put("on", "0");
        for (Map<String, String> q : new Map[] {on, off}) {
            String json = Hotspot.route("POST", q, "127.0.0.1:1");
            assertTrue(json, json.contains("\"ok\":false"));
            assertTrue(json, json.contains("no tethering service"));
        }
    }

    /**
     * The callback hands back a bare int and the app shows what we write. "error=14" says nothing;
     * the name is the diagnosis — it is the difference between "this phone will not let us" and
     * "the radio failed". Checked here because the callback itself only fires on a real device.
     */
    @Test
    public void tetheringErrorCodesAreNamed() {
        assertEquals("NO_CHANGE_TETHERING_PERMISSION(14)", Hotspot.errorName(14));
        assertEquals("PROVISIONING_FAILED(11)", Hotspot.errorName(11));
        assertEquals("NO_ERROR(0)", Hotspot.errorName(0));
        // Out of range, and not an int at all: both have to come back as something readable.
        assertEquals("TETHER_ERROR_99(99)", Hotspot.errorName(99));
        assertEquals("null", Hotspot.errorName(null));
    }

    /** A junk wait= from a caller must not fail the request; it falls back to the default. */
    @Test
    public void badWaitIsIgnoredRatherThanFatal() {
        String json = Hotspot.set(false, "not-a-number");
        assertTrue(json, json.contains("\"ok\":false"));
    }
}
