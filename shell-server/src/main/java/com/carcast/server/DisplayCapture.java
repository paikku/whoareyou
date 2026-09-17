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

    // android.view.Display.FLAG_*: what the created display reports back (@hide, so spelled out here).
    static final int FLAG_TRUSTED = 1 << 7;
    static final int FLAG_OWN_DISPLAY_GROUP = 1 << 8;
    static final int FLAG_ALWAYS_UNLOCKED = 1 << 9;
    static final int FLAG_OWN_FOCUS = 1 << 11;

    /** android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL: the keyboard shows on the virtual display itself. */
    static final int DISPLAY_IME_POLICY_LOCAL = 0;

    /** Not final: /api/encoder may resize the display while the car is watching (see resize). */
    int width;
    int height;
    int dpi;
    private final boolean systemDecorations;
    private VirtualDisplay virtualDisplay;
    private int displayId = -1;
    /** What the display actually got, not what we asked for (android.view.Display.FLAG_*). */
    private int displayFlags;

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
     * The flags the created display really carries. Asking for a flag and getting it are two
     * different things: OWN_DISPLAY_GROUP needs ADD_TRUSTED_DISPLAY and ALWAYS_UNLOCKED needs
     * ADD_ALWAYS_UNLOCKED_DISPLAY, and a refused flag is dropped silently. ALWAYS_UNLOCKED is the
     * one that exempts this display from being covered while the phone's keyguard is up
     * (AOSP RootWindowContainer.handleNotObscuredLocked), so "did we get it" is a real question.
     */
    int displayFlags() {
        return displayFlags;
    }

    void start(Surface surface) throws Exception {
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
            displayFlags = virtualDisplay.getDisplay().getFlags();
            Ln.i("New display: " + width + "x" + height + "/" + dpi + " (id=" + displayId + ")"
                    + " flags=0x" + Integer.toHexString(displayFlags)
                    + (has(FLAG_OWN_DISPLAY_GROUP) ? " own-group" : " NO-own-group")
                    + (has(FLAG_ALWAYS_UNLOCKED) ? " always-unlocked" : " NO-always-unlocked"));
            try {
                ServiceManager.getWindowManager().setDisplayImePolicy(displayId, DISPLAY_IME_POLICY_LOCAL);
            } catch (Throwable t) {
                Ln.w("Could not set IME policy: " + t);
            }
        } else {
            virtualDisplay.setSurface(surface);
        }
    }

    /**
     * Change the display's size in place, keeping the display id and the app running on it. Android
     * re-lays-out the app for the new size, exactly as a fold/unfold does. Used by /api/encoder: a
     * smaller display is the one lever that makes the car's software decoder keep up, and trying it
     * must not mean reinstalling the APK in a parking lot.
     */
    synchronized void resize(int newWidth, int newHeight, int newDpi) {
        if (virtualDisplay == null) {
            throw new IllegalStateException("no virtual display");
        }
        virtualDisplay.resize(newWidth, newHeight, newDpi);
        width = newWidth;
        height = newHeight;
        dpi = newDpi;
        Ln.i("Display resized: " + newWidth + "x" + newHeight + "/" + newDpi + " (id=" + displayId + ")");
    }

    /** Detach the encoder surface (encoder restart) without destroying the display and the app on it. */
    void detachSurface() {
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(null);
        }
    }

    boolean has(int displayFlag) {
        return (displayFlags & displayFlag) != 0;
    }

    void release() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
            displayId = -1;
        }
    }
}
