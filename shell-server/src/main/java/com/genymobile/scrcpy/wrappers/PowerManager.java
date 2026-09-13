package com.genymobile.scrcpy.wrappers;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.util.Ln;

import android.os.Build;
import android.os.IInterface;
import android.os.SystemClock;

import java.lang.reflect.Method;

public final class PowerManager {

    private static final int USER_ACTIVITY_EVENT_OTHER = 0;
    /** PowerManager.WAKE_REASON_APPLICATION */
    private static final int WAKE_REASON_APPLICATION = 2;

    private final IInterface manager;
    private Method isScreenOnMethod;
    private Method userActivityMethod;
    private Method wakeUpWithDisplayIdMethod;
    private boolean wakeUpWithDisplayIdMissing;

    static PowerManager create() {
        IInterface manager = ServiceManager.getService("power", "android.os.IPowerManager");
        return new PowerManager(manager);
    }

    private PowerManager(IInterface manager) {
        this.manager = manager;
    }

    private Method getIsScreenOnMethod() throws NoSuchMethodException {
        if (isScreenOnMethod == null) {
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                isScreenOnMethod = manager.getClass().getMethod("isDisplayInteractive", int.class);
            } else {
                isScreenOnMethod = manager.getClass().getMethod("isInteractive");
            }
        }
        return isScreenOnMethod;
    }

    public boolean isScreenOn(int displayId) {

        try {
            Method method = getIsScreenOnMethod();
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                return (boolean) method.invoke(manager, displayId);
            }
            return (boolean) method.invoke(manager);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
            return false;
        }
    }

    private Method getUserActivityMethod() throws NoSuchMethodException {
        if (userActivityMethod == null) {
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                // userActivity(int displayId, long time, int event, int flags);
                userActivityMethod = manager.getClass().getMethod("userActivity", int.class, long.class, int.class, int.class);
            } else {
                // userActivity(long time, int event, int flags);
                userActivityMethod = manager.getClass().getMethod("userActivity", long.class, int.class, int.class);
            }
        }
        return userActivityMethod;
    }

    public void userActivity(int displayId) {
        try {
            Method method = getUserActivityMethod();
            long time = SystemClock.uptimeMillis();
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_31_ANDROID_12) {
                method.invoke(manager, displayId, time, USER_ACTIVITY_EVENT_OTHER, 0);
                return;
            }
            method.invoke(manager, time, USER_ACTIVITY_EVENT_OTHER, 0);
        } catch (ReflectiveOperationException e) {
            Ln.e("Could not invoke method", e);
        }
    }

    /**
     * Wake only the display group that {@code displayId} belongs to.
     *
     * The phone's own panel lives in the default group; a virtual display created with
     * OWN_DISPLAY_GROUP has its own. So waking the virtual display's group brings the car's picture
     * back without lighting the phone — unlike {@code input keyevent WAKEUP}, which wakes the whole
     * device and makes the panel flash before we darken it again.
     *
     * {@code wakeUpWithDisplayId} exists since Android 14 (it is what the no-arg {@code wakeUp}
     * delegates to, with DEFAULT_DISPLAY). It needs DEVICE_POWER, which the shell package holds.
     *
     * @return true if the call went through (not that the group actually woke — ask isScreenOn).
     */
    public boolean wakeUpDisplay(int displayId) {
        if (wakeUpWithDisplayIdMissing) {
            return false;
        }
        try {
            if (wakeUpWithDisplayIdMethod == null) {
                // wakeUpWithDisplayId(long time, int reason, String details, String opPackageName, int displayId)
                wakeUpWithDisplayIdMethod = manager.getClass()
                        .getMethod("wakeUpWithDisplayId", long.class, int.class, String.class, String.class, int.class);
            }
            wakeUpWithDisplayIdMethod.invoke(manager, SystemClock.uptimeMillis(), WAKE_REASON_APPLICATION,
                    "carcast:vd-wake", com.genymobile.scrcpy.FakeContext.PACKAGE_NAME, displayId);
            return true;
        } catch (NoSuchMethodException e) {
            Ln.i("wakeUpWithDisplayId is not available on this Android version");
            wakeUpWithDisplayIdMissing = true;
            return false;
        } catch (ReflectiveOperationException e) {
            Ln.w("wakeUpWithDisplayId(" + displayId + ") failed: " + e.getCause());
            wakeUpWithDisplayIdMissing = true; // a SecurityException here will repeat every time
            return false;
        }
    }
}
