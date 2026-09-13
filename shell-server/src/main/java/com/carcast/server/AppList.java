package com.carcast.server;

import com.carcast.core.Json;
import com.genymobile.scrcpy.FakeContext;
import com.genymobile.scrcpy.util.Command;
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
 * **Icons are never rendered for the whole list.** The first version did, and on a real phone that is
 * hundreds of {@code getApplicationIcon()} calls in one request, each opening another app's APK to read
 * its resources. The virtual phone has 22 apps and never noticed; an S26 Ultra has hundreds, and the
 * server came back with the control socket still alive but **every new connection failing** — the shape
 * of a process out of file descriptors (real-car reports #31-33). So the list is names only, and icons
 * come one at a time from /api/icon as the car draws them.
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
    /** Why the list is empty, when it is. The car shows this instead of "no apps". */
    private static volatile String problem = "";
    /** Where the list came from: "package-manager" or "shell" (the fallback). */
    private static volatile String origin = "";
    private static final Map<String, String> ICONS = new ConcurrentHashMap<>();

    private AppList() {
    }

    static String json(boolean refresh) {
        List<Map<String, Object>> apps = list(refresh);
        if (apps.isEmpty()) {
            // An empty array reads as "this phone has no apps", which is never true and tells the driver
            // nothing. Say what went wrong instead — the car puts it on screen.
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", problem.isEmpty() ? "앱 목록이 비어 있습니다" : problem);
            return Json.INSTANCE.obj(err);
        }
        return Json.INSTANCE.array(new ArrayList<Object>(apps));
    }

    /** For /api/status: how many apps we found and where they came from (or why we found none). */
    static Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> apps = cached;
        m.put("apps", apps == null ? null : apps.size());
        m.put("appsFrom", origin.isEmpty() ? null : origin);
        if (!problem.isEmpty()) {
            m.put("appsError", problem);
        }
        return m;
    }

    /** One app's icon, as {@code {"package":…,"icon":"data:…"|null}}. */
    static String iconJson(String pkg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("package", pkg);
        m.put("icon", pkg == null || pkg.isEmpty() ? null : icon(pkg));
        return Json.INSTANCE.obj(m);
    }

    static synchronized List<Map<String, Object>> list(boolean refresh) {
        List<Map<String, Object>> c = cached;
        if (c != null && !refresh) {
            return c;
        }
        List<Map<String, Object>> apps = new ArrayList<>();
        problem = "";
        origin = "";
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
            origin = "package-manager";
        } catch (Throwable t) {
            // PackageManager from a bare app_process is not a given: it depends on the fake context that
            // Workarounds builds, and that has ROM-specific ways of not working. Say so, then try the
            // other road rather than handing the car an empty home.
            problem = "PackageManager: " + t;
            Ln.e("could not list apps through PackageManager: " + t);
        }
        if (apps.isEmpty()) {
            apps = listViaShell();
        }
        Collections.sort(apps, Comparator.comparing(a -> ((String) a.get("label")).toLowerCase(java.util.Locale.ROOT)));
        cached = apps;
        return apps;
    }

    /**
     * The same question asked the way the shell asks it: which components answer the launcher intent.
     * No labels (the package name stands in) and no icons, but it needs nothing but `cmd`, so it works
     * where the framework path does not — and a home with plain names beats a home with nothing.
     */
    private static List<Map<String, Object>> listViaShell() {
        List<Map<String, Object>> apps = new ArrayList<>();
        try {
            String out = Command.execReadOutput("cmd", "package", "query-activities", "--brief",
                    "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER");
            java.util.Set<String> seen = new java.util.LinkedHashSet<>();
            for (String line : out.split("\n")) {
                String s = line.trim();
                int slash = s.indexOf('/');
                if (slash <= 0 || s.contains(" ") || s.contains("=")) {
                    continue;
                }
                seen.add(s.substring(0, slash));
            }
            for (String pkg : seen) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("package", pkg);
                m.put("label", pkg);
                m.put("system", false);
                apps.add(m);
            }
            if (!apps.isEmpty()) {
                origin = "shell";
                Ln.i("app list via `cmd package query-activities`: " + apps.size() + " apps");
            } else if (problem.isEmpty()) {
                problem = "cmd package query-activities 가 아무것도 돌려주지 않았습니다";
            }
        } catch (Throwable t) {
            String why = "cmd package query-activities: " + t;
            problem = problem.isEmpty() ? why : problem + " / " + why;
            Ln.e("could not list apps through the shell either: " + t);
        }
        return apps;
    }

    private static Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLeanbackLaunchIntentForPackage(pkg);
    }

    /**
     * The app's icon as a {@code data:} URI, or null when it cannot be rendered. Cached per package,
     * and **one at a time**: reading an icon loads another app's resources, and doing that for a whole
     * phone's worth of apps at once is what broke the server (see the class comment).
     */
    static synchronized String icon(String pkg) {
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
