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

    /** Read on every event: the display can be resized at runtime (/api/encoder), and the car's coordinates are normalised. */
    private final java.util.function.IntSupplier width;
    private final java.util.function.IntSupplier height;
    private final java.util.function.IntSupplier displayId;

    private final MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[MAX_POINTERS];
    private final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[MAX_POINTERS];
    /** Slot → whether that finger is currently down. Slots are the client's pointer ids (0..9). */
    private final boolean[] down = new boolean[MAX_POINTERS];
    private final float[] lastX = new float[MAX_POINTERS];
    private final float[] lastY = new float[MAX_POINTERS];
    private final float[] lastPressure = new float[MAX_POINTERS];
    private long lastTouchDown;
    /**
     * The car's clock at the DOWN that started the current gesture (-1: none, or the car sent no clock), and the
     * uptime we stamped the last touch event with. Together they turn the car's timestamps into ours: see
     * {@link #eventTime(long, long)}.
     */
    private long carDownMs = -1;
    private long lastEventTime;
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
    /** Set once the live source exists: every touch is stamped so the next frame can be timed against it. */
    private volatile FrameTiming timing;

    InputInjector(java.util.function.IntSupplier width, java.util.function.IntSupplier height, java.util.function.IntSupplier displayId) {
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

    void timing(FrameTiming t) {
        timing = t;
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
        // Taken before the injection, not after: the binder call into Android is part of what we are timing.
        long atNs = System.nanoTime();
        boolean isTouch = msg instanceof ControlMessage.Touch || msg instanceof ControlMessage.TouchBatch;
        if (msg instanceof ControlMessage.Touch) {
            ok = touch((ControlMessage.Touch) msg);
        } else if (msg instanceof ControlMessage.TouchBatch) {
            ok = touchBatch((ControlMessage.TouchBatch) msg);
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
            FrameTiming t = timing;
            if (isTouch && t != null) {
                t.onInject(atNs);
            }
        } else {
            failed++;
        }
    }

    /**
     * When this event happened, on our clock.
     *
     * Every event used to be stamped with the moment it arrived here, so the network's jitter became the
     * finger's jitter: Android's VelocityTracker read the spacing of the arrivals, not of the driver's
     * movement, and a fling came out a different strength every time. The car now sends its own clock
     * ({@code tMs}); we anchor it to the DOWN — {@code downTime + (tMs - car's tMs at DOWN)} — so the gesture
     * keeps the timing it had on the car's screen. Clamped to never run ahead of now (a late DOWN followed
     * by a punctual MOVE would otherwise land in the future) and never behind the previous event (event
     * times must not go backwards within a gesture). Without a car clock: now, as before.
     */
    private long eventTime(long tMs, long now) {
        long t = now;
        if (tMs >= 0 && carDownMs >= 0) {
            long delta = (tMs - carDownMs) & 0xffffffffL; // the car's clock is u32 and wraps
            if (delta < 0x80000000L) {
                t = lastTouchDown + delta;
            }
        }
        if (t > now) {
            t = now;
        }
        if (t < lastEventTime) {
            t = lastEventTime;
        }
        lastEventTime = t;
        return t;
    }

    /**
     * MOVE samples the car's browser coalesced into one frame, as one event with history: the first sample is
     * the event, the rest go in through {@link MotionEvent#addBatch}, each with its own time — exactly what a
     * real touchscreen delivers, and what scroll velocity is computed from.
     */
    private synchronized boolean touchBatch(ControlMessage.TouchBatch b) {
        int slot = b.getPointerId();
        if (slot < 0 || slot >= MAX_POINTERS || !down[slot] || b.getSamples().isEmpty()) {
            // A batch for a finger we are not holding (its DOWN was on a socket that died): nothing to move.
            return true;
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = null;
        for (ControlMessage.Sample s : b.getSamples()) {
            lastX[slot] = s.getX() * width.getAsInt();
            lastY[slot] = s.getY() * height.getAsInt();
            lastPressure[slot] = Math.max(s.getPressure(), 0.01f);
            int count = fillPointers();
            long time = eventTime(s.getTMs(), now);
            if (event == null) {
                event = MotionEvent.obtain(lastTouchDown, time, MotionEvent.ACTION_MOVE, count, props, coords,
                        0, 0, 1f, 1f, DEFAULT_DEVICE_ID, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            } else {
                event.addBatch(time, coords, 0);
            }
        }
        return inject(event);
    }

    /** Builds {@link #props}/{@link #coords} from every finger that is down; returns how many. */
    private int fillPointers() {
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
            c.pressure = lastPressure[s];
            c.size = 1f;
            count++;
        }
        return count;
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
        if (action != MotionEvent.ACTION_DOWN && !down[slot]) {
            // A stray event for a finger we are not holding. The common case is the UP that arrives on the
            // car's *new* control socket after the old one died mid-gesture: we already cancelled that
            // gesture when the socket went away, so this UP has nothing to lift. Counting it as a failed
            // injection would be worse than useless — injectFailed is the number the guide tells people to
            // check for the Samsung INJECT_EVENTS permission, and a false one there sends them chasing a
            // permission problem they do not have.
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            down[slot] = true;
        }
        lastX[slot] = t.getX() * width.getAsInt();
        lastY[slot] = t.getY() * height.getAsInt();
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
                // A new gesture: our clock and the car's clock meet here.
                lastTouchDown = now;
                lastEventTime = now;
                carDownMs = t.getTMs();
            }
        } else if (action == MotionEvent.ACTION_DOWN) {
            action = MotionEvent.ACTION_POINTER_DOWN | (indexOfSlot << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        } else if (action == MotionEvent.ACTION_UP) {
            action = MotionEvent.ACTION_POINTER_UP | (indexOfSlot << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }
        if (t.getAction() == ControlMessage.Touch.UP || t.getAction() == ControlMessage.Touch.CANCEL) {
            down[slot] = false;
        }
        long time = action == MotionEvent.ACTION_DOWN ? now : eventTime(t.getTMs(), now);
        MotionEvent event = MotionEvent.obtain(lastTouchDown, time, action, count, props, coords, 0, 0, 1f, 1f,
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
