package com.carcast.server;

import com.carcast.core.Json;
import com.carcast.core.TaskList;
import com.genymobile.scrcpy.util.Command;
import com.genymobile.scrcpy.util.Ln;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is running **on the car's own display** — the list behind the car's "recents".
 *
 * The phone's APP_SWITCH key would show the *phone's* recents (Android routes that key to the
 * default display), which is both the wrong list and a way to lose the app to display 0. So the car
 * asks here instead. {@code tasks} is what lives on our display — what the driver is using in the car,
 * newest first. {@code phone} is what the phone is using (display 0, front-most first): since a start
 * from the car brings an app over exactly as it is (restart=never), those are one tap from the car too,
 * and the driver wants to see them here — "이거 폰에서 보던 건데" is the whole point. Only real apps
 * are listed there (the launcher, system UI and CarCast itself are not things to bring over), and no
 * more than {@link #PHONE_MAX}: the phone's recents can hold dozens of dead tasks nobody remembers.
 * {@code elsewhere} still counts every task off our display, listed or not.
 */
final class RunningTasks {
    /** How many phone tasks to list. Front-most first, so the one being used right now is always in. */
    static final int PHONE_MAX = 12;
    /** Our own app never belongs in the car's recents — bringing it over would move the phone UI to the car. */
    private static final String SELF = "com.carcast";

    private RunningTasks() {
    }

    static String json(int ourDisplay) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("display", ourDisplay);
        List<Object> out = new ArrayList<>();
        String error = null;
        List<TaskList.Task> tasks;
        try {
            tasks = TaskList.INSTANCE.parse(Command.execReadOutput("am", "stack", "list"));
        } catch (Exception e) {
            // "도는 앱이 없다"와 "물어보지도 못했다"는 다르다. 차가 그 둘을 구별할 수 있어야 한다.
            Ln.w("am stack list failed: " + e);
            tasks = new ArrayList<>();
            error = "am stack list: " + e;
        }
        int elsewhere = 0;
        List<Map<String, Object>> here = new ArrayList<>();
        List<Map<String, Object>> phone = new ArrayList<>();
        java.util.Set<String> seenOnPhone = new java.util.HashSet<>();
        Map<String, String> apps = launchable();
        for (TaskList.Task t : tasks) {
            String pkg = t.getPackageName();
            if (t.getDisplayId() != ourDisplay) {
                elsewhere++;
                // 폰 화면(0)의 앱 중 차로 가져올 만한 것만: 런처·시스템 UI·CarCast 자신은 뺀다. 앱마다 한 번.
                if (t.getDisplayId() == 0 && phone.size() < PHONE_MAX && apps.containsKey(pkg)
                        && !SELF.equals(pkg) && seenOnPhone.add(pkg)) {
                    phone.add(row(t, apps.get(pkg)));
                }
                continue;
            }
            here.add(row(t, apps.getOrDefault(pkg, pkg)));
        }
        // 최신순. `am` 도 앞에 있는 것부터 주지만, 우리가 언제 띄웠는지를 아는 쪽이 더 정확하다
        // (폰에서 만졌다 돌아온 앱은 `am` 순서가 우리 기억과 다를 수 있다).
        here.sort((x, y) -> Long.compare((Long) y.get("lastUsed"), (Long) x.get("lastUsed")));
        out.addAll(here);
        res.put("tasks", out);
        // 폰 쪽은 `am` 의 순서 그대로 — 앞에 있는 것이 폰에서 지금 보고 있는 것이다.
        res.put("phone", phone);
        res.put("elsewhere", elsewhere);
        if (error != null) {
            res.put("error", error);
        }
        return Json.INSTANCE.obj(res);
    }

    private static Map<String, Object> row(TaskList.Task t, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.getTaskId());
        m.put("name", t.getName());
        m.put("package", t.getPackageName());
        m.put("display", t.getDisplayId());
        m.put("label", label);
        m.put("lastUsed", AppHistory.lastUsed(t.getPackageName()));
        return m;
    }

    /** package → human name, for every app the car's home can start (the list we already built). */
    private static Map<String, String> launchable() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map<String, Object> app : AppList.list(false)) {
            m.put((String) app.get("package"), (String) app.get("label"));
        }
        return m;
    }
}
