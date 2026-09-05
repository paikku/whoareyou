package com.carcast.server;

import com.carcast.core.media.EncodedH264Sink;
import com.carcast.core.media.MediaHub;
import com.carcast.core.media.VideoSource;
import com.genymobile.scrcpy.Workarounds;
import com.genymobile.scrcpy.util.Command;
import com.genymobile.scrcpy.util.Ln;

import android.view.Surface;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;

/**
 * The live source for the shell process: virtual display → H.264 encoder → fMP4 → clients.
 * Also starts Android apps on that display (`am start --display N`).
 */
public final class DisplayVideoSource implements VideoSource {
    private final DisplayCapture display;
    private final int bitRate;
    private final int maxFps;
    private H264Encoder encoder;
    private EncodedH264Sink sink;
    private volatile String lastApp = "";

    public DisplayVideoSource(int width, int height, int dpi, boolean systemDecorations, int bitRate, int maxFps) {
        this.display = new DisplayCapture(width, height, dpi, systemDecorations);
        this.bitRate = bitRate;
        this.maxFps = maxFps;
    }

    @Override
    public void start(@NotNull MediaHub hub) {
        Workarounds.apply();
        sink = new EncodedH264Sink(hub, 33_333);
        EncodedH264Sink s = sink;
        encoder = new H264Encoder(display.width, display.height, bitRate, maxFps, new H264Encoder.Output() {
            @Override
            public void onCodecConfig(byte[] annexB) {
                s.onCodecConfig(annexB);
            }

            @Override
            public void onFrame(byte[] annexB, long ptsUs, boolean keyframe) {
                s.onFrame(annexB, ptsUs, keyframe);
            }
        });
        try {
            Surface surface = encoder.open();
            display.start(surface);
            encoder.start();
        } catch (Exception e) {
            stop();
            throw new RuntimeException("display/encoder start failed: " + e, e);
        }
    }

    @Override
    public void stop() {
        if (encoder != null) {
            encoder.stop();
            encoder = null;
        }
        display.release();
    }

    @Override
    public void requestKeyframe() {
        H264Encoder e = encoder;
        if (e != null) {
            e.requestKeyframe();
        }
    }

    public int displayId() {
        return display.displayId();
    }

    /**
     * Starts an app on the virtual display. [name] is a package (its launcher activity is resolved)
     * or an explicit component `pkg/.Activity`. Returns the `am` output.
     */
    public String startApp(String name) throws IOException, InterruptedException {
        int id = display.displayId();
        if (id < 0) {
            throw new IOException("no virtual display");
        }
        String component = name;
        if (!name.contains("/")) {
            // `cmd package resolve-activity --brief <pkg>` prints the component on its last line
            String out = Command.execReadOutput("cmd", "package", "resolve-activity", "--brief", name);
            String[] lines = out.trim().split("\n");
            component = lines[lines.length - 1].trim();
            if (!component.contains("/")) {
                throw new IOException("no launcher activity for " + name + ": " + out.trim());
            }
        }
        String result = Command.execReadOutput("am", "start", "--display", String.valueOf(id), "-n", component,
                "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER");
        Ln.i("start app " + component + " on display " + id + ": " + result.trim());
        lastApp = component;
        return result.trim();
    }

    @NotNull
    @Override
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", "display");
        m.put("width", display.width);
        m.put("height", display.height);
        m.put("dpi", display.dpi);
        m.put("displayId", display.displayId());
        m.put("encoder", encoder != null ? encoder.name() : null);
        m.put("frames", sink != null ? sink.getFrames() : 0);
        m.put("keyframes", sink != null ? sink.getKeyframes() : 0);
        m.put("app", lastApp);
        return m;
    }
}
