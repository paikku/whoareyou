package com.carcast.core.media

/**
 * Where the audio comes from: the bundled test tone ([ClipSource] on the audio hub) or, in the
 * shell process, the phone's audio output captured with REMOTE_SUBMIX and encoded to AAC.
 * Like [VideoSource] the session owns the lifecycle; a failure here must never take video down.
 */
interface AudioSource {
    fun start(hub: MediaHub)
    fun stop()

    /** Fields merged into /api/status under "audio". */
    fun info(): Map<String, Any?>
}
