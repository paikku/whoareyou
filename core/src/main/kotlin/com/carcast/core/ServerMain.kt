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
 *  - https_port=3443    second listener for the same pages over TLS with a self-signed certificate
 *                       (0 turns it off). The car shows a warning once; past it the page is a secure
 *                       context, which is the only place /diag can ask whether this browser has
 *                       WebCodecs. A measuring instrument — see net/SelfSignedCert.kt.
 *  - tls_cert=<pem>     a certificate a public CA signed (chain) and its key, used instead of the
 *    tls_key=<pem>      self-signed one. The car this was built for will not let anyone click through a
 *                       certificate warning, so only a trusted certificate reaches a secure context there.
 *                       A pair bundled in the APK under assets/tls/ is used when these are absent.
 *  - daemon=true        do not watch stdin; run until killed (pkill -f com.carcast.server.Server).
 *                       For car tests without a PC in the car:
 *                       adb shell 'CLASSPATH=... setsid nohup app_process / com.carcast.server.Server <sha> daemon=true >/dev/null 2>&1 &'
 *
 * By default the process stays alive until stdin reaches EOF, which is how the app (or an
 * interactive `adb shell`) tears it down: closing the adb stream kills the server. That is the kill switch.
 */
object ServerMain {
    /** The TLS port. Plain 3333 stays the address everything else uses; this one is for /diag in the car. */
    const val DEFAULT_HTTPS_PORT = 3443

    class Options(val port: Int, val assets: Assets, val buildId: String, val raw: Map<String, String>) {
        val daemon: Boolean get() = raw["daemon"] == "true"
        val reportDir: File? get() = raw["reports"]?.let { File(it) }
            ?: File("/data/local/tmp").takeIf { it.isDirectory && it.canWrite() }?.let { File(it, "carcast") }
        /** Default on: the question it answers can only be asked from inside the car, one visit at a time. */
        val httpsPort: Int get() = raw["https_port"]?.toIntOrNull() ?: DEFAULT_HTTPS_PORT
        /** Next to the reports, which is the directory this process already owns. */
        val tlsKeystore: File? get() = reportDir?.let { File(it, "tls.p12") }
        val tlsCert: File? get() = raw["tls_cert"]?.let { File(it) } ?: reportDir?.let { File(it, "tls-cert.pem") }
        val tlsKey: File? get() = raw["tls_key"]?.let { File(it) } ?: reportDir?.let { File(it, "tls-key.pem") }
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
        startApp: ((String, String) -> Map<String, Any?>)? = null,
        control: ((ControlMessage) -> Unit)? = null,
        onControlGone: (() -> Unit)? = null,
        extraApi: ((String, String, Map<String, String>) -> String?)? = null,
        onStopped: () -> Unit = {},
        onReady: ((StreamSession) -> Unit)? = null,
    ) {
        val session = StreamSession(
            opts.assets, opts.port, process = "shell", extraStatus = extraStatus, reportDir = opts.reportDir, videoSource = videoSource,
            staticVersion = opts.buildId, httpsPort = opts.httpsPort, tlsKeystore = opts.tlsKeystore,
            tlsCert = opts.tlsCert, tlsKey = opts.tlsKey,
        )
        session.onStartApp = startApp
        session.controlHandler = control
        onControlGone?.let { session.onControlGone = it }
        onReady?.invoke(session)
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
