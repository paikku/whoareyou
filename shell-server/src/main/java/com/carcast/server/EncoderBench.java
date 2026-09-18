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
 * <p>{@code POST /api/bench?codec=hevc&width=1920&height=1080&fps=30&bitrate=8000000&frames=90&qp_i_max=28}.
 * Synchronous; {@code frames/fps} seconds. One at a time — two hardware encoders racing would measure the race.
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
        int qpIMax = Integer.parseInt(q.getOrDefault("qp_i_max", "0"));
        boolean lowLatency = !"0".equals(q.getOrDefault("low_latency", "1"));
        EncoderSettings.validate(width, height, fps, bitrate);
        EncoderSettings.validateQpIMax(qpIMax);
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

        // Same request as the live encoder (H264Encoder.format), minus the surface: CBR, no B-frames, real-time
        // priority, the low-latency hints, and the I-frame QP cap when asked for. Tried in the same order too —
        // everything, then without the QP cap, then bare — so what a vendor rejects is recorded, not fatal.
        MediaCodec mc = null;
        String accepted = "";
        Exception last = null;
        int[][] attempts = qpIMax > 0 ? new int[][]{{1, qpIMax}, {1, 0}, {0, 0}} : new int[][]{{1, 0}, {0, 0}};
        for (int[] a : attempts) {
            mc = MediaCodec.createByCodecName(name);
            try {
                mc.configure(format(mime, width, height, fps, bitrate, a[0] == 1 && lowLatency, a[1]), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                accepted = (a[0] == 1 && lowLatency ? "low-latency" : "plain") + (a[1] > 0 ? "+I-QP≤" + a[1] : (qpIMax > 0 ? " (I-QP cap rejected)" : ""));
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
                    long due = t0 + sent * frameNs;
                    long wait = due - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
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
                            pattern = new Pattern(width, height);
                        }
                        pattern.paint(img, sent);
                        long pts = System.nanoTime() / 1000;
                        mc.queueInputBuffer(id, 0, size, pts, 0);
                        if (sent == requestAt) {
                            Bundle b = new Bundle();
                            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
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
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            eos = true;
                        }
                    } finally {
                        mc.releaseOutputBuffer(out, false);
                    }
                } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    m.put("outputFormat", String.valueOf(mc.getOutputFormat()));
                } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER && sent >= frames) {
                    eos = true; // nothing more is coming
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

    private static MediaFormat format(String mime, int width, int height, int fps, int bitrate, boolean lowLatency, int qpIMax) {
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

        Pattern(int width, int height) {
            this.width = width;
            this.height = height;
            yRow = new byte[width * 2];
            for (int x = 0; x < yRow.length; x++) {
                yRow[x] = (byte) (16 + (x * 219 / width) % 220);
            }
            cRow = new byte[width];
            for (int x = 0; x < cRow.length; x++) {
                cRow[x] = (byte) (64 + (x * 128 / width));
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
