package com.carcast.server;

/**
 * Entry point run by {@code app_process} as the shell user (M3+).
 * Launched as: CLASSPATH=&lt;base.apk&gt; app_process / com.carcast.server.Server &lt;build-id&gt; key=value...
 */
public final class Server {
    private Server() {
    }

    public static void main(String... args) {
        System.out.println("carcast-server placeholder uid=" + android.os.Process.myUid());
    }
}
