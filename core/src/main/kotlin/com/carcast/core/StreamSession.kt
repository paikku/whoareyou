package com.carcast.core

import com.carcast.core.media.ClipSource
import com.carcast.core.media.ControlMessage
import com.carcast.core.media.MediaHub
import com.carcast.core.media.VideoSource
import com.carcast.core.net.HttpServer
import com.carcast.core.net.SelfSignedCert
import com.carcast.core.net.WebSocketConnection
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One streaming session: HTTP/WS server, video and audio fan-out, control channel and (until the
 * screen capture exists) the bundled test clip as the video source. Process-agnostic: the shell-uid
 * server and the app's optional in-process server both drive this class.
 */
class StreamSession(
    private val assets: Assets,
    val port: Int,
    /** Where this session runs, reported in /api/status: "shell" or "app". */
    private val process: String,
    /** Extra fields for /api/status (e.g. tun state), evaluated on each request. */
    private val extraStatus: () -> Map<String, Any?> = { emptyMap() },
    /** Where diagnostic reports from the car are kept across restarts; null keeps them in memory only. */
    reportDir: File? = null,
    /** Live video (the virtual display in the shell process); null falls back to the bundled test clip. */
    private val videoSource: VideoSource? = null,
    /** Build sha of the bundled web files; lets the car cache them (HttpServer.serveStatic). Null: no caching. */
    private val staticVersion: String? = null,
    /**
     * A second listener for the same pages over TLS (0: off). It is a measuring instrument, not a feature:
     * the certificate is self-signed, so the car shows a warning that has to be clicked through, and the one
     * thing that buys is a **secure context** — the only place `VideoDecoder` (WebCodecs) can be asked about
     * at all. /diag reports what it finds there. See [com.carcast.core.net.SelfSignedCert].
     */
    private val httpsPort: Int = 0,
    /** Where the self-signed certificate is kept so the car is not asked to trust a new one every start. */
    private val tlsKeystore: File? = null,
    /**
     * A certificate somebody's CA already signed (PEM chain + key), preferred over the self-signed one.
     * This is what a car that will not let anyone click through a warning needs — see [startTls].
     */
    private val tlsCert: File? = null,
    private val tlsKey: File? = null,
) {
    val reports = ReportStore(reportDir)
    private var http: HttpServer? = null
    private var https: HttpServer? = null
    /** SHA-256 of the certificate the car is being asked to accept; null when TLS is off. */
    @Volatile private var tlsFingerprint: String? = null
    /** What that certificate is, and where the car should go: see [startTls] and /api/status. */
    @Volatile private var tlsSubject: String? = null
    @Volatile private var tlsTrusted = false
    @Volatile private var tlsHost: String? = null
    private val videoHub = MediaHub()
    private val audioHub = MediaHub()
    private var clip: ClipSource? = null
    @Volatile private var liveSourceRunning = false
    private val controlClients = CopyOnWriteArrayList<WebSocketConnection>()

    @Volatile var running = false
        private set
    @Volatile var controlPackets = 0L
        private set
    val videoClients: Int get() = videoHub.clientCount
    val controlClientCount: Int get() = controlClients.size

    /** Human-readable events (also logged); the app shows them on screen, the shell prints them. */
    var onEvent: (String) -> Unit = {}

    /**
     * Called when loopback asks for `POST /api/stop`: the app's kill switch for a detached shell
     * server (wireless debugging is off whenever Wi-Fi is, so adb cannot be relied on to kill it).
     * Only 127.x may ask; the car or anyone on the hotspot cannot.
     */
    var onStopRequest: () -> Unit = {}

    /**
     * `POST /api/app?name=<package or package/.Activity>[&restart=auto|always|never]`: start an app on the streamed
     * display. `restart` says what to do when the app already has a task somewhere: `never` (default) lets Android
     * *move* that task to the car as it is — the phone loses it, the car gets it mid-state — or bring it to front,
     * `auto` force-stops it first only when that task is on another display (the phone's screen), `always` restarts
     * it regardless.
     * Returns the fields merged into the JSON reply (`result`, `action`, `fromDisplay`, `display`, …).
     */
    var onStartApp: ((name: String, restart: String) -> Map<String, Any?>)? = null

    /** Receives parsed car → phone control messages (touch/key/text); the shell process injects them. */
    var controlHandler: ((ControlMessage) -> Unit)? = null

    /**
     * The last control client went away. The host uses it to let go of anything that client left held —
     * a finger that was down when the socket died would otherwise stay down forever (InputInjector.cancelAll).
     */
    var onControlGone: () -> Unit = {}

    /** Extra /api endpoints from the host process (e.g. /api/screen); return JSON or null for "not mine". */
    var extraApi: ((method: String, path: String, query: Map<String, String>) -> String?)? = null

    private fun event(s: String) { Log.i(TAG, s); onEvent(s) }

    /** 마지막으로 남긴 원격 accept 의 "호스트 → 로컬주소", 그리고 그 뒤로 같은 짝이 몇 번 왔는지. */
    private var lastAcceptRoute = ""
    private var sameRouteAccepts = 0L

    @Throws(IOException::class)
    fun start() {
        if (running) return
        val server = HttpServer(assets, port, ::onWebSocket, ::onApi, staticVersion)
        // The app polls /api/status over loopback every 2 s; only remote (car/laptop) connections are events.
        //
        // 그런데 차의 페이지도 2 초마다 /api/status 를 부르고, 응답은 Connection: close 다 — 즉 원격
        // accept 도 초당 한 번꼴로 쏟아진다. 한 줄씩 남기면 /api/log 의 300 줄이 6~10 분이면 다 밀려
        // 나가서, 정작 보려던 사건(안 읽는 클라이언트, 소스 실패)이 사라진다. 그래서 **어디서 어디로**
        // 가 달라질 때만 남기고, 같은 짝이 이어지면 세어 두었다가 가끔 한 줄로 알린다.
        server.onAccept = { remote, local ->
            if (remote.startsWith("127.")) {
                Log.d(TAG, "accept $remote → $local")
            } else {
                val route = "${remote.substringBeforeLast(':')} → $local" // 포트는 매번 달라진다
                val n = if (route == lastAcceptRoute) ++sameRouteAccepts else { lastAcceptRoute = route; sameRouteAccepts = 1; 1L }
                if (n == 1L) event("accept $route") else if (n % ACCEPT_LOG_EVERY == 0L) event("accept $route (${n}회째)")
            }
        }
        server.start()
        http = server
        running = true
        event("HTTP 서버 시작 ($process): 0.0.0.0:$port" + if (reports.size > 0) ", 저장된 진단 ${reports.size}건" else "")
        startTls()
        videoHub.onClientStalled = { remote, queued -> event("video 클라이언트 $remote 가 안 읽음: 큐 $queued 개, 다음 키프레임까지 버림") }
        videoHub.onNeedKeyframe = { requestKeyframe() }
        videoHub.onClientDropped = { remote -> event("video 클라이언트 $remote 를 놓아줌: init 세그먼트를 받지 못함 — 재접속을 기다린다") }
        val live = videoSource
        if (live != null) {
            try {
                videoHub.onClientAttached = { live.requestKeyframe() }
                live.start(videoHub)
                liveSourceRunning = true
                event("라이브 소스 시작: ${live.info()}")
            } catch (e: Throwable) {
                // Throwable, not Exception: a NoClassDefFoundError/AssertionError from the scrcpy reflection
                // layer must also degrade to the clip instead of taking the HTTP server down with it.
                Log.e(TAG, "live source failed, falling back to the clip", e)
                event("라이브 소스 실패 (${e.message ?: e}) — 테스트 클립으로 대체")
                startClip()
            }
        } else startClip()
    }

    /**
     * The TLS listener, if one was asked for. Never fatal: a phone whose security provider will not make an
     * EC key, or a keystore that cannot be written, must cost the driver a diagnostic — not the picture.
     */
    private fun startTls() {
        if (httpsPort <= 0) return
        try {
            val tls = tlsCredentials()
            val server = HttpServer(assets, httpsPort, ::onWebSocket, ::onApi, staticVersion, ssl = tls.sslContext)
            server.start()
            https = server
            tlsFingerprint = tls.fingerprint
            tlsSubject = tls.subject
            tlsTrusted = !tls.selfSigned
            tlsHost = tls.hostFor(SelfSignedCert.CAR_ADDRESS)
            event(
                if (tlsTrusted) "HTTPS 서버 시작: 0.0.0.0:$httpsPort — ${tls.subject}, 차는 https://${tlsHost}:$httpsPort 를 경고 없이 연다"
                else "HTTPS 서버 시작: 0.0.0.0:$httpsPort — 자체서명이라 차가 경고를 넘겨야 한다 (인증서 ${tls.fingerprint.take(17)}…)"
            )
        } catch (e: Throwable) {
            event("HTTPS 서버 실패 (${e.message ?: e}) — 평문은 그대로 돈다")
            Log.w(TAG, "TLS listener failed", e)
        }
    }

    /**
     * Which certificate to serve, best first.
     *
     * 1. Files the host pointed at (`tls_cert=`/`tls_key=`), 2. a pair bundled in the APK under `tls/`,
     * 3. one we sign ourselves. The first two are for a car that cannot click through a warning: this Model Y
     * (2026-09-17) showed the **non-overridable** interstitial for a self-signed certificate — no "Advanced",
     * no way in — so on that car only a publicly trusted certificate reaches a secure context at all.
     */
    private fun tlsCredentials(): SelfSignedCert.Tls {
        val fromFiles = tlsCert?.takeIf { it.canRead() } to tlsKey?.takeIf { it.canRead() }
        if (fromFiles.first != null && fromFiles.second != null) {
            return SelfSignedCert.fromPem(fromFiles.first!!.readText(), fromFiles.second!!.readText())
        }
        if (assets.exists(TLS_CERT_ASSET) && assets.exists(TLS_KEY_ASSET)) {
            val cert = assets.open(TLS_CERT_ASSET)!!.use { it.readBytes().toString(Charsets.US_ASCII) }
            val key = assets.open(TLS_KEY_ASSET)!!.use { it.readBytes().toString(Charsets.US_ASCII) }
            return SelfSignedCert.fromPem(cert, key)
        }
        return SelfSignedCert.load(tlsKeystore, localAddresses())
    }

    private fun startClip() {
        if (assets.exists("clips/$TEST_CLIP")) {
            clip = ClipSource(assets, "clips/$TEST_CLIP", videoHub).also { it.start() }
            event("테스트 클립 송출: $TEST_CLIP")
        } else {
            event("테스트 클립 없음 (assets/clips/$TEST_CLIP)")
        }
    }

    fun stop() {
        if (!running) return
        if (liveSourceRunning) { runCatching { videoSource?.stop() }; liveSourceRunning = false }
        clip?.stop(); clip = null
        https?.stop(); https = null
        videoHub.closeAll()
        audioHub.closeAll()
        for (c in controlClients) c.close()
        controlClients.clear()
        http?.stop(); http = null
        running = false
        event("세션 종료")
    }

    private fun onWebSocket(path: String, query: Map<String, String>, conn: WebSocketConnection): Boolean {
        return when (path) {
            "/ws/video" -> { videoHub.attach(conn); event("video 클라이언트 접속 (${videoHub.clientCount})"); true }
            "/ws/audio" -> { audioHub.attach(conn); true }
            "/ws/control" -> {
                controlClients += conn
                conn.listener = object : WebSocketConnection.Listener {
                    override fun onBinary(conn: WebSocketConnection, data: ByteArray) { onControl(conn, data) }
                    override fun onText(conn: WebSocketConnection, text: String) {}
                    override fun onClose(conn: WebSocketConnection) {
                        controlClients.remove(conn)
                        if (controlClients.isEmpty()) {
                            event("control 클라이언트 끊김 — 눌린 채 남은 터치를 취소")
                            runCatching { onControlGone() }.onFailure { Log.w(TAG, "onControlGone failed: $it") }
                        }
                    }
                }
                conn.sendText(statusJson())
                true
            }
            else -> false
        }
    }

    private fun onApi(method: String, path: String, query: Map<String, String>, body: ByteArray, remote: String): String? {
        extraApi?.invoke(method, path, query)?.let { return it }
        return onCoreApi(method, path, query, body, remote)
    }

    private fun onCoreApi(method: String, path: String, query: Map<String, String>, body: ByteArray, remote: String): String? = when {
        path == "/api/status" -> statusJson()
        path == "/api/reports" && method == "GET" -> reports.listJson(query["limit"]?.toIntOrNull() ?: ReportStore.MAX)
        path == "/api/app" && method == "POST" -> {
            val name = query["name"].orEmpty()
            val restart = query["restart"]?.takeIf { it in RESTART_MODES } ?: DEFAULT_RESTART
            val handler = onStartApp
            when {
                handler == null -> Json.obj(mapOf("ok" to false, "error" to "no display source"))
                !Regex("[A-Za-z0-9._/$]+").matches(name) -> Json.obj(mapOf("ok" to false, "error" to "bad app name"))
                else -> runCatching { handler(name, restart) }.fold(
                    { Json.obj(linkedMapOf<String, Any?>("ok" to true).apply { putAll(it) }) },
                    { Json.obj(mapOf("ok" to false, "error" to (it.message ?: it.toString()))) },
                )
            }
        }
        path == "/api/log" && method == "GET" -> Json.array(Log.recentLines().takeLast(query["limit"]?.toIntOrNull() ?: Log.RECENT_MAX))
        path == "/api/stop" && method == "POST" -> {
            if (!remote.startsWith("127.")) Json.obj(mapOf("ok" to false, "error" to "loopback only"))
            else { event("종료 요청 ($remote)"); Thread({ Thread.sleep(200); onStopRequest() }, "stop").apply { isDaemon = true }.start(); Json.obj(mapOf("ok" to true)) }
        }
        path == "/api/report" && method == "POST" -> {
            val r = reports.add(String(body, Charsets.UTF_8), remote)
            if (r == null) Json.obj(mapOf("ok" to false, "error" to "body is not a JSON object"))
            else {
                event("진단 결과 #${r.id} 수신 ($remote): ${r.summary}")
                Json.obj(mapOf("ok" to true, "id" to r.id, "receivedAt" to r.receivedAt, "stored" to reports.size))
            }
        }
        else -> null
    }

    @Volatile var controlErrors = 0L
        private set

    /** Keyframes asked for by the car and actually passed on to the source (the rest fell inside the rate limit). */
    @Volatile var keyframeRequests = 0L
        private set
    @Volatile private var lastKeyframeRequestAt = 0L

    /**
     * Ask the source for an IDR. Two callers: the car (it dropped frames or its decoder stalled) and the hub
     * (it dropped frames that had gone stale in front of a socket). Both mean the same thing — somebody is
     * waiting for the next keyframe and the GOP is ten seconds — and both can repeat, so the rate limit is
     * shared: a car in trouble must not be able to turn the stream into all keyframes.
     *
     * No lock: the hub calls this from the encoder's output thread, which is holding EncodedH264Sink's
     * monitor, and a lock taken in that order is a lock ordering to maintain forever. Two callers slipping
     * through the gap at once cost one extra IDR, which is what the gap is there to bound anyway.
     */
    private fun requestKeyframe() {
        val now = System.currentTimeMillis()
        if (now - lastKeyframeRequestAt < KEYFRAME_REQUEST_MIN_GAP_MS) return
        lastKeyframeRequestAt = now
        keyframeRequests++
        if (liveSourceRunning) videoSource?.requestKeyframe()
    }

    private fun onControl(conn: WebSocketConnection, data: ByteArray) {
        if (data.isEmpty()) return
        controlPackets++
        if (controlPackets % 200 == 1L) event("control 패킷 ${controlPackets}개 (kind=${data[0]})")
        val msg = ControlMessage.parse(data) ?: run { controlErrors++; return }
        when (msg) {
            // The car's round-trip probe: back the way it came, untouched. Never reaches the injector.
            is ControlMessage.Ping -> { conn.offer(data); return }
            // The car dropped frames or its decoder stalled: an IDR now beats waiting out the GOP or reconnecting.
            // Rate-limited so a car in trouble cannot turn the stream into all keyframes.
            is ControlMessage.Keyframe -> { requestKeyframe(); return }
            else -> {}
        }
        val handler = controlHandler ?: return
        try { handler(msg) } catch (e: Exception) {
            controlErrors++
            if (controlErrors % 100 == 1L) Log.w(TAG, "control inject failed: $e")
        }
    }

    fun statusJson(): String {
        val fields = linkedMapOf<String, Any?>(
            "type" to "status",
            "running" to running,
            "process" to process,
            "port" to port,
            // What the car has to open to be in a secure context, and the certificate it will be asked to
            // accept. Absent means no TLS listener — and then a "WebCodecs X" in a report means nothing.
            "httpsPort" to (httpsPort.takeIf { https != null } ?: 0),
            "tlsFingerprint" to tlsFingerprint,
            "tlsSubject" to tlsSubject,
            // True when a public CA signed it: then the car opens tlsHost with no interstitial at all, which
            // matters because this Model Y's interstitial has no way through.
            "tlsTrusted" to tlsTrusted,
            "tlsHost" to tlsHost,
            "videoClients" to videoHub.clientCount,
            "videoClientStats" to videoHub.clientStats(),
            "controlClients" to controlClients.size,
            "controlPackets" to controlPackets,
            "controlErrors" to controlErrors,
            "keyframeRequests" to keyframeRequests,
            "input" to (controlHandler != null),
            "source" to if (clip != null) "clip" else if (liveSourceRunning) "display" else "none",
            "width" to 1280,
            "height" to 720,
            "addresses" to localAddresses(),
            // 받은 연결 수·accept 오류·마지막 연결이 언제였나. 차에서 "죽었다"고 할 때 폰까지
            // 닿기는 했는지를 가르는 유일한 증거다(끊긴 링크와 멎은 서버는 브라우저에서 똑같아 보인다).
            "accepts" to (http?.accepts ?: 0L),
            "acceptErrors" to (http?.acceptErrors ?: 0L),
            "accepting" to (http?.accepting ?: false),
            "lastAcceptAgoMs" to http?.lastAcceptAt?.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
            "videoDropped" to videoHub.dropped,
            "videoStaleDropped" to videoHub.staleDropped,
            "reports" to reports.size,
            "lastReport" to reports.last?.let { mapOf("id" to it.id, "receivedAt" to it.receivedAt, "remote" to it.remote, "summary" to it.summary) },
        )
        if (liveSourceRunning) videoSource?.let { fields.putAll(it.info()) }
        fields.putAll(extraStatus())
        return Json.obj(fields)
    }

    /**
     * Non-loopback IPv4 addresses of this host, "iface=addr". The diag page uses them as the control
     * group: the car must NOT be able to open http://<hotspot address>:port, only the tun address.
     * Empty when the process may not enumerate interfaces (app uid under SELinux).
     */
    private fun localAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { "${ni.name}=${it.hostAddress}" } }
    } catch (_: Exception) { emptyList() }

    companion object {
        private const val TAG = "StreamSession"
        /** 같은 곳에서 계속 들어오는 accept 는 이 간격으로만 한 줄 남긴다(로그가 밀려나지 않게). */
        private const val ACCEPT_LOG_EVERY = 100L
        const val TEST_CLIP = "test-720p30.cmp4"
        /** A trusted certificate bundled with the build (not in git — see tools/tls/README.md). */
        const val TLS_CERT_ASSET = "tls/cert.pem"
        const val TLS_KEY_ASSET = "tls/key.pem"
        /** Two keyframe requests closer than this collapse into one; an IDR is dozens of P-frames' worth of bytes. */
        const val KEYFRAME_REQUEST_MIN_GAP_MS = 500L
        /** Accepted values of `/api/app`'s `restart` query parameter; anything else falls back to [DEFAULT_RESTART]. */
        val RESTART_MODES = setOf("auto", "always", "never")
        /**
         * `never`: bring the app over as it is. The car is a second screen for the phone, and what the driver
         * wants is the video they were watching, mid-play, not the app's front page (`auto` used to be the
         * default and threw that state away by force-stopping the app; the ping-pong it was meant to hide is
         * reported by the app watcher instead, see `appOnPhone`).
         */
        const val DEFAULT_RESTART = "never"
    }
}

