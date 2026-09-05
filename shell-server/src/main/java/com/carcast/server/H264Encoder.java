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
    private final Output output;
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread thread;
    private final AtomicBoolean stopped = new AtomicBoolean();
    @SuppressWarnings("FieldCanBeLocal")
    private volatile String name = "";

    H264Encoder(int width, int height, int bitRate, int maxFps, Output output) {
        this.width = width;
        this.height = height;
        this.bitRate = bitRate;
        this.maxFps = maxFps;
        this.output = output;
    }

    /** Creates the codec and returns the surface the display must render into. */
    Surface open() throws IOException {
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        name = codec.getName();
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
        // No KEY_PROFILE: vendors reject some combinations; the SPS decides the codec string the web client uses.
        if (maxFps > 0) {
            format.setFloat(KEY_MAX_FPS_TO_ENCODER, maxFps);
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        inputSurface = codec.createInputSurface();
        Ln.i("Video encoder: " + name + " " + width + "x" + height + " " + bitRate / 1000 + " kbps");
        return inputSurface;
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
