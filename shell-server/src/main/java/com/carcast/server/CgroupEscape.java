package com.carcast.server;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Moves this process out of adbd's cgroup so it survives adbd stopping.
 *
 * init puts every service in its own cgroup ({@code /sys/fs/cgroup/uid_<uid>/pid_<pid>}) and, when the
 * service stops or dies, SIGKILLs everything still in that cgroup (libprocessgroup killProcessGroup).
 * Android switches wireless debugging off whenever Wi-Fi drops, and if USB debugging is off as well
 * AdbService stops adbd — so a server started from {@code adb shell} was killed the moment the phone went
 * from Wi-Fi to hotspot (seen on build 306d41a: up for 25 s, gone the second wireless debugging turned
 * off, no crash in the log because SIGKILL leaves none). {@code setsid nohup} cannot help; that only
 * covers SIGHUP. Writing our pid to an ancestor cgroup's {@code cgroup.procs} takes us out of the doomed
 * group. Whether the shell user is allowed to depends on the ROM's file modes and policy, so this is
 * best-effort and reports exactly what it did; the sure fallback is keeping USB debugging on, which keeps
 * adbd — and with it its cgroup — alive.
 */
final class CgroupEscape {
    private CgroupEscape() {
    }

    /** Every line of /proc/self/cgroup, joined; what the app shows so we can see where adbd put us. */
    static String current() {
        try {
            return String.join(" | ", Files.readAllLines(Paths.get("/proc/self/cgroup"), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return "(unreadable: " + e + ")";
        }
    }

    /**
     * {@code cgroup.procs} files to try, nearest ancestor first, for each hierarchy we are in. Lines look
     * like {@code 0::/uid_0/pid_1234} (v2) or {@code 3:cpuacct:/uid_0/pid_1234} (v1, mounted under /acct
     * and /dev/<name> on Android). Package-private for the unit test.
     */
    static List<String> candidates(List<String> cgroupLines, int uid) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : cgroupLines) {
            String[] parts = line.split(":", 3);
            if (parts.length < 3 || !parts[2].startsWith("/")) {
                continue;
            }
            String root = mountFor(parts[0], parts[1]);
            if (root == null) {
                continue;
            }
            String path = parts[2];
            // A sibling group owned by our own uid is the friendliest destination, then each ancestor up to the root.
            out.add(root + "/uid_" + uid + "/cgroup.procs");
            while (!path.equals("/")) {
                int slash = path.lastIndexOf('/');
                path = slash <= 0 ? "/" : path.substring(0, slash);
                out.add(root + (path.equals("/") ? "" : path) + "/cgroup.procs");
            }
        }
        return new ArrayList<>(out);
    }

    private static String mountFor(String hierarchyId, String controllers) {
        if ("0".equals(hierarchyId)) {
            return "/sys/fs/cgroup";
        }
        for (String c : controllers.split(",")) {
            switch (c) {
                case "cpuacct": return "/acct";
                case "cpuset": return "/dev/cpuset";
                case "cpu": return "/dev/cpuctl";
                case "memory": return "/dev/memcg";
                case "blkio": return "/dev/blkio";
                case "freezer": return "/dev/freezer";
                default: break;
            }
        }
        return null;
    }

    /** Tries each candidate; returns a one-line report for the log. Never throws. */
    static String apply() {
        String before = current();
        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get("/proc/self/cgroup"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "cannot read /proc/self/cgroup: " + e;
        }
        int uid = android.os.Process.myUid();
        int pid = android.os.Process.myPid();
        StringBuilder errors = new StringBuilder();
        for (String procs : candidates(lines, uid)) {
            File f = new File(procs);
            if (!f.exists()) {
                continue;
            }
            // O_APPEND like Magisk/Shizuku do; cgroup.procs moves the whole thread group at once.
            try (FileOutputStream o = new FileOutputStream(f, true)) {
                o.write((pid + "\n").getBytes(StandardCharsets.UTF_8));
                return "left adbd's group via " + procs + " (was " + before + ", now " + current() + ")";
            } catch (IOException e) {
                errors.append(' ').append(procs).append(": ").append(e.getMessage()).append(';');
            }
        }
        return "still in " + before + " — adbd stopping (wireless debugging off with USB debugging off) will kill this server;"
                + (errors.length() == 0 ? " no writable cgroup.procs found" : errors.toString());
    }
}
