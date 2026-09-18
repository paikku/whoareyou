package com.carcast.server;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the milliseconds go <em>inside the phone</em>.
 *
 * <p>The car measures what it can see: the control round trip (ping, 5~11 ms in the car) and its own
 * decode-to-draw (9~13 ms), and the end-to-end probe says 43 ms at 720p30. The ~25 ms in between is
 * everything on this side — the touch reaching Android, the app drawing, SurfaceFlinger composing onto the
 * virtual display, the encoder swallowing that frame and handing back a NAL — and until now it was one
 * number nobody had ever split. Optimising a black box is guessing, so this splits it in two.
 *
 * <h3>Why the encoder's pts can be subtracted from our own clock</h3>
 * With a surface-input encoder, {@code BufferInfo.presentationTimeUs} is not ours: it is the timestamp the
 * <em>producer</em> (the virtual display's buffer queue) stamped on the graphics buffer when that frame was
 * made, and that stamp is CLOCK_MONOTONIC — the same clock as {@link System#nanoTime()}. So at the moment
 * the encoder gives us the encoded bytes, {@code nanoTime - pts} is exactly how long that picture spent from
 * "composed" to "compressed". No clock sync, no probe, no car.
 *
 * <p>It is an assumption about vendors, not a law: an encoder that re-stamps its output would make this
 * meaningless. That shows up as absurd values rather than plausible wrong ones, so samples outside a sane
 * window are counted in {@code skipped} instead of being averaged in. A high {@code skipped} means "do not
 * trust this number", which is the honest failure.
 *
 * <h3>touchToFrame</h3>
 * From a touch injected here to the timestamp of the next frame the display produced: Android's own input →
 * app → composition path. It is a rough measure by nature — a tap that changes nothing on screen is answered
 * by whatever frame happens to come next — so samples above {@link #MAX_REACTION_US} are dropped, and while a
 * video is playing (a frame every 33 ms anyway) the number has a floor that is not the touch's fault. Read it
 * next to {@code encodeMs}, not on its own.
 */
final class FrameTiming {
    /** How many samples each measure keeps. ~4 s at 30fps: recent enough to follow a preset change. */
    private static final int WINDOW = 128;
    /** Above this, an "encode latency" is telling us the pts is not on our clock (see the class comment). */
    private static final long MAX_ENCODE_US = 2_000_000;
    /** Above this, the frame we are timing was not an answer to that touch. */
    private static final long MAX_REACTION_US = 500_000;

    private final Samples encode = new Samples();
    private final Samples reaction = new Samples();
    /** nanoTime of the oldest touch that has not been answered by a frame yet; 0 when there is none. */
    private volatile long pendingInjectNs;

    /**
     * A touch was injected at {@code atNs}. Only the oldest unanswered one is kept: the next frame answers
     * the whole burst, and timing the last touch of a flick against it would measure nothing.
     */
    void onInject(long atNs) {
        if (pendingInjectNs == 0) {
            pendingInjectNs = atNs;
        }
    }

    /** The encoder handed back one frame, stamped {@code ptsUs} by whoever produced the surface buffer. */
    void onEncodedFrame(long ptsUs) {
        encode.add(System.nanoTime() / 1000 - ptsUs, MAX_ENCODE_US);
        long inject = pendingInjectNs;
        if (inject != 0) {
            pendingInjectNs = 0;
            reaction.add(ptsUs - inject / 1000, MAX_REACTION_US);
        }
    }

    /** For /api/status. Milliseconds, one decimal; {@code skipped} is how many samples looked impossible. */
    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("encodeMs", encode.summary());
        m.put("touchToFrameMs", reaction.summary());
        return m;
    }

    /** A fixed window of microsecond samples, summarised on demand. Written by the encoder thread only. */
    private static final class Samples {
        private final long[] values = new long[WINDOW];
        private int n;
        private int next;
        private long total;
        private long skipped;

        synchronized void add(long us, long maxUs) {
            if (us < 0 || us > maxUs) {
                skipped++;
                return;
            }
            values[next] = us;
            next = (next + 1) % WINDOW;
            if (n < WINDOW) {
                n++;
            }
            total++;
        }

        synchronized Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", total);
            m.put("skipped", skipped);
            if (n == 0) {
                return m;
            }
            long[] sorted = Arrays.copyOf(values, n);
            Arrays.sort(sorted);
            m.put("p50", ms(sorted[n / 2]));
            m.put("p90", ms(sorted[Math.min(n - 1, (int) (n * 0.9))]));
            m.put("max", ms(sorted[n - 1]));
            return m;
        }

        private static double ms(long us) {
            return Math.round(us / 100.0) / 10.0;
        }
    }
}
