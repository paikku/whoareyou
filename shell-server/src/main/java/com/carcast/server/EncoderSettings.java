package com.carcast.server;

import com.genymobile.scrcpy.util.Ln;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the car last asked the encoder to be (size, fps, bitrate) — kept across server restarts, like
 * {@link AppHistory}, so a choice made in the car sticks without the app having to know about it.
 *
 * The file is `key=value` lines a person can read and edit. Written to a temp file and moved, for the
 * same reason as the history: the server dies at the worst moments.
 */
final class EncoderSettings {
    private static final File FILE = new File("/data/local/tmp/carcast/encoder.conf");

    final int width;
    final int height;
    final int fps;
    final int bitRate;

    EncoderSettings(int width, int height, int fps, int bitRate) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.bitRate = bitRate;
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
            Map<String, Integer> kv = new LinkedHashMap<>();
            for (String line : new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8).split("\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    kv.put(line.substring(0, eq).trim(), Integer.parseInt(line.substring(eq + 1).trim()));
                }
            }
            EncoderSettings s = new EncoderSettings(kv.get("width"), kv.get("height"), kv.get("fps"), kv.get("bitrate"));
            validate(s.width, s.height, s.fps, s.bitRate);
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
            Files.write(tmp.toPath(), ("width=" + width + "\nheight=" + height + "\nfps=" + fps + "\nbitrate=" + bitRate + "\n")
                    .getBytes(StandardCharsets.UTF_8));
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
