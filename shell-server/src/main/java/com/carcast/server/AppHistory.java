package com.carcast.server;

import com.genymobile.scrcpy.util.Ln;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 차에서 무엇을 언제 띄웠는지. 홈과 최근앱을 **최신순**으로 세우는 근거이고, 서버가 죽거나
 * 다시 떠도 남아 있어야 하므로 파일에 적는다.
 *
 * 저장 형식은 한 줄에 하나, 탭으로 나눈 `<마지막 사용 epoch ms>\t<횟수>\t<패키지>`. JSON 이 아닌
 * 이유는 셸 프로세스에 파서가 없기 때문이고, 굳이 들일 만한 일이 아니다 — 이 파일은 사람이 읽고
 * 고칠 수 있어야 하는 종류의 기록이다. 쓸 때는 임시 파일에 적고 옮긴다: 차에서 서버가 죽는 순간이
 * 하필 쓰는 중이면 목록이 통째로 날아가기 때문이다.
 */
final class AppHistory {
    private static final File FILE = new File("/data/local/tmp/carcast/app-history.tsv");
    /** 이보다 오래된 꼬리는 버린다. 차에서 쓰는 앱이 이보다 많을 일은 없다. */
    private static final int MAX = 200;

    private static final Map<String, long[]> USES = new LinkedHashMap<>(); // pkg -> {lastUsedMs, count}
    private static boolean loaded;

    private AppHistory() {
    }

    /** 차에서 앱을 띄웠다. 실패한 실행은 기록하지 않는다 — 쓴 적 없는 앱이 맨 위에 오면 안 된다. */
    static synchronized void used(String pkg) {
        // CarCast itself (the latency probe activity runs on the car display) is never something the driver
        // "used" — it must not float to the top of the car's home.
        if (pkg == null || pkg.isEmpty() || "com.carcast".equals(pkg)) {
            return;
        }
        load();
        long[] e = USES.get(pkg);
        if (e == null) {
            e = new long[] {0, 0};
            USES.put(pkg, e);
        }
        e[0] = System.currentTimeMillis();
        e[1]++;
        save();
    }

    /** 마지막으로 쓴 시각(ms), 쓴 적 없으면 0. */
    static synchronized long lastUsed(String pkg) {
        load();
        long[] e = USES.get(pkg);
        return e == null ? 0 : e[0];
    }

    /** 최신순 패키지 목록. */
    static synchronized List<String> recent() {
        load();
        List<Map.Entry<String, long[]>> all = new ArrayList<>(USES.entrySet());
        all.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
        List<String> out = new ArrayList<>(all.size());
        for (Map.Entry<String, long[]> e : all) {
            out.add(e.getKey());
        }
        return out;
    }

    static synchronized int size() {
        load();
        return USES.size();
    }

    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!FILE.isFile()) {
            return;
        }
        try {
            for (String line : new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8).split("\n")) {
                String[] f = line.split("\t");
                if (f.length != 3) {
                    continue;
                }
                try {
                    USES.put(f[2], new long[] {Long.parseLong(f[0]), Long.parseLong(f[1])});
                } catch (NumberFormatException ignored) {
                    // 한 줄이 깨졌다고 나머지를 버릴 이유는 없다.
                }
            }
            Ln.i("app history: " + USES.size() + " entries from " + FILE);
        } catch (Throwable t) {
            Ln.w("could not read " + FILE + ": " + t);
        }
    }

    private static void save() {
        try {
            File dir = FILE.getParentFile();
            if (dir != null) {
                dir.mkdirs();
            }
            List<Map.Entry<String, long[]>> all = new ArrayList<>(USES.entrySet());
            all.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, long[]> e : all) {
                if (n++ >= MAX) {
                    break;
                }
                sb.append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\t').append(e.getKey()).append('\n');
            }
            File tmp = new File(FILE.getParentFile(), FILE.getName() + ".tmp");
            Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Throwable t) {
            Ln.w("could not write " + FILE + ": " + t);
        }
    }
}
