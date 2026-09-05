package com.carcast.core

import com.carcast.core.media.ControlMessage
import com.carcast.core.media.VideoSource
import java.io.File

/**
 * Entry point of the shell-uid server (wrapped by com.carcast.server.Server under app_process)
 * and of the desktop dry run (`./gradlew :core:run`).
 *
 * Arguments: `<build-id> [key=value ...]`
 *  - port=3333          TCP port for HTTP + WebSocket
 *  - apk=<path>         where to read the web client and clips from; defaults to the CLASSPATH
 *                       environment variable, which app_process sets to the APK path
 *  - assets=<dir>       alternative to apk= for development (e.g. app/src/main/assets)
 *  - reports=<dir>      where /diag reports posted by the car are kept (one JSON file each). Defaults to
 *                       /data/local/tmp/carcast on Android (writable by shell), memory-only elsewhere.
 *  - daemon=true        do not watch stdin; run until killed (pkill -f com.carcast.server.Server).
 *                       For car tests without a PC in the car:
 *                       adb shell 'CLASSPATH=... setsid nohup app_process / com.carcast.server.Server <sha> daemon=true >/dev/null 2>&1 &'
 *
 * By default the process stays alive until stdin reaches EOF, which is how the app (or an
 * interactive `adb shell`) tears it down: closing the adb stream kills the server. That is the kill switch.
 */
object ServerMain {
    class Options(val port: Int, val assets: Assets, val buildId: String, val raw: Map<String, String>) {
        val daemon: Boolean get() = raw["daemon"] == "true"
        val reportDir: File? get() = raw["reports"]?.let { File(it) }
            ?: File("/data/local/tmp").takeIf { it.isDirectory && it.canWrite() }?.let { File(it, "carcast") }
    }

    fun parse(args: Array<String>): Options {
        require(args.isNotEmpty()) { "usage: <build-id> [key=value ...]" }
        val kv = args.drop(1).associate { a ->
            val i = a.indexOf('=')
            require(i > 0) { "bad argument '$a', expected key=value" }
            a.substring(0, i) to a.substring(i + 1)
        }
        val assets = when {
            kv["assets"] != null -> ZipAssets.ofDirectory(File(kv.getValue("assets")))
            kv["apk"] != null -> ZipAssets(kv.getValue("apk"))
            else -> {
                val cp = System.getenv("CLASSPATH")?.split(':')?.firstOrNull { it.endsWith(".apk") }
                    ?: error("no apk= / assets= argument and CLASSPATH does not point at an APK")
                ZipAssets(cp)
            }
        }
        return Options(kv["port"]?.toInt() ?: 3333, assets, args[0], kv)
    }

    /**
     * Runs a session until stdin closes (or [stopOnStdinEof] is false), or until loopback posts
     * `/api/stop` — the app's kill switch for a detached (`daemon=true`) server.
     */
    fun run(
        opts: Options,
        extraStatus: () -> Map<String, Any?> = { emptyMap() },
        stopOnStdinEof: Boolean = !opts.daemon,
        videoSource: VideoSource? = null,
        startApp: ((String) -> String)? = null,
        control: ((ControlMessage) -> Unit)? = null,
        extraApi: ((String, String, Map<String, String>) -> String?)? = null,
        onStopped: () -> Unit = {},
    ) {
        val session = StreamSession(opts.assets, opts.port, process = "shell", extraStatus = extraStatus, reportDir = opts.reportDir, videoSource = videoSource)
        session.onStartApp = startApp
        session.controlHandler = control
        session.extraApi = extraApi
        val stopped = java.util.concurrent.CountDownLatch(1)
        session.onStopRequest = { stopped.countDown() }
        try {
            session.start()
        } catch (e: Throwable) {
            println("carcast-server: could not start on port ${opts.port}: $e")
            e.printStackTrace()
            System.out.flush()
            throw e
        }
        println("carcast-server ready build=${opts.buildId} port=${opts.port}")
        System.out.flush()
        Runtime.getRuntime().addShutdownHook(Thread { session.stop() })
        try {
            if (stopOnStdinEof) {
                Thread({
                    val buf = ByteArray(256)
                    runCatching { while (System.`in`.read(buf) >= 0) { /* ignore input, wait for EOF */ } }
                    println("stdin closed, stopping")
                    stopped.countDown()
                }, "stdin").apply { isDaemon = true }.start()
            }
            stopped.await()
        } catch (_: InterruptedException) {
        } finally {
            session.stop()
            onStopped()
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // Log lines carry Korean; make stdout UTF-8 regardless of the JVM's default locale.
        System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
        val opts = try { parse(args) } catch (e: Exception) { System.err.println(e.message); System.exit(2); return }
        run(opts)
    }
}
