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
 * What is actually running on the car's own display — the list behind the car's "recents".
 *
 * The phone's APP_SWITCH key would show the *phone's* recents (Android routes that key to the
 * default display), which is both the wrong list and a way to lose the app to display 0. So the car
 * asks here instead: every task, which display it sits on, and whether that display is ours.
 */
final class RunningTasks {
    private RunningTasks() {
    }

    static String json(int ourDisplay) {
        List<Object> out = new ArrayList<>();
        for (TaskList.Task t : list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("taskId", t.getTaskId());
            m.put("name", t.getName());
            m.put("package", t.getPackageName());
            m.put("display", t.getDisplayId());
            // The car shows "here" apps as its recents and can offer to pull back a "phone" one.
            m.put("here", t.getDisplayId() == ourDisplay);
            m.put("label", labelOf(t.getPackageName()));
            out.add(m);
        }
        return Json.INSTANCE.array(out);
    }

    private static List<TaskList.Task> list() {
        try {
            return TaskList.INSTANCE.parse(Command.execReadOutput("am", "stack", "list"));
        } catch (Exception e) {
            Ln.w("am stack list failed: " + e);
            return new ArrayList<>();
        }
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
