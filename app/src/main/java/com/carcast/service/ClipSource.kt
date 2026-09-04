package com.carcast.service

import android.content.res.AssetManager
import android.os.SystemClock
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException

/**
 * Plays a pre-muxed test clip (a .cmp4 file under tools/clips, produced by the :mux CLI) in a loop.
 * Lets the car-side pipeline be validated with the real APK before the shell server exists (M4).
 *
 * File format: repeated records of [u8 type][u64 pts_us][u32 len][payload]; type as in MediaHub.
 */
class ClipSource(private val assets: AssetManager, private val name: String, private val hub: MediaHub) {
    private var thread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "clip-source").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Fragments carry their original timestamp in `tfdt`; when looping, rewrite it so the timeline keeps increasing. */
    private fun restampTfdt(fragment: ByteArray, ptsUs: Long) {
        val limit = minOf(fragment.size - 12, 256)
        for (i in 0 until limit) {
            if (fragment[i] == 't'.code.toByte() && fragment[i + 1] == 'f'.code.toByte() &&
                fragment[i + 2] == 'd'.code.toByte() && fragment[i + 3] == 't'.code.toByte() && fragment[i + 4].toInt() == 1
            ) {
                for (b in 0 until 8) fragment[i + 8 + b] = (ptsUs ushr (8 * (7 - b))).toByte()
                return
            }
        }
    }

    private fun loop() {
        var ptsBase = 0L
        while (running) {
            var lastPts = 0L
            try {
                DataInputStream(assets.open(name).buffered(1 shl 16)).use { input ->
                    val t0 = SystemClock.elapsedRealtimeNanos() / 1000
                    while (running) {
                        val type = input.read()
                        if (type < 0) break
                        val pts = input.readLong()
                        val len = input.readInt()
                        val payload = ByteArray(len)
                        input.readFully(payload)
                        val outPts = ptsBase + pts
                        if (type.toByte() == MediaHub.TYPE_INIT) {
                            if (ptsBase == 0L) hub.onInit(MediaHub.packet(MediaHub.TYPE_INIT, outPts, payload))
                            continue
                        }
                        val due = t0 + pts
                        val now = SystemClock.elapsedRealtimeNanos() / 1000
                        if (due > now) Thread.sleep((due - now) / 1000, (((due - now) % 1000) * 1000).toInt())
                        if (ptsBase != 0L) restampTfdt(payload, outPts)
                        hub.onFrame(MediaHub.packet(type.toByte(), outPts, payload), type.toByte() == MediaHub.TYPE_KEY)
                        lastPts = pts
                    }
                }
            } catch (_: EOFException) {
            } catch (_: InterruptedException) {
                return
            } catch (e: IOException) {
                return
            }
            // Loop seamlessly: continue timestamps after the last frame.
            ptsBase += lastPts + 33_333
        }
    }
}
