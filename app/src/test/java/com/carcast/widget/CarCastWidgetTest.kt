package com.carcast.widget

import com.carcast.adb.UsbDebugging
import com.carcast.service.BulkControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two decisions the home screen switch makes on its own. Both look obvious and are wrong in one
 * direction: refusing to switch *off* because VPN consent is missing would strand the hotspot, and
 * showing the switch as on because a session exists would hide the failure this project spends most of
 * its time on — a session whose server is not answering.
 */
class CarCastWidgetTest {

    @Test
    fun offNeverWaitsForConsentAndOnNeverStartsWithoutIt() {
        assertEquals(CarCastWidget.Action.ALL_OFF, CarCastWidget.decide(checked = false, consentGiven = false))
        assertEquals(CarCastWidget.Action.ALL_OFF, CarCastWidget.decide(checked = false, consentGiven = true))
        assertEquals(CarCastWidget.Action.ALL_ON, CarCastWidget.decide(checked = true, consentGiven = true))
        assertEquals(CarCastWidget.Action.ASK_CONSENT, CarCastWidget.decide(checked = true, consentGiven = false))
    }

    @Test
    fun theSwitchIsOnOnlyWhenBothTheSessionAndTheServerAre() {
        val idle = BulkControl.Phase.IDLE
        assertTrue(CarCastWidget.checkedFor(idle, session = true, server = true))
        assertFalse("a session whose server is not answering is not 'on'", CarCastWidget.checkedFor(idle, session = true, server = false))
        assertFalse(CarCastWidget.checkedFor(idle, session = false, server = true))
        assertFalse(CarCastWidget.checkedFor(idle, session = false, server = false))
    }

    /** Mid-sequence the switch shows where it is going, or it snaps back under the user's finger. */
    @Test
    fun whileASequenceRunsTheSwitchShowsItsDestination() {
        assertTrue(CarCastWidget.checkedFor(BulkControl.Phase.TURNING_ON, session = false, server = false))
        assertFalse(CarCastWidget.checkedFor(BulkControl.Phase.TURNING_OFF, session = true, server = true))
    }

    /**
     * The line under the switch. A sequence shows its own step (so a thirty-second wait is visibly a wait),
     * and idle with the server down shows what the link is stuck on — the widget is what the driver looks
     * at, and "session on, server gone" behind a single dot is the failure that goes unnoticed longest.
     */
    @Test
    fun theDetailLineShowsTheStepWhileRunningAndTheBlockerWhenIdle() {
        val idle = BulkControl.Phase.IDLE
        val line = "VPN ● 서버 ●"
        assertEquals("3/3 서버 응답 대기 12/30초", CarCastWidget.detailFor(BulkControl.Phase.TURNING_ON, "3/3 서버 응답 대기 12/30초", false, false, line, null, null, null))
        assertEquals("진행 중…", CarCastWidget.detailFor(BulkControl.Phase.TURNING_OFF, "", true, true, line, null, null, null))
        assertEquals(line, CarCastWidget.detailFor(idle, "", session = true, server = true, idleLine = line, link = "무선 디버깅 꺼짐", failure = null, usb = null))
        assertEquals("서버 없음 — 무선 디버깅 꺼짐", CarCastWidget.detailFor(idle, "", session = true, server = false, idleLine = line, link = "무선 디버깅 꺼짐", failure = null, usb = null))
        assertEquals("⚠ 3/3 서버: 30초 안에 응답 없음", CarCastWidget.detailFor(idle, "", session = false, server = false, idleLine = line, link = null, failure = "3/3 서버: 30초 안에 응답 없음", usb = null))
    }

    /** The USB debugging toggle is mentioned only when the app could not switch it on — then it is the driver's to do. */
    @Test
    fun theUsbToggleIsMentionedOnlyWhenTheAppCouldNotSwitchItOn() {
        val idle = BulkControl.Phase.IDLE
        val line = "VPN ○ 서버 ○"
        assertEquals(line, CarCastWidget.detailFor(idle, "", false, false, line, null, null, UsbDebugging.Outcome.TURNED_ON))
        assertEquals(line, CarCastWidget.detailFor(idle, "", false, false, line, null, null, UsbDebugging.Outcome.ALREADY_ON))
        assertEquals("$line · USB 디버깅을 켜 주세요", CarCastWidget.detailFor(idle, "", false, false, line, null, null, UsbDebugging.Outcome.NO_PERMISSION))
        assertEquals("서버 없음 — Wi-Fi 없음 (TCP 모드도 없음) · USB 디버깅을 켜 주세요", CarCastWidget.detailFor(idle, "", true, false, line, "Wi-Fi 없음 (TCP 모드도 없음)", null, UsbDebugging.Outcome.REFUSED))
    }
}
