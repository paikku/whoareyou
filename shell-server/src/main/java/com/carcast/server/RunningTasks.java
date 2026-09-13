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
 * asks here instead, and the answer holds only what lives on our display: what the driver is using
 * in the car, newest first. The phone's own apps are not the car's business and are not listed —
 * only counted, so the car can say "여기엔 없지만 폰에 N개" instead of a bare "없습니다".
 */
final class RunningTasks {
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
        for (TaskList.Task t : tasks) {
            if (t.getDisplayId() != ourDisplay) {
                elsewhere++;
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getTaskId());
            m.put("name", t.getName());
            m.put("package", t.getPackageName());
            m.put("display", t.getDisplayId());
            m.put("label", labelOf(t.getPackageName()));
            m.put("lastUsed", AppHistory.lastUsed(t.getPackageName()));
            here.add(m);
        }
        // 최신순. `am` 도 앞에 있는 것부터 주지만, 우리가 언제 띄웠는지를 아는 쪽이 더 정확하다
        // (폰에서 만졌다 돌아온 앱은 `am` 순서가 우리 기억과 다를 수 있다).
        here.sort((x, y) -> Long.compare((Long) y.get("lastUsed"), (Long) x.get("lastUsed")));
        out.addAll(here);
        res.put("tasks", out);
        res.put("elsewhere", elsewhere);
        if (error != null) {
            res.put("error", error);
        }
        return Json.INSTANCE.obj(res);
    }

    /** The human name for a package, from the app list we already built; falls back to the package. */
    private static String labelOf(String pkg) {
        for (Map<String, Object> app : AppList.list(false)) {
            if (pkg.equals(app.get("package"))) {
                return (String) app.get("label");
            }
        }
        return pkg;
    }
}
