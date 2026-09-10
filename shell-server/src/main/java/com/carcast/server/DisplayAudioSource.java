package com.carcast.server;

import com.carcast.core.media.EncodedAacSink;
import com.carcast.core.media.MediaHub;
import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioDirectCapture;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * The live audio source for the shell process: what the phone plays, captured with
 * {@code REMOTE_SUBMIX} (scrcpy's {@code --audio-source=output}: the sound goes to the car and the
 * phone's own speaker stays silent — M0 on One UI 8) → AAC-LC → fMP4 → clients.
 *
 * Only the direct capture sources are ported ({@code output}, {@code mic}); {@code playback}
 * (AudioPlaybackCapture with a fake MediaProjection, which keeps the phone speaker playing too)
 * is not, since M0 chose {@code output}.
 */
public final class DisplayAudioSource implements com.carcast.core.media.AudioSource {
    private final com.genymobile.scrcpy.audio.AudioSource source;
    private final int bitRate;
    private AacEncoder encoder;
    private EncodedAacSink sink;

    public DisplayAudioSource(String sourceName, int bitRate) {
        com.genymobile.scrcpy.audio.AudioSource s = com.genymobile.scrcpy.audio.AudioSource.findByName(sourceName);
        if (s == null || !s.isDirect()) {
            throw new IllegalArgumentException("audio must be output or mic (playback is not ported), got '" + sourceName + "'");
        }
        this.source = s;
        this.bitRate = bitRate;
    }

    @Override
    public void start(@NotNull MediaHub hub) {
        sink = new EncodedAacSink(hub);
        EncodedAacSink s = sink;
        AudioCapture capture = new AudioDirectCapture(source);
        encoder = new AacEncoder(capture, bitRate, new AacEncoder.Output() {
            @Override
            public void onCodecConfig(byte[] audioSpecificConfig) {
                s.onCodecConfig(audioSpecificConfig);
            }

            @Override
            public void onFrame(byte[] aac, long ptsUs) {
                s.onFrame(aac, ptsUs);
            }
        });
        try {
            encoder.start();
        } catch (Throwable e) {
            stop();
            throw new RuntimeException("audio capture/encoder start failed: " + e, e);
        }
    }

    @Override
    public void stop() {
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
    }

    @NotNull
    @Override
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("capture", source.name().toLowerCase(java.util.Locale.ROOT));
        m.put("encoder", encoder != null ? encoder.name() : null);
        m.put("codec", sink != null ? sink.getCodecString() : "");
        m.put("sampleRate", sink != null ? sink.getSampleRate() : 0);
        m.put("channels", sink != null ? sink.getChannels() : 0);
        m.put("bitRate", bitRate);
        m.put("frames", sink != null ? sink.getFrames() : 0);
        m.put("bytes", sink != null ? sink.getBytes() : 0);
        m.put("lastPtsUs", encoder != null ? encoder.lastPtsUs() : -1);
        m.put("driftMs", encoder != null ? encoder.driftUs() / 1000 : 0);
        if (encoder != null && !encoder.error().isEmpty()) {
            m.put("error", encoder.error());
        }
        return m;
    }
}
