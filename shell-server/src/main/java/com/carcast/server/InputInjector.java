package com.carcast.server;

import com.carcast.core.media.ControlMessage;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.wrappers.ClipboardManager;
import com.genymobile.scrcpy.wrappers.InputManager;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

/**
 * Turns the car's control messages into input events on the virtual display, the way scrcpy's
 * Controller does: touches become multi-pointer MotionEvents (finger tool type, SOURCE_TOUCHSCREEN),
 * nav keys become KeyEvents, text becomes key events for characters the virtual keyboard map knows
 * and a clipboard paste for everything else (Korean etc.). Every event carries the display id.
 * Injection needs INJECT_EVENTS, which the shell uid has (on Samsung: "USB debugging (Security
 * settings)" must be enabled once).
 */
final class InputInjector {
    private static final int MAX_POINTERS = 10;
    private static final int DEFAULT_DEVICE_ID = 0;

    private final int width;
    private final int height;
    private final java.util.function.IntSupplier displayId;

    private final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[MAX_POINTERS];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[MAX_POINTERS];
    /** Slot → whether that finger is currently down. Slots are the client's pointer ids (0..9). */
    private final boolean[] down = new boolean[MAX_POINTERS];
    private final float[] lastX = new float[MAX_POINTERS];
    private final float[] lastY = new float[MAX_POINTERS];
    private final float[] lastPressure = new float[MAX_POINTERS];
    private long lastTouchDown;
    private final KeyCharacterMap charMap = loadCharMap();

    private static KeyCharacterMap loadCharMap() {
        try {
            return KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        } catch (Throwable t) {
            // Never fatal: without it, ASCII text goes through the clipboard path instead of key events.
            Ln.w("KeyCharacterMap unavailable: " + t);
            return null;
        }
    }
    private volatile long injected;
    private volatile long failed;

    InputInjector(int width, int height, java.util.function.IntSupplier displayId) {
        this.width = width;
        this.height = height;
        this.displayId = displayId;
        for (int i = 0; i < MAX_POINTERS; i++) {
            MotionEvent.PointerProperties p = new MotionEvent.PointerProperties();
            p.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = p;
            coords[i] = new MotionEvent.PointerCoords();
        }
    }

    long injected() {
        return injected;
    }

    long failed() {
        return failed;
    }

    /** How many fingers this injector currently believes are down on the virtual display. */
    synchronized int pointersDown() {
        int n = 0;
        for (boolean d : down) {
            if (d) {
                n++;
            }
        }
        return n;
    }

    /**
     * Let go of every finger, as one cancelled gesture.
     *
     * The car's control socket dies mid-gesture more often than anywhere else — Tesla's browser drops
     * WebSockets, the driver reverses, the hotspot stutters. The UP that would have ended the touch never
     * arrives, so Android keeps that finger down forever: the next tap becomes a two-finger gesture with a
     * phantom finger somewhere else on the screen, and the app starts pinching instead of tapping. Nothing
     * recovers from that on its own, and to the driver it reads as "the car screen went crazy".
     *
     * So when the control socket goes away, the gesture goes away with it. CANCEL rather than UP: an UP at
     * the last known point would be a click the driver never made (on a "delete" button, say).
     */
    synchronized boolean cancelAll() {
        if (pointersDown() == 0) {
            return true;
        }
        long now = SystemClock.uptimeMillis();
        int count = 0;
        for (int s = 0; s < MAX_POINTERS; s++) {
            if (!down[s]) {
                continue;
            }
            props[count].id = s;
            props[count].toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords c = coords[count];
            c.clear();
            c.x = lastX[s];
            c.y = lastY[s];
            c.pressure = 0f;
            c.size = 1f;
            count++;
        }
        java.util.Arrays.fill(down, false);
        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, MotionEvent.ACTION_CANCEL, count, props, coords,
                0, 0, 1f, 1f, DEFAULT_DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        boolean ok = inject(event);
        Ln.i("control socket gone: cancelled " + count + " stuck finger(s) -> " + ok);
        return ok;
    }

    void handle(ControlMessage msg) {
        boolean ok;
        if (msg instanceof ControlMessage.Touch) {
            ok = touch((ControlMessage.Touch) msg);
        } else if (msg instanceof ControlMessage.Key) {
            ControlMessage.Key k = (ControlMessage.Key) msg;
            ok = key(k.getAction() == ControlMessage.Key.DOWN ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, k.getKeycode());
        } else if (msg instanceof ControlMessage.Text) {
            ok = text(((ControlMessage.Text) msg).getText());
        } else {
            return;
        }
        if (ok) {
            injected++;
        } else {
            failed++;
        }
    }

