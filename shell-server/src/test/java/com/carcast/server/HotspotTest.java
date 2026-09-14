package com.carcast.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Off a phone there is no WifiManager and no ActivityThread, so this is the "nobody would tell us" path —
 * and that is the one worth pinning. The failure that would hurt is not an exception: it is answering "the
 * hotspot is off" when the truth is that nothing answered. The driver switches the hotspot by hand, and a
 * confident wrong "off" sends them to flip a switch that is already on while the car still cannot connect.
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
    public void statusIsJsonAndNamesWhatAnswered() {
        String json = Hotspot.statusJson();
        assertTrue(json, json.startsWith("{") && json.endsWith("}"));
        assertTrue(json, json.contains("\"known\":false"));
        assertTrue(json, json.contains("\"interfaces\":["));
        // Which of the three sources spoke is the difference between an answer and a shrug.
        assertTrue(json, json.contains("\"via\":\""));
    }

    /** Reading is open: the state is visible to anyone who can already see the SSID. */
    @Test
    public void anyoneMayRead() {
        String fromCar = Hotspot.route("GET");
        assertTrue(fromCar, fromCar.contains("\"known\":false"));
        assertTrue(fromCar, fromCar.contains("\"via\":\""));
    }

    /**
     * Switching is gone — the phone refuses uid 2000 — but an older app may still ask. It has to be told why
     * rather than left to guess from a silent failure.
     */
    @Test
    public void writesAreRefusedWithAReason() {
        String post = Hotspot.route("POST");
        assertTrue(post, post.contains("\"ok\":false"));
        assertTrue(post, post.contains("read only"));
    }
}
