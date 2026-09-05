package com.carcast.core.media

/**
 * Where the video comes from: the bundled test clip ([ClipSource]) or, in the shell process, the
 * virtual display encoder. The session owns the lifecycle; the source pushes packets into the hub.
 */
interface VideoSource {
    fun start(hub: MediaHub)
    fun stop()

    /** A new client attached: emit an IDR soon so it gets a picture without waiting for the GOP. */
    fun requestKeyframe() {}

    /** Fields merged into /api/status: at least "source", "width", "height". */
    fun info(): Map<String, Any?>
}
