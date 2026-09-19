package com.carcast.server;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one encoder for a few seconds on synthetic frames and reports what it cost — per codec, without a car and
 * without touching the live stream.
 *
 * <p>Why: {@link Encoders} says which encoders the phone <em>has</em>; this says what each one <em>does</em>: how
 * many milliseconds a frame spends inside it, how many bytes an IDR is (the first one, and the one asked for
 * mid-stream — the requested IDR was this project's stall amplifier, 550~575 KB at 1080p on the S26U, report #76),
 * and what the P-frames average. The same numbers for AVC, HEVC and AV1 side by side are the phone's half of
 * "would another codec help": fewer IDR bytes is the gain, a deeper pipeline (AV1 hardware encoders tend to be)
 * is the cost, and neither can be read off a spec sheet.
 *
 * <p>How: byte-buffer input (YUV420 flexible), not a surface. A surface would need something to draw into it
 * (GL or a canvas, neither of which is guaranteed under app_process), and the comparison between codecs is what
 * matters, not the last millisecond of the surface path. The pts we stamp on each input buffer is our own clock,
 * so {@code nanoTime − pts} at output is the encoder's latency exactly as {@link FrameTiming} measures the live
 * one. Frames are paced at the requested fps so the encoder's rate control sees a real cadence.
 *
 * <p>{@code POST /api/bench?codec=hevc&width=1920&height=1080&fps=30&bitrate=8000000&frames=90&qp_i_min=28}.
 * Synchronous; {@code frames/fps} seconds. One at a time — two hardware encoders racing would measure the race.
 *
 * <p>Two lessons from the first run on the S26U (car-tests/model-y §13): the first version dequeued one output per
 * input and so measured its own startup depth (101 ms for every codec, identically) — now every available output is
 * drained and the wait for the next frame is spent polling the output queue, so a frame is timed within a
 * millisecond of leaving the encoder. And the smooth gradient it painted was too easy: a requested 1080p IDR came
 * out at 49 KB where the car had seen 575 KB. The default content is now noise-textured ({@code content=noise}),
 * which is the hard end; {@code content=gradient} keeps the easy one for comparison.
 */
final class EncoderBench {
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final int MAX_FRAMES = 300;
    private static final long MAX_ENCODE_US = 2_000_000;

    private EncoderBench() {
    }

    static Map<String, Object> run(Map<String, String> q) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!BUSY.compareAndSet(false, true)) {
            m.put("ok", false);
            m.put("error", "a bench is already running");
            return m;
        }
        try {
            return bench(q);
        } catch (Throwable t) {
            m.put("ok", false);
            m.put("error", String.valueOf(t));
            return m;
        } finally {
            BUSY.set(false);
        }
    }

    private static Map<String, Object> bench(Map<String, String> q) throws Exception {
        String codec = q.getOrDefault("codec", "avc").toLowerCase();
        String mime = mimeFor(codec);
        int width = Integer.parseInt(q.getOrDefault("width", "1280"));
        int height = Integer.parseInt(q.getOrDefault("height", "720"));
        int fps = Integer.parseInt(q.getOrDefault("fps", "30"));
        int bitrate = Integer.parseInt(q.getOrDefault("bitrate", "4000000"));
        int frames = Math.min(MAX_FRAMES, Integer.parseInt(q.getOrDefault("frames", "90")));
        int qpIMin = Integer.parseInt(q.getOrDefault("qp_i_min", "0"));
        int qpIMax = Integer.parseInt(q.getOrDefault("qp_i_max", "0"));
        boolean lowLatency = !"0".equals(q.getOrDefault("low_latency", "1"));
        boolean noise = !"gradient".equals(q.getOrDefault("content", "noise"));
        // An experiment on the requested IDR's budget: rate control sizes an IDR from the *current* target, so dip the
        // live bitrate to this just before asking for the sync frame and restore it a few frames later. 0 = do not.
        // Why: on noise at 8 Mbps the S26U gave a requested IDR of 335~380 KB whatever qp_i_min said (§13 second run) —
        // the floor never bit because rate control was already coarser than it. If this knob shrinks the IDR, the live
        // encoder can do the same on every keyframe request (H264Encoder.requestKeyframe).
        // Third run (§13): the dip in the same setParameters as the sync request changed nothing — three runs gave the
        // requested IDR byte-for-byte identical to no dip (367,610), while the total kbps rose, i.e. the new target
        // landed *after* the IDR. request_lead_frames=N sends the dip N frames before the request instead, to tell
        // "the parameter is late" from "rate control ignores the target for a requested IDR".
        int requestBitrate = Integer.parseInt(q.getOrDefault("request_bitrate", "0"));
        int restoreAfter = Integer.parseInt(q.getOrDefault("request_restore_frames", "3"));
        int lead = Integer.parseInt(q.getOrDefault("request_lead_frames", "0"));
        if (lead < 0 || restoreAfter < 1) {
            throw new IllegalArgumentException("request_lead_frames must be >= 0 and request_restore_frames >= 1");
        }
        EncoderSettings.validate(width, height, fps, bitrate);
        EncoderSettings.validateQpIBounds(qpIMin, qpIMax);
        if ((width & 1) != 0 || (height & 1) != 0) {
            throw new IllegalArgumentException("width/height must be even");
        }
        String name = q.get("encoder");
        if (name == null) {
            name = pick(mime, !"0".equals(q.getOrDefault("hardware", "1")));
        }
        if (name == null) {
            throw new IllegalArgumentException("no encoder for " + mime);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("codec", codec);
        m.put("mime", mime);
        m.put("encoder", name);
        m.put("width", width);
        m.put("height", height);
        m.put("fps", fps);
        m.put("bitrate", bitrate);
        m.put("frames", frames);
        m.put("content", noise ? "noise" : "gradient");
        m.put("qpIMin", qpIMin);
        m.put("qpIMax", qpIMax);
        m.put("requestBitrate", requestBitrate);
        m.put("requestLeadFrames", lead);
        m.put("requestRestoreFrames", restoreAfter);

        // Same request as the live encoder (H264Encoder.format), minus the surface: CBR, no B-frames, real-time
        // priority, the low-latency hints, and the I-frame QP cap when asked for. Tried in the same order too —
        // everything, then without the QP cap, then bare — so what a vendor rejects is recorded, not fatal.
        MediaCodec mc = null;
        String accepted = "";
        Exception last = null;
        boolean qp = qpIMin > 0 || qpIMax > 0;
        int[][] attempts = qp ? new int[][]{{1, 1}, {1, 0}, {0, 0}} : new int[][]{{1, 0}, {0, 0}};
        for (int[] a : attempts) {
            mc = MediaCodec.createByCodecName(name);
            try {
                boolean withQp = a[1] == 1;
                mc.configure(format(mime, width, height, fps, bitrate, a[0] == 1 && lowLatency, withQp ? qpIMin : 0, withQp ? qpIMax : 0), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                accepted = (a[0] == 1 && lowLatency ? "low-latency" : "plain")
                        + (withQp && qpIMin > 0 ? "+I-QP≥" + qpIMin : "") + (withQp && qpIMax > 0 ? "+I-QP≤" + qpIMax : "")
                        + (qp && !withQp ? " (I-QP bounds rejected)" : "");
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                try {
                    mc.release();
                } catch (Exception ignored) {
                    // unusable anyway
                }
                mc = null;
            }
        }
        if (mc == null) {
            throw new IllegalStateException("configure rejected on every try: " + last);
        }
        m.put("accepted", accepted);
        MediaCodecInfo info = mc.getCodecInfo();
        m.put("hardware", info.isHardwareAccelerated());

        List<Long> encodeUs = new ArrayList<>();
        List<Integer> pBytes = new ArrayList<>();
        List<Integer> keyBytes = new ArrayList<>();
        int skipped = 0;
        long totalBytes = 0;
        long firstOutNs = 0, lastOutNs = 0;
        int requestAt = frames / 2;
        Pattern pattern = null;
        try {
            mc.start();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long frameNs = 1_000_000_000L / fps;
            long t0 = System.nanoTime();
            // A wedged encoder must not wedge the HTTP thread: the run is frames/fps seconds plus slack, then we stop.
            long deadline = t0 + frames * frameNs + 5_000_000_000L;
            int sent = 0;
            boolean eos = false;
            while (!eos) {
                if (System.nanoTime() > deadline) {
                    m.put("timedOut", true);
                    break;
                }
                if (sent < frames) {
                    // Until the next frame is due, wait *on the output queue*: that is where the number we are after
                    // appears, and sleeping through it would time our nap instead of the encoder.
                    long due = t0 + sent * frameNs;
                    long wait;
                    while ((wait = due - System.nanoTime()) > 0) {
                        int out = mc.dequeueOutputBuffer(bi, Math.max(1, wait / 1000));
                        if (out >= 0) {
                            try {
                                if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                                    long now = System.nanoTime();
                                    long us = now / 1000 - bi.presentationTimeUs;
                                    if (us < 0 || us > MAX_ENCODE_US) {
                                        skipped++;
                                    } else {
                                        encodeUs.add(us);
                                    }
                                    if ((bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                        keyBytes.add(bi.size);
                                    } else {
                                        pBytes.add(bi.size);
                                    }
                                    totalBytes += bi.size;
                                    if (firstOutNs == 0) {
                                        firstOutNs = now;
                                    }
                                    lastOutNs = now;
                                }
                            } finally {
                                mc.releaseOutputBuffer(out, false);
                            }
                        } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            m.put("outputFormat", String.valueOf(mc.getOutputFormat()));
                        }
                    }
                    int id = mc.dequeueInputBuffer(20_000);
                    if (id >= 0) {
                        // The byte count to queue: a zero-size buffer is an *empty* frame to Codec2, not a full one.
                        // Read the capacity before getInputImage, which invalidates that ByteBuffer.
                        ByteBuffer raw = mc.getInputBuffer(id);
                        int size = raw != null ? raw.capacity() : width * height * 3 / 2;
                        Image img = mc.getInputImage(id);
                        if (img == null) {
                            throw new IllegalStateException("encoder gave no input image (YUV420Flexible not honoured)");
                        }
                        if (pattern == null) {
                            pattern = new Pattern(width, height, noise);
                        }
                        pattern.paint(img, sent);
                        long pts = System.nanoTime() / 1000;
                        mc.queueInputBuffer(id, 0, size, pts, 0);
                        if (requestBitrate > 0 && lead > 0 && sent == Math.max(0, requestAt - lead)) {
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, requestBitrate);
                            mc.setParameters(b);
                        }
                        if (sent == requestAt) {
                            Bundle b = new Bundle();
                            if (requestBitrate > 0 && lead == 0) {
                                b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, requestBitrate);
                            }
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                            mc.setParameters(b);
                        } else if (requestBitrate > 0 && sent == requestAt + restoreAfter) {
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate);
                            mc.setParameters(b);
                        }
                        sent++;
                        if (sent == frames) {
                            int e = mc.dequeueInputBuffer(200_000);
                            if (e >= 0) {
                                mc.queueInputBuffer(e, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            } else {
                                eos = true; // could not signal EOS; stop after draining what is there
                            }
                        }
                    }
                }
                int out = mc.dequeueOutputBuffer(bi, sent < frames ? 0 : 250_000);
                boolean gotAny = false;
                while (out >= 0 || out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        m.put("outputFormat", String.valueOf(mc.getOutputFormat()));
                        out = mc.dequeueOutputBuffer(bi, 0);
                        continue;
                    }
                    gotAny = true;
                    try {
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bi.size > 0) {
                            long now = System.nanoTime();
                            long us = now / 1000 - bi.presentationTimeUs;
                            if (us < 0 || us > MAX_ENCODE_US) {
                                skipped++;
                            } else {
                                encodeUs.add(us);
                            }
                            if ((bi.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                keyBytes.add(bi.size);
                            } else {
                                pBytes.add(bi.size);
                            }
                            totalBytes += bi.size;
                            if (firstOutNs == 0) {
                                firstOutNs = now;
                            }
                            lastOutNs = now;
                        }
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eos = true;
                        }
                    } finally {
                        mc.releaseOutputBuffer(out, false);
                    }
                    if (eos) {
                        break;
                    }
                    out = mc.dequeueOutputBuffer(bi, 0);
                }
                if (out == MediaCodec.INFO_TRY_AGAIN_LATER && sent >= frames && !gotAny) {
                    eos = true; // a quarter second of silence after EOS: nothing more is coming
                }
            }
        } finally {
            try {
                mc.stop();
            } catch (Exception ignored) {
                // already stopped
            }
            mc.release();
        }

        long[] sorted = new long[encodeUs.size()];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = encodeUs.get(i);
        }
        Arrays.sort(sorted);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("n", sorted.length);
        t.put("skipped", skipped);
        t.put("p50", sorted.length > 0 ? sorted[sorted.length / 2] / 1000.0 : null);
        t.put("p90", sorted.length > 0 ? sorted[(int) (sorted.length * 0.9)] / 1000.0 : null);
        t.put("max", sorted.length > 0 ? sorted[sorted.length - 1] / 1000.0 : null);
        m.put("encodeMs", t);
        m.put("keyframes", keyBytes.size());
        m.put("firstKeyBytes", keyBytes.isEmpty() ? null : keyBytes.get(0));
        // The one the request produced (index 1 when the encoder honoured it). What the car's keyframe request costs.
        m.put("requestedKeyBytes", keyBytes.size() > 1 ? keyBytes.get(1) : null);
        m.put("maxKeyBytes", keyBytes.isEmpty() ? null : keyBytes.stream().max(Integer::compare).orElse(null));
        long pSum = 0;
        for (int b : pBytes) {
            pSum += b;
        }
        m.put("avgPBytes", pBytes.isEmpty() ? null : pSum / pBytes.size());
        double secs = lastOutNs > firstOutNs ? (lastOutNs - firstOutNs) / 1e9 : 0;
        m.put("kbps", secs > 0 ? Math.round(totalBytes * 8 / secs / 1000) : null);
        m.put("totalBytes", totalBytes);
        return m;
    }

    private static String mimeFor(String codec) {
        switch (codec) {
            case "avc":
            case "h264":
                return MediaFormat.MIMETYPE_VIDEO_AVC;
            case "hevc":
            case "h265":
                return MediaFormat.MIMETYPE_VIDEO_HEVC;
            case "av1":
                return MediaFormat.MIMETYPE_VIDEO_AV1;
            case "vp9":
                return MediaFormat.MIMETYPE_VIDEO_VP9;
            default:
                throw new IllegalArgumentException("codec must be avc, hevc, av1 or vp9");
        }
    }

    /** First hardware encoder for the MIME (or first software one when none / when asked). */
    private static String pick(String mime, boolean preferHardware) {
        String sw = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) {
                continue;
            }
            boolean has = false;
            for (String t : info.getSupportedTypes()) {
                if (t.equalsIgnoreCase(mime)) {
                    has = true;
                }
            }
            if (!has) {
                continue;
            }
            if (info.isHardwareAccelerated()) {
                if (preferHardware) {
                    return info.getName();
                }
            } else if (sw == null) {
                sw = info.getName();
            }
        }
        return sw;
    }

    private static MediaFormat format(String mime, int width, int height, int fps, int bitrate, boolean lowLatency, int qpIMin, int qpIMax) {
        MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);
        f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        f.setInteger(MediaFormat.KEY_LATENCY, 1);
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        if (lowLatency) {
            f.setInteger(MediaFormat.KEY_OPERATING_RATE, 240);
            f.setInteger("vendor.qti-ext-enc-low-latency.enable", 1);
        }
        if (qpIMin > 0) {
            f.setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, qpIMin);
        }
        if (qpIMax > 0) {
            f.setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, qpIMax);
        }
        return f;
    }

    /**
     * A moving picture: a diagonal gradient that scrolls one row per frame, plus a block that hops around. Enough
     * change that every frame costs the encoder real bits (a still frame would measure nothing but the pipeline),
     * cheap enough that painting is not what we time — rows are precomputed and copied with bulk puts.
     */
    private static final class Pattern {
        private final int width;
        private final int height;
        private final byte[] yRow;
        private final byte[] cRow;

        /**
         * @param noise texture the rows with pseudo-random detail. A smooth gradient is the easiest picture an encoder
         *              can meet (a 1080p IDR of it was 49 KB where real video gave 575 KB); noise is the hardest. Real
         *              screens sit between, nearer the noisy end when video is playing. The rows are still precomputed
         *              and rotated per frame, so painting stays cheap.
         */
        Pattern(int width, int height, boolean noise) {
            this.width = width;
            this.height = height;
            yRow = new byte[width * 2];
            cRow = new byte[width];
            int x = 0x2545F491;
            for (int i = 0; i < yRow.length; i++) {
                x = x * 1103515245 + 12345;
                int base = 16 + (i * 219 / width) % 220;
                yRow[i] = (byte) (noise ? 16 + ((base - 16 + ((x >>> 16) & 0x7f)) % 220) : base);
            }
            for (int i = 0; i < cRow.length; i++) {
                x = x * 1103515245 + 12345;
                int base = 64 + (i * 128 / width);
                cRow[i] = (byte) (noise ? 32 + ((base - 32 + ((x >>> 16) & 0x3f)) % 192) : base);
            }
        }

        void paint(Image img, int frame) {
            Image.Plane[] planes = img.getPlanes();
            put(planes[0], width, height, yRow, frame);
            // A block of pure white that moves: a hard edge every frame, so the encoder cannot coast on the gradient.
            int bx = (frame * 37) % Math.max(1, width - 64);
            int by = (frame * 23) % Math.max(1, height - 64);
            ByteBuffer y = planes[0].getBuffer();
            int rs = planes[0].getRowStride();
            int ps = planes[0].getPixelStride();
            for (int r = by; r < by + 64; r++) {
                for (int c = bx; c < bx + 64; c++) {
                    y.put(r * rs + c * ps, (byte) 235);
                }
            }
            put(planes[1], width / 2, height / 2, cRow, frame / 2);
            put(planes[2], width / 2, height / 2, cRow, frame / 3);
        }

        private static void put(Image.Plane p, int w, int h, byte[] row, int shift) {
            ByteBuffer buf = p.getBuffer();
            int rs = p.getRowStride();
            int ps = p.getPixelStride();
            if (ps == 1) {
                for (int r = 0; r < h; r++) {
                    int off = (r + shift) % Math.max(1, row.length - w);
                    buf.position(r * rs);
                    buf.put(row, off, w);
                }
            } else {
                // Interleaved chroma: one byte every ps. Slower path; only chroma planes come here (a quarter of the pixels).
                for (int r = 0; r < h; r++) {
                    int off = (r + shift) % Math.max(1, row.length - w);
                    int base = r * rs;
                    for (int c = 0; c < w; c++) {
                        buf.put(base + c * ps, row[off + c]);
                    }
                }
            }
        }
    }
}
