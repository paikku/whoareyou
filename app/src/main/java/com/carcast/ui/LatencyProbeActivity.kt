package com.carcast.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

/**
 * The glass-to-glass latency target. The car starts this on the virtual display
 * (`POST /api/app?name=com.carcast/.ui.LatencyProbeActivity`), then taps: every DOWN flips the whole
 * screen between black and white. The car's decoder watches the centre of the picture and measures the
 * time from sending the touch to seeing the flip — touch link, phone render, encode, network, decode,
 * all of it, with no camera and no laptop.
 *
 * The centre stays a flat colour on purpose (the caption sits at the bottom): the decoder reads the mean
 * luma of a small block there, and text through it would blur the edge.
 *
 * Its own task (`taskAffinity` in the manifest): started on the car display it must not drag the app's
 * main task — the screen the driver set things up on — off the phone.
 */
class LatencyProbeActivity : Activity() {
    private var white = false
    private var flips = 0
    private lateinit var root: FrameLayout
    private lateinit var caption: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this)
        caption = TextView(this).apply {
            textSize = 16f
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(caption, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { white = !white; flips++; paint() }
            true
        }
        setContentView(root)
        paint()
        // No system bars over the picture: they would sit in the encoder's frame too.
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun paint() {
        root.setBackgroundColor(if (white) Color.WHITE else Color.BLACK)
        caption.setTextColor(if (white) Color.DKGRAY else Color.LTGRAY)
        caption.text = "CarCast 지연 측정 — 차에서 재는 중 (${flips}번 바뀜). 끝나면 차의 ◀ 로 나갑니다."
    }
}
