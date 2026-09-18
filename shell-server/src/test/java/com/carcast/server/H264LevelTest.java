package com.carcast.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The car's quality sheet decides the size and frame rate; this decides what the encoder is told to
 * promise. Both halves are worth pinning down, because getting it wrong fails silently on an encoder that
 * rejects the level: H264Encoder falls back to the vendor's profile (High), and a car decoding with WASM
 * reads Baseline only — the picture stops and the log says nothing but "encoder restarted".
 *
 * The one encoder we have measured does not reject — `c2.qti.avc.encoder` overrode our 3.2 with the 4.2 the
 * format needed (report #61). So this is a risk removed, not an observed bug fixed. Either way, asking for a
 * level the stream exceeds is a claim that is simply false, and the SPS is what a decoder believes.
 *
 * Only constants and arithmetic here, so this runs on the JVM: the AVCLevel* values are compile-time
 * constants that javac inlines, and no android.jar class is ever loaded.
 */
public class H264LevelTest {

    /** Annex A counts partial macroblocks: 900 pixels is 56.25 rows of 16, which is 57 rows. */
    @Test
    public void partialMacroblocksCount() {
        assertEquals(3600, H264Level.macroblocks(1280, 720));
        assertEquals(5700, H264Level.macroblocks(1600, 900));
        assertEquals(8160, H264Level.macroblocks(1920, 1080));
    }

    /** Every preset the car can pick, and the level it needs. 3.2 — what used to be hard-coded — covers two. */
    @Test
    public void everyPresetGetsALevelThatFitsIt() {
        assertEquals("3.1", level(1280, 720, 30));
        assertEquals("3.2", level(1280, 720, 60));
        assertEquals("4.0", level(1600, 900, 30));
        assertEquals("4.2", level(1600, 900, 60));
        assertEquals("4.0", level(1920, 1080, 30));
        assertEquals("4.2", level(1920, 1080, 60));
    }

    /**
     * The regression this file exists for: three of those six exceed level 3.2, which is what the encoder
     * asked for regardless of size until 2026-09-17.
     */
    @Test
    public void theOldHardCodedLevelWasTooSmallForMostOfTheLadder() {
        for (int[] preset : new int[][]{{1600, 900, 30}, {1600, 900, 60}, {1920, 1080, 30}, {1920, 1080, 60}}) {
            int mbs = H264Level.macroblocks(preset[0], preset[1]);
            boolean fitsIn32 = mbs <= 5120 && (long) mbs * preset[2] <= 216_000;
            assertTrue(preset[0] + "x" + preset[1] + "@" + preset[2] + " unexpectedly fits in level 3.2", !fitsIn32);
        }
    }

    /** 1080p30 sits 960 macroblocks per second under level 4.0's ceiling — close enough to be worth a test. */
    @Test
    public void level40JustBarelyCarries1080p30() {
        assertEquals(244_800, H264Level.macroblocks(1920, 1080) * 30);
        assertEquals("4.0", level(1920, 1080, 30));
        assertEquals("4.2", level(1920, 1080, 31));
    }

    /** Beyond anything we know, say the highest level rather than a small one the stream would break. */
    @Test
    public void absurdSizesGetTheHighestLevelWeKnow() {
        assertEquals("5.2", level(4096, 4096, 120));
    }

    private static String level(int width, int height, int fps) {
        return H264Level.describe(H264Level.levelFor(width, height, fps));
    }
}
