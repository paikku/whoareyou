package com.carcast.server;

import com.carcast.core.Json;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Ln;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The apps the car can start, and their icons — the "home" the car needs of its own.
 *
 * Why the car cannot just press HOME: that key is not ours to send. Android routes HOME and
 * APP_SWITCH to the *default* display no matter which display the event carries, so pressing them
 * from the car sends the phone to its own launcher and drags the running app back to display 0 with
 * it (real-car report #30: "phone took com.google.android.youtube (display 0)" right after a press).
 * The car therefore gets its own home and its own recents, built from this list and from the tasks
 * living on our virtual display, and those two keys are never injected at all.
 *
 * Reading the list costs a pass over every installed package, so it is cached; `refresh=1` rebuilds.
 */
final class AppList {
    /** Icon side in pixels. Big enough for a car screen at arm's length, small enough to ship ~100 of them. */
    private static final int ICON_PX = 96;

    private static volatile List<Map<String, Object>> cached;
    private static final Map<String, String> ICONS = new ConcurrentHashMap<>();

    private AppList() {
    }

    static String json(boolean withIcons, boolean refresh) {
        List<Map<String, Object>> apps = list(refresh);
        List<Object> out = new ArrayList<>(apps.size());
        for (Map<String, Object> app : apps) {
            Map<String, Object> m = new LinkedHashMap<>(app);
            if (withIcons) {
                String icon = icon((String) app.get("package"));
                if (icon != null) {
                    m.put("icon", icon);
                }
            }
            out.add(m);
        }
        return Json.INSTANCE.array(out);
    }

    static synchronized List<Map<String, Object>> list(boolean refresh) {
        List<Map<String, Object>> c = cached;
        if (c != null && !refresh) {
            return c;
        }
        List<Map<String, Object>> apps = new ArrayList<>();
        try {
            PackageManager pm = FakeContext.get().getPackageManager();
            for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                if (!info.enabled || launchIntent(pm, info.packageName) == null) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("package", info.packageName);
                CharSequence label = pm.getApplicationLabel(info);
                m.put("label", label == null ? info.packageName : label.toString());
                m.put("system", (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                apps.add(m);
            }
            Collections.sort(apps, Comparator.comparing(a -> ((String) a.get("label")).toLowerCase(java.util.Locale.ROOT)));
        } catch (Throwable t) {
            // A car with an empty home is bad; a car that cannot open the page at all is worse.
            Ln.e("could not list apps: " + t);
        }
        cached = apps;
        return apps;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLeanbackLaunchIntentForPackage(pkg);
    }

    /** The app's icon as a {@code data:} URI, or null when it cannot be rendered. Cached per package. */
    static String icon(String pkg) {
        String hit = ICONS.get(pkg);
        if (hit != null) {
            return hit.isEmpty() ? null : hit;
        }
        String uri = "";
        try {
            PackageManager pm = FakeContext.get().getPackageManager();
            Drawable d = pm.getApplicationIcon(pkg);
            Bitmap bmp = toBitmap(d);
            if (bmp != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                uri = "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            }
        } catch (Throwable t) {
            Ln.w("no icon for " + pkg + ": " + t);
        }
        ICONS.put(pkg, uri);
        return uri.isEmpty() ? null : uri;
    }

    private static Bitmap toBitmap(Drawable d) {
        if (d == null) {
            return null;
        }
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
            return Bitmap.createScaledBitmap(((BitmapDrawable) d).getBitmap(), ICON_PX, ICON_PX, true);
        }
        // Adaptive and vector icons have no bitmap of their own: draw them once at the size we ship.
        Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        d.setBounds(0, 0, ICON_PX, ICON_PX);
        d.draw(canvas);
        return bmp;
    }
}