    private synchronized boolean touch(ControlMessage.Touch t) {
        int slot = t.getPointerId();
        if (slot < 0 || slot >= MAX_POINTERS) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        int action;
        switch (t.getAction()) {
            case ControlMessage.Touch.DOWN:
                action = MotionEvent.ACTION_DOWN;
                break;
            case ControlMessage.Touch.UP:
                action = MotionEvent.ACTION_UP;
                break;
            case ControlMessage.Touch.MOVE:
                action = MotionEvent.ACTION_MOVE;
                break;
            case ControlMessage.Touch.CANCEL:
                action = MotionEvent.ACTION_CANCEL;
                break;
            default:
                return false;
        }
        if (action == MotionEvent.ACTION_MOVE && !down[slot]) {
            return true; // stray move after up/cancel: nothing to do
        }
        if (action == MotionEvent.ACTION_DOWN) {
            down[slot] = true;
        }
        lastX[slot] = t.getX() * width;
        lastY[slot] = t.getY() * height;
        lastPressure[slot] = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0f : Math.max(t.getPressure(), 0.01f);

        // Build the pointer arrays from every finger that is down (the one going up is still included).
        int count = 0;
        int indexOfSlot = -1;
        for (int s = 0; s < MAX_POINTERS; s++) {
            if (!down[s]) {
                continue;
            }
            props[count].id = s;
            props[count].toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords c = coords[count];
            c.clear();
            c.x = lastX[s];
            c.y = lastY[s];
            c.pressure = lastPressure[s];
            c.size = 1f;
            if (s == slot) {
                indexOfSlot = count;
            }
            count++;
        }
        if (count == 0 || indexOfSlot < 0) {
            return false;
        }
        if (count == 1) {
            if (action == MotionEvent.ACTION_DOWN) {
                lastTouchDown = now;
            }
        } else if (action == MotionEvent.ACTION_DOWN) {
            action = MotionEvent.ACTION_POINTER_DOWN | (indexOfSlot << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        } else if (action == MotionEvent.ACTION_UP) {
            action = MotionEvent.ACTION_POINTER_UP | (indexOfSlot << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        if (t.getAction() == ControlMessage.Touch.UP || t.getAction() == ControlMessage.Touch.CANCEL) {
            down[slot] = false;
        }
        MotionEvent event = MotionEvent.obtain(lastTouchDown, now, action, count, props, coords, 0, 0, 1f, 1f,
                DEFAULT_DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        return inject(event);
    }

    private boolean key(int action, int keycode) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now, action, keycode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
        return inject(event);
    }

    private boolean pressRelease(int keycode) {
        return key(KeyEvent.ACTION_DOWN, keycode) && key(KeyEvent.ACTION_UP, keycode);
    }

    private boolean text(String text) {
        if (text.isEmpty()) {
            return true;
        }
        // Characters the virtual keyboard can type directly (ASCII etc.): key events, like scrcpy's injectText.
        boolean allTyped = charMap != null;
        if (allTyped) for (char c : text.toCharArray()) {
            KeyEvent[] events = charMap.getEvents(new char[] {c});
            if (events == null) {
                allTyped = false;
                break;
            }
        }
        if (allTyped) {
            for (char c : text.toCharArray()) {
                KeyEvent[] events = charMap.getEvents(new char[] {c});
                for (KeyEvent e : events) {
                    if (!inject(e)) {
                        return false;
                    }
                }
            }
            return true;
        }
        // Anything else (Korean from the car keyboard): clipboard + paste key.
        ClipboardManager cm = ServiceManager.getClipboardManager();
        if (cm == null) {
            Ln.w("No clipboard manager: cannot inject non-ASCII text");
            return false;
        }
        if (!cm.setText(text)) {
            Ln.w("Could not set clipboard for text injection");
            return false;
        }
        return pressRelease(KeyEvent.KEYCODE_PASTE);
    }

    private boolean inject(InputEvent event) {
        int id = displayId.getAsInt();
        if (id < 0) {
            return false;
        }
        if (id != 0 && !InputManager.setDisplayId(event, id)) {
            return false;
        }
        return ServiceManager.getInputManager().injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
