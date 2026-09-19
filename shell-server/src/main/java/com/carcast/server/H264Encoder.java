package com.carcast.server;

import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Looper;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Surface-input H.264 encoder tuned like scrcpy's SurfaceEncoder: low latency, no B-frames,
 * repeat the last frame after 100 ms when nothing changes (so a static screen still refreshes),
 * one output buffer per input frame. Output is handed over as Annex-B byte arrays.
 */
final class H264Encoder {
    interface Output {
        void onCodecConfig(byte[] annexB);
        void onFrame(byte[] annexB, long ptsUs, boolean keyframe);
    }

    /**
     * GOP length. Used to be 2 s so a new car client would see a picture soon even without a sync request —
     * but a new client now gets the last keyframe on attach and asks for a fresh IDR (MediaHub.attach), and
     * the car can ask for one whenever it drops frames (control kind 4). What a short GOP cost was a burst
     * every two seconds: an IDR is dozens of P-frames' worth of bytes, a hiccup on the hotspot link and a
     * long decode on the car's software decoder. Ten seconds keeps the safety net without the tic.
     */
    private static final int I_FRAME_INTERVAL_S = 10;
    private static final long REPEAT_FRAME_DELAY_US = 100_000;
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";
    /**
     * Qualcomm's low-latency switch (Codec2 vendor extension). Their encoder pipelines a frame or two by
     * default; this asks it not to. Other vendors ignore an unknown key, so it costs nothing where it does
     * not apply. Measured need: encodeMs sat at 11~14 ms across 720p30, 720p60 and 900p60 alike (reports
     * #68~#73) — flat against pixel count and *slower* at the lower frame rate, which is a pipeline depth,
     * not a throughput. See docs/car-tests/model-y-2026.26.md §9.
     */
    private static final String KEY_QTI_LOW_LATENCY = "vendor.qti-ext-enc-low-latency.enable";
    /**
     * What KEY_OPERATING_RATE is set to: a rate well above any frame rate we ask for, which is the
     * documented way to tell a codec "clock yourself for the worst case, not for the average". At 30 fps
     * the encoder took 2.5 ms longer per frame than at 60 — it was pacing itself to the input.
     */
    private static final int OPERATING_RATE = 240;

    private final int width;
    private final int height;
    private final int bitRate;
    private final int maxFps;
    private final boolean constrainedBaseline;
    /** "cbr", "vbr" or "" (the vendor's choice). */
    private final String bitrateMode;
    /** Intra-refresh period in frames (0: off): spread the I-macroblocks over this many frames instead of one IDR. */
    private final int intraRefresh;
    /**
     * QP bounds for I-frames (H.264 QP 0..51, higher = coarser = fewer bytes; 0 = do not ask).
     *
     * <p>{@code qpIMin} is the one that caps the IDR's <em>size</em>: it forbids the encoder from spending a finer
     * QP than this on an I-frame, so the bytes have a ceiling. Why: on the S26U an IDR at 1080p came out at 260 KB on
     * the 10 s GOP and **550~575 KB when the car asked for one** — while the target bitrate was being cut to
     * 6.7 Mbps (report #76). Rate control does not shrink a requested IDR with the target; each one was a
     * half-second of dropped P-frames on the car, and the car asked for the next one before that one had landed.
     * With a QP floor the first picture after a keyframe is a little coarser and the P-frames sharpen it within a
     * few frames, which is invisible next to a stalled screen. See docs/car-tests/model-y §11.
     *
     * <p>{@code qpIMax} is the opposite bound (never coarser than this) — a <em>floor</em> on IDR bytes, which is
     * a quality guarantee, not a size cap. It shipped first as the default "IDR cap" (build e0ee6d2) with the
     * direction inverted: the phone bench measured a requested 1080p IDR at 104 KB with qp_i_max=28 and 49 KB
     * without (car-tests/model-y §13). Kept as a knob, off by default.
     */
    private final int qpIMin;
    private final int qpIMax;
    private final Output output;
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();
    @SuppressWarnings("FieldCanBeLocal")
    private volatile String name = "";
    /** What actually happened to the profile request, for /api/status — the SPS is the final word. */
    private volatile String profileNote = "";

