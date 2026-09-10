package com.carcast.server;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.view.Surface;

/**
 * A trusted virtual display that Android apps can run on, rendered into the encoder's surface.
 * Flags follow scrcpy's NewDisplayCapture (v4.1): public + presentation + own content + touch,
 * and on Android 13+/14+ the trusted/own-display-group/always-unlocked/own-focus flags that make
 * it usable while the phone is locked and let it receive its own input focus.
 */
final class DisplayCapture {
    private static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    private static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
    private static final int VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 << 8;
    private static final int VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED = 1 << 13;
    private static final int VIRTUAL_DISPLAY_FLAG_OWN_FOCUS = 1 << 14;
    private static final int VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP = 1 << 15;

    /** android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL: the keyboard shows on the virtual display itself. */
    static final int DISPLAY_IME_POLICY_LOCAL = 0;

    final int width;
    final int height;
    final int dpi;
    private final boolean systemDecorations;
    private VirtualDisplay virtualDisplay;
    private Surface surface;
    private int displayId = -1;

    DisplayCapture(int width, int height, int dpi, boolean systemDecorations) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
        this.systemDecorations = systemDecorations;
    }

    int displayId() {
        return displayId;
    }

    /**
     * android.view.Display state of the virtual display: STATE_ON while it renders, STATE_OFF once its power
     * group went to sleep (the power button sleeps every group; on Android 13+ this display has its own,
     * which KEYCODE_WAKEUP alone does not bring back). -1 when there is no display.
     */
    int state() {
        VirtualDisplay vd = virtualDisplay;
        if (vd == null) {
            return -1;
        }
        try {
            return vd.getDisplay().getState();
        } catch (Throwable t) {
            Ln.w("display state unavailable: " + t);
            return -1;
        }
    }

    boolean isAsleep() {
        int s = state();
        return s != -1 && s != android.view.Display.STATE_ON;
    }

    /**
     * Tear the display down and create a fresh one on the same encoder surface — the only sure way to get a
     * rendering display back after its power group slept. The app on it is destroyed with it
     * (DESTROY_CONTENT_ON_REMOVAL); the caller relaunches it. The display id changes.
     */
    void recreate() throws Exception {
        Surface s = surface;
        if (s == null) {
            throw new IllegalStateException("no surface to recreate the display on");
        }
        release();
        start(s);
    }

    void start(Surface surface) throws Exception {
        this.surface = surface;
        int flags = VIRTUAL_DISPLAY_FLAG_PUBLIC
                | VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                | VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
                | VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL;
        if (systemDecorations) {
            flags |= VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    | VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED;
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags |= VIRTUAL_DISPLAY_FLAG_OWN_FOCUS | VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
            }
        }
        if (virtualDisplay == null) {
            virtualDisplay = ServiceManager.getDisplayManager().createNewVirtualDisplay("carcast", width, height, dpi, surface, flags);
            displayId = virtualDisplay.getDisplay().getDisplayId();
            Ln.i("New display: " + width + "x" + height + "/" + dpi + " (id=" + displayId + ")");
            try {
                ServiceManager.getWindowManager().setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_LOCAL);
            } catch (Throwable t) {
                Ln.w("Could not set IME policy: " + t);
            }
        } else {
            virtualDisplay.setSurface(surface);
        }
    }

    /** Detach the encoder surface (encoder restart) without destroying the display and the app on it. */
    void detachSurface() {
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(null);
        }
    }

    void release() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
            displayId = -1;
        }
    }
}
