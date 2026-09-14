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

    private static final int I_FRAME_INTERVAL_S = 2; // short GOP: a new car client waits at most this long even without a sync request
    private static final long REPEAT_FRAME_DELAY_US = 100_000;
    private static final String KEY_MAX_FPS_TO_ENCODER = "max-fps-to-encoder";

    private final int width;
    private final int height;
    private final int bitRate;
    private final int maxFps;
    private final boolean constrainedBaseline;
    private final Output output;
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();
    @SuppressWarnings("FieldCanBeLocal")
    private volatile String name = "";
    /** What actually happened to the profile request, for /api/status — the SPS is the final word. */
    private volatile String profileNote = "";

    H264Encoder(int width, int height, int bitRate, int maxFps, boolean constrainedBaseline, Output output) {
        this.width = width;
        this.height = height;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.constrainedBaseline = constrainedBaseline;
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
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        name = codec.getName();
        if (constrainedBaseline) {
            try {
                MediaFormat wanted = format();
                wanted.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline);
                // A profile without a level is ignored by some encoders; 3.2 covers 720p60.
                wanted.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel32);
                codec.configure(wanted, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                profileNote = "constrained-baseline 요청 (SPS 확인 필요)";
            } catch (Exception e) {
                Ln.w("Video encoder: constrained-baseline rejected (" + e + ") — falling back to the vendor default");
                try {
                    codec.release();
                } catch (Exception ignored) {
                    // already unusable; the fresh codec below is what matters
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
                name = codec.getName();
                codec.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                profileNote = "constrained-baseline 거부됨 → 벤더 기본값";
            }
        } else {
            codec.configure(format(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            profileNote = "벤더 기본값";
        }
        inputSurface = codec.createInputSurface();
        Ln.i("Video encoder: " + name + " " + width + "x" + height + " " + bitRate / 1000 + " kbps (" + profileNote + ")");
        return inputSurface;
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