/** Just enough JSON to write flat status objects without pulling a library into the shell process. */
object Json {
    /** Pre-serialized JSON to embed verbatim (validate with [isObject] first). */
    class Raw(val json: String)

    fun obj(fields: Map<String, Any?>): String = fields.entries.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }
    fun array(items: Iterable<Any?>): String = items.joinToString(",", "[", "]") { value(it) }

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean, is Number -> v.toString()
        is Raw -> v.json
        is Map<*, *> -> obj(v.entries.associate { it.key.toString() to it.value })
        is Iterable<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }

    /**
     * Structural check that [s] is one complete JSON object: balanced braces/brackets outside strings,
     * nothing after the closing brace. Enough to embed untrusted client text into our own output
     * without a parser; it does not validate scalars.
     */
    fun isObject(s: String): Boolean {
        if (!s.startsWith("{")) return false
        var depth = 0
        var inString = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                    '\n', '\r' -> return false
                }
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> { depth--; if (depth == 0) return i == s.length - 1; if (depth < 0) return false }
            }
            i++
        }
        return false
    }

    /** Reverses [str] for the simple escapes it produces (and \uXXXX). */
    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val e = s[i + 1]) {
                    'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                    'u' -> if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 } else sb.append(e)
                    else -> sb.append(e)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }
}
