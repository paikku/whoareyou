package com.carcast.server;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioCaptureException;
import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Looper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PCM from an {@link AudioCapture} → AAC-LC frames, the way scrcpy's AudioEncoder does it but with
 * plain blocking loops instead of the async callback: one thread reads 1024-sample chunks from the
 * recorder into the codec's input buffers, another drains the encoded frames. Timestamps are the
 * recorder's (monotonic clock), so they line up with the virtual display's video frames; the AAC
 * encoder keeps them (only the OPUS/FLAC encoders rewrite pts, scrcpy #4066).
 */
final class AacEncoder {
    interface Output {
        void onCodecConfig(byte[] audioSpecificConfig);
        void onFrame(byte[] aac, long ptsUs);
    }

    private final AudioCapture capture;
    private final int bitRate;
    private final Output output;
    private MediaCodec codec;
    private Thread inThread;
    private Thread outThread;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile String name = "";
    private volatile long lastPtsUs = -1;
    private volatile long driftUs;
    private volatile long readErrors;
    private volatile String error = "";

    AacEncoder(AudioCapture capture, int bitRate, Output output) {
        this.capture = capture;
        this.bitRate = bitRate;
        this.output = output;
    }

    void start() throws IOException, AudioCaptureException {
        capture.checkCompatibility();
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        name = codec.getName();
        MediaFormat format = new MediaFormat();
        format.setString(MediaFormat.KEY_MIME, MediaFormat.MIMETYPE_AUDIO_AAC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, AudioConfig.CHANNELS);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, AudioConfig.SAMPLE_RATE);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, AudioConfig.MAX_READ_SIZE);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IllegalStateException | IllegalArgumentException e) {
            codec.release();
            codec = null;
            throw new IOException("AAC encoder configure failed: " + e, e);
        }
        try {
            capture.start();
        } catch (AudioCaptureException | RuntimeException e) {
            codec.release();
            codec = null;
            throw e;
        }
        codec.start();
        stopped.set(false);
        Ln.i("Audio encoder: " + name + " AAC-LC " + AudioConfig.SAMPLE_RATE + " Hz " + AudioConfig.CHANNELS + "ch " + bitRate / 1000 + " kbps");
        inThread = thread("audio-in", this::feed);
        outThread = thread("audio-out", this::drain);
    }

    private Thread thread(String name, Runnable body) {
        Thread t = new Thread(() -> {
            Looper.prepare();
            try {
                body.run();
            } catch (Exception e) {
                if (!stopped.get()) {
                    error = e.toString();
                    Ln.e("Audio " + name + " stopped: " + e);
                }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private void feed() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!stopped.get()) {
            int id = codec.dequeueInputBuffer(250_000);
            if (id < 0) {
                continue;
            }
            ByteBuffer buf = codec.getInputBuffer(id);
            int r = capture.read(buf, info);
            if (r <= 0) {
                // The recorder was released (stop), or died; hand the buffer back empty and give up.
                codec.queueInputBuffer(id, 0, 0, 0, 0);
                if (!stopped.get()) {
                    readErrors++;
                    throw new IllegalStateException("audio read returned " + r);
                }
                return;
            }
            codec.queueInputBuffer(id, info.offset, info.size, info.presentationTimeUs, info.flags);
        }
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
                        lastPtsUs = info.presentationTimeUs;
                        driftUs = System.nanoTime() / 1000 - info.presentationTimeUs;
                        output.onFrame(bytes, info.presentationTimeUs);
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

    String name() {
        return name;
    }

    long lastPtsUs() {
        return lastPtsUs;
    }

    /** How far the newest encoded frame's timestamp trails the clock now: capture + encode latency, or a stuck recorder if it keeps growing. */
    long driftUs() {
        return driftUs;
    }

    String error() {
        return error;
    }

    void stop() {
        stopped.set(true);
        // Releasing the recorder unblocks a pending read(); the threads then see `stopped` and exit.
        capture.stop();
        for (Thread t : new Thread[] {inThread, outThread}) {
            if (t != null) {
                try {
                    t.join(2000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        MediaCodec c = codec;
        if (c != null) {
            try {
                c.stop();
            } catch (IllegalStateException ignored) {
            }
            c.release();
            codec = null;
        }
    }
}