    H264Encoder(int width, int height, int bitRate, int maxFps, boolean constrainedBaseline, String bitrateMode, int intraRefresh,
                int qpIMin, int qpIMax, Output output) {
        this.width = width;
        this.height = height;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.constrainedBaseline = constrainedBaseline;
        this.bitrateMode = bitrateMode == null ? "" : bitrateMode;
        this.intraRefresh = intraRefresh;
        this.qpIMin = qpIMin;
        this.qpIMax = qpIMax;
        this.output = output;
    }

    /**
     * Creates the codec and returns the surface the display must render into.
     *
     * The profile is normally the vendor's choice (S26U gives High: the SPS reads avc1.640020), and
     * that is fine for MSE. It is not fine for a JS decoder on the car side, which only handles
     * Baseline — and we need one, because Tesla stops feeding <video> the moment the gear leaves P
     * (measured 2026-09-14, docs/drive-check). So [constrainedBaseline] asks for Baseline; vendors
     * reject profile/level combinations they dislike, so a rejection falls back to the old behaviour
     * rather than leaving the car with no picture at all.
     */
    Surface open() throws IOException {
        // Three tries, each asking for less: everything (profile, mode, intra refresh, low-latency hints, I-frame QP
        // bounds), then the same without the QP bounds (they are the newest keys and the ones a vendor is likeliest
        // to reject), then the vendor's bare defaults. Everything asked for is a nicety; a picture is not.
        String asked = describe(constrainedBaseline, bitrateMode, intraRefresh, qpIMin, qpIMax);
        String askedNoQp = describe(constrainedBaseline, bitrateMode, intraRefresh, 0, 0);
        Object[][] attempts = {
                {format(constrainedBaseline, bitrateMode, intraRefresh, true, qpIMin, qpIMax), asked.isEmpty() ? "저지연 요청" : asked + " 요청 (SPS 확인 필요)"},
                {format(constrainedBaseline, bitrateMode, intraRefresh, true, 0, 0), (askedNoQp.isEmpty() ? "저지연" : askedNoQp) + " 요청, I-QP 경계 거부됨"},
                {format(false, "", 0, false, 0, 0), (asked.isEmpty() ? "저지연" : asked) + " 거부됨 → 벤더 기본값"},
        };
        Exception last = null;
        for (int i = 0; i < attempts.length; i++) {
            if (qpIMin <= 0 && qpIMax <= 0 && i == 1) {
                continue; // nothing to drop between the first and the last try
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            name = codec.getName();
            try {
                codec.configure((MediaFormat) attempts[i][0], null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                profileNote = (String) attempts[i][1];
                last = null;
                break;
            } catch (Exception e) {
                // Vendors reject profile/level/mode/key combinations they dislike. Log and ask for less.
                Ln.w("Video encoder: try " + (i + 1) + " (" + attempts[i][1] + ") rejected: " + e);
                last = e;
                try {
                    codec.release();
                } catch (Exception ignored) {
                    // already unusable; the fresh codec on the next try is what matters
                }
                codec = null;
            }
        }
        if (codec == null) {
            throw new IOException("encoder configure failed on every try: " + last);
        }
        inputSurface = codec.createInputSurface();
        Ln.i("Video encoder: " + name + " " + width + "x" + height + " " + bitRate / 1000 + " kbps (" + profileNote + ")");
        return inputSurface;
    }

    /** The level this encoder's size and rate need. The frame rate is the cap the car asked for, 60 when none. */
    private int level() {
        return H264Level.levelFor(width, height, maxFps > 0 ? maxFps : 60);
    }

    private String describe(boolean baseline, String mode, int refresh, int qpMin, int qpMax) {
        StringBuilder sb = new StringBuilder();
        if (baseline) {
            sb.append("constrained-baseline ").append(H264Level.describe(level()));
        }
        if (!mode.isEmpty()) {
            sb.append(sb.length() > 0 ? "+" : "").append(mode);
        }
        if (refresh > 0) {
            sb.append(sb.length() > 0 ? "+" : "").append("intra-refresh ").append(refresh);
        }
        if (qpMin > 0) {
            sb.append(sb.length() > 0 ? "+" : "").append("I-QP≥").append(qpMin);
        }
        if (qpMax > 0) {
            sb.append(sb.length() > 0 ? "+" : "").append("I-QP≤").append(qpMax);
        }
        return sb.toString();
    }

    private MediaFormat format(boolean baseline, String mode, int refresh, boolean lowLatency, int qpMin, int qpMax) {
        MediaFormat format = format();
        if (qpMin > 0) {
            format.setInteger(MediaFormat.KEY_VIDEO_QP_I_MIN, qpMin);
        }
        if (qpMax > 0) {
            format.setInteger(MediaFormat.KEY_VIDEO_QP_I_MAX, qpMax);
        }
        if (lowLatency) {
            // KEY_LATENCY=1 (below) is the portable request; these two are what actually moved the number on
            // the vendor encoder we have. Both are hints: a codec that does not know them ignores them.
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, OPERATING_RATE);
            format.setInteger(KEY_QTI_LOW_LATENCY, 1);
        }
        if (baseline) {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline);
            // A profile without a level is ignored by some encoders, so one has to be named — and it has to be
            // the level this size and rate actually need. It used to be a fixed 3.2 ("covers 720p60"), which
            // 900p and 1080p both exceed; see H264Level for what that cost.
            format.setInteger(MediaFormat.KEY_LEVEL, level());
        }
        // CBR: every frame about the same size, so decode time and transfer time are steady too — steadiness is
        // what the car's software decoder and the hotspot link want, more than the sharper frames VBR spends on.
        if ("cbr".equals(mode)) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
        } else if ("vbr".equals(mode)) {
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        if (refresh > 0) {
            format.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, refresh);
        }
        return format;
    }

    private MediaFormat format() {
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_VIDEO_AVC);
        format.setInteger(MediaFormat.KEY_WIDTH, width);
        format.setInteger(MediaFormat.KEY_HEIGHT, height);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 60); // required; actual rate is variable
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_FRAME_DELAY_US);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // real-time
        format.setInteger(MediaFormat.KEY_LATENCY, 1);
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        if (maxFps > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }
        return format;
    }

    /** Whether Baseline was asked for and whether the encoder took it. The SPS in /api/status.codec decides. */
    String profileNote() {
        return profileNote;
    }

    void start() {
        codec.start();
        stopped.set(false);
        thread = new Thread(() -> {
            Looper.prepare(); // some vendors' codecs deadlock without a Looper on the calling thread (scrcpy #4143)
            try {
                drain();
            } catch (Exception e) {
                if (!stopped.get()) {
                    Ln.e("Encoder stopped: " + e);
                }
            }
        }, "video-encoder");
        thread.setDaemon(true);
        thread.start();
    }

    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stopped.get()) {
            int id = codec.dequeueOutputBuffer(info, 250_000);
            if (id < 0) {
                continue;
            }
            try {
                if (info.size > 0) {
                    ByteBuffer buf = codec.getOutputBuffer(id);
                    byte[] bytes = new byte[info.size];
                    buf.position(info.offset);
                    buf.get(bytes, 0, info.size);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        output.onCodecConfig(bytes);
                    } else {
                        output.onFrame(bytes, info.presentationTimeUs, (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0);
                    }
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            } finally {
                codec.releaseOutputBuffer(id, false);
            }
        }
    }

    /** Ask for an IDR on the next frame (new client). */
    void requestKeyframe() {
        MediaCodec c = codec;
        if (c == null || stopped.get()) {
            return;
        }
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            c.setParameters(b);
        } catch (IllegalStateException ignored) {
        }
    }

    /**
     * Change the target bitrate on the running codec (PARAMETER_KEY_VIDEO_BITRATE). No IDR, no init segment,
     * no gap — the next frame is simply budgeted differently — which is what an adaptive controller needs:
     * it may want to move every few seconds, and a rebuild costs the car a keyframe and a pipeline reset each
     * time (see DisplayVideoSource.reconfigure). Returns false when the codec is not running; the vendor
     * accepting the parameter but ignoring it is possible and shows up as the bytes not changing.
     */
    boolean setBitrate(int bitsPerSecond) {
        MediaCodec c = codec;
        if (c == null || stopped.get()) {
            return false;
        }
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond);
            c.setParameters(b);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    String name() {
        return name;
    }

    void stop() {
        stopped.set(true);
        MediaCodec c = codec;
        if (c != null) {
            try {
                c.signalEndOfInputStream();
            } catch (IllegalStateException ignored) {
            }
        }
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException ignored) {
            }
        }
        if (c != null) {
            try {
                c.stop();
            } catch (IllegalStateException ignored) {
            }
            c.release();
            codec = null;
        }
        if (inputSurface != null) {
            inputSurface.release();
            inputSurface = null;
        }
    }
}
