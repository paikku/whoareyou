package com.carcast.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The front page's lights: what "done" means for each, and when the note under step 3 has anything to say. */
class SetupStepsTest {

    private fun state(
        wireless: Boolean = false, paired: Boolean = false, server: Boolean = false, widget: Boolean = false,
        granted: Boolean = false, usb: Boolean = false, tcp: Boolean = false, byApp: Boolean = false,
    ) = SetupSteps.State(wireless, byApp, paired, server, widget, granted, usb, tcp)

    /** After a reboot the session is waiting on Wi-Fi; that line beats the three lights. */
    @Test
    fun aBlockerReplacesTheLights() {
        assertEquals("⏳ Wi-Fi에 연결하세요", SetupSteps.note(state(granted = true, usb = true), blocker = "Wi-Fi에 연결하세요"))
    }

    @Test
    fun nothingToNoteBeforeTheFirstSessionHasDoneAnything() {
        assertNull(SetupSteps.note(state()))
    }

    @Test
    fun theNoteLightsEachAutomaticThingAndNamesTheMissingOne() {
        assertEquals("● 권한  ● USB 디버깅  ● TCP 모드", SetupSteps.note(state(granted = true, usb = true, tcp = true)))
        assertEquals("● 권한  ○ USB 디버깅  ○ TCP 모드", SetupSteps.note(state(granted = true)))
    }

    @Test
    fun allDoneNeedsTheFourStepsNotTheNote() {
        assertTrue(state(wireless = true, paired = true, server = true, widget = true).allDone)
        assertFalse(state(wireless = true, paired = true, server = true, widget = false).allDone)
        assertTrue(state(wireless = true, paired = true, server = true, widget = true, granted = false).allDone)
    }
}
