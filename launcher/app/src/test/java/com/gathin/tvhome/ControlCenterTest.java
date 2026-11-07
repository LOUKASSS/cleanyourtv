package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ControlCenterTest {
    @Test
    public void theTimerCyclesOffThroughNinetyMinutesAndBack() {
        assertEquals(15, ControlCenter.nextTimer(0));
        assertEquals(30, ControlCenter.nextTimer(15));
        assertEquals(60, ControlCenter.nextTimer(30));
        assertEquals(90, ControlCenter.nextTimer(60));
        assertEquals(0, ControlCenter.nextTimer(90));
        // A saved value that is no step (an older build, a hand-edited preference) turns the timer off.
        assertEquals(0, ControlCenter.nextTimer(45));
    }

    @Test
    public void minutesLeftRoundUpAndStopAtTheEnd() {
        long now = 1_000_000L;
        assertEquals(0, ControlCenter.minutesLeft(0, now));
        assertEquals(0, ControlCenter.minutesLeft(now, now));
        assertEquals(0, ControlCenter.minutesLeft(now - 5_000, now));
        assertEquals(1, ControlCenter.minutesLeft(now + 1, now));
        assertEquals(1, ControlCenter.minutesLeft(now + 60_000, now));
        assertEquals(2, ControlCenter.minutesLeft(now + 60_001, now));
        assertEquals(90, ControlCenter.minutesLeft(now + 90 * 60_000L, now));
    }

    @Test
    public void hoursAndMinutesSplitTheTimeLeft() {
        assertArrayEquals(new int[]{0, 45}, ControlCenter.hoursMinutes(45));
        assertArrayEquals(new int[]{1, 5}, ControlCenter.hoursMinutes(65));
        assertArrayEquals(new int[]{1, 30}, ControlCenter.hoursMinutes(90));
        assertArrayEquals(new int[]{0, 0}, ControlCenter.hoursMinutes(-3));
    }

    @Test
    public void standbyAndTheTimerNeedABoundHelperOnAndroidNine() {
        assertTrue(ControlCenter.canSleep(true, 34));
        assertTrue(ControlCenter.canSleep(true, 28));
        assertFalse(ControlCenter.canSleep(true, 27));
        assertFalse(ControlCenter.canSleep(false, 34));
        assertTrue(ControlCenter.timerOffered(true, 34));
        assertFalse(ControlCenter.timerOffered(false, 34));
        assertTrue(ControlCenter.settingsOffered(true));
        assertFalse(ControlCenter.settingsOffered(false));
    }

    @Test
    public void anInputRowSplitsIntoPortAndName() {
        assertArrayEquals(new String[]{"HDMI 1", "Freebox Pop"}, ControlCenter.inputLines("Freebox Pop · HDMI 1"));
        assertArrayEquals(new String[]{"HDMI 2", ""}, ControlCenter.inputLines("HDMI 2"));
        // A name with a dot of its own keeps it; the port is after the last one.
        assertArrayEquals(new String[]{"HDMI 3", "A · B"}, ControlCenter.inputLines("A · B · HDMI 3"));
        assertArrayEquals(new String[]{"", ""}, ControlCenter.inputLines(null));
    }

    @Test
    public void thePanelSitsInsideTheScreen() {
        assertTrue(ControlCenter.PANEL_TOP + ControlCenter.PANEL_MAX_H <= 540 - ControlCenter.PANEL_TOP);
        // Two tiles and their gap fill the panel's width less 16dp of padding on each side.
        assertEquals(ControlCenter.PANEL_W - 2 * 16, 2 * ControlCenter.TILE_W + ControlCenter.GAP);
    }
}
