package com.carcast.server;

import com.genymobile.scrcpy.util.Ln;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the car last asked the encoder to be (size, fps, bitrate, profile, intra refresh) — kept across
 * server restarts, like {@link AppHistory}, so a choice made in the car sticks without the app having to
 * know about it.
 *
 * The profile is here because every car with a hardware decoder asks for High on attach, and until it was
 * saved every attach cost two encoder builds: one at Baseline from the command line, one at High a moment
 * later (reports #71~#73: `init segment` twice within 0.2 s, `encoderRestarts 2` before any picture).
 *
 * The file is `key=value` lines a person can read and edit. Written to a temp file and moved, for the
 * same reason as the history: the server dies at the worst moments. Keys that are missing (a file from
 * an older build) leave the command-line default in place.
 */
final class EncoderSettings {
    private static final File FILE = new File("/data/local/tmp/carcast/encoder.conf");

    final int width;
    final int height;
    final int fps;
    final int bitRate;
    /** Constrained Baseline asked for; null when the file does not say (older file → command line decides). */
    final Boolean constrainedBaseline;
    /** Intra-refresh period in frames, 0 off; null when the file does not say. */
    final Integer intraRefresh;
    /** Largest QP for I-frames (caps the IDR size), 0 vendor's choice; null when the file does not say. */
    final Integer qpIMax;

    EncoderSettings(int width, int height, int fps, int bitRate, Boolean constrainedBaseline, Integer intraRefresh, Integer qpIMax) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitRate = bitRate;
        this.constrainedBaseline = constrainedBaseline;
        this.intraRefresh = intraRefresh;
        this.qpIMax = qpIMax;
    }

    /** Bounds the car may ask for. The JS decoder on the car is the real limit; these only stop nonsense. */
    static void validate(int width, int height, int fps, int bitRate) {
        if (width < 320 || height < 180 || width > 4096 || height > 4096 || width % 2 != 0 || height % 2 != 0) {
            throw new IllegalArgumentException("size must be even and between 320x180 and 4096x4096: " + width + "x" + height);
        }
        if (fps < 1 || fps > 120) {
            throw new IllegalArgumentException("fps must be 1..120: " + fps);
        }
        if (bitRate < 200_000 || bitRate > 50_000_000) {
            throw new IllegalArgumentException("bitrate must be 200k..50M: " + bitRate);
        }
    }

    /** H.264 QP is 0..51; 0 here means "do not ask". */
    static void validateQpIMax(int qp) {
        if (qp < 0 || qp > 51) {
            throw new IllegalArgumentException("qp_i_max must be 0..51: " + qp);
        }
    }

    /** Bounds for the intra-refresh period: 0 is off; longer than a few seconds of frames refreshes nothing. */
    static void validateIntraRefresh(int frames) {
        if (frames < 0 || frames > 600) {
            throw new IllegalArgumentException("intra_refresh must be 0..600 frames: " + frames);
        }
    }

    /** The virtual display's density for a height: 160 dpi at 720p, scaled so apps lay out the same at any size. */
    static int dpiFor(int height) {
        return Math.max(60, Math.min(640, Math.round(160f * height / 720f)));
    }

    /** The saved settings, or null when there are none (or they do not parse). */
    static EncoderSettings load() {
        if (!FILE.isFile()) {
            return null;
        }
        try {
            Map<String, String> kv = new LinkedHashMap<>();
            for (String line : new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            String profile = kv.get("profile");
            Boolean baseline = profile == null ? null : !"high".equalsIgnoreCase(profile);
            Integer refresh = kv.containsKey("intra_refresh") ? Integer.valueOf(kv.get("intra_refresh")) : null;
            Integer qp = kv.containsKey("qp_i_max") ? Integer.valueOf(kv.get("qp_i_max")) : null;
            EncoderSettings s = new EncoderSettings(Integer.parseInt(kv.get("width")), Integer.parseInt(kv.get("height")),
                    Integer.parseInt(kv.get("fps")), Integer.parseInt(kv.get("bitrate")), baseline, refresh, qp);
            validate(s.width, s.height, s.fps, s.bitRate);
            if (refresh != null) {
                validateIntraRefresh(refresh);
            }
            if (qp != null) {
                validateQpIMax(qp);
            }
            return s;
        } catch (Exception e) {
            Ln.w("encoder.conf unreadable, using defaults: " + e);
            return null;
        }
    }

    void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new java.io.IOException("cannot create " + dir);
            }
            File tmp = new File(FILE.getPath() + ".tmp");
            StringBuilder sb = new StringBuilder();
            sb.append("width=").append(width).append('\n');
            sb.append("height=").append(height).append('\n');
            sb.append("fps=").append(fps).append('\n');
            sb.append("bitrate=").append(bitRate).append('\n');
            if (constrainedBaseline != null) {
                sb.append("profile=").append(constrainedBaseline ? "baseline" : "high").append('\n');
            }
            if (intraRefresh != null) {
                sb.append("intra_refresh=").append(intraRefresh).append('\n');
            }
            if (qpIMax != null) {
                sb.append("qp_i_max=").append(qpIMax).append('\n');
            }
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), FILE.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            Ln.w("could not save encoder.conf: " + e);
        }
    }

    /** Forget the saved choice: the next server start uses the command-line defaults again. */
    static void clear() {
        if (FILE.isFile() && !FILE.delete()) {
            Ln.w("could not delete encoder.conf");
        }
    }
}
