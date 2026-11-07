package com.gathin.tvhome;

/**
 * appletv_control_center's pure logic, free of Android calls for the JVM tests: the sleep timer's steps, the time
 * left on its tile, which tiles can do something on this TV, and how an input row's name splits into its two lines.
 * Sizes are dp on the 960x540 dp screen (the mockup's 1920x1080 pixels halved).
 */
final class ControlCenter {
    private ControlCenter() {
    }

    /** The panel: right edge 40dp in, 26dp from the top, 340dp wide, at most 488dp tall, 28dp corners. */
    static final int PANEL_W = 340, PANEL_RIGHT = 40, PANEL_TOP = 26, PANEL_MAX_H = 488, PANEL_RADIUS = 28;
    /** A tile: 149x56dp, 16dp corners, a 29dp icon disc; tiles 10dp apart. */
    static final int TILE_W = 149, TILE_H = 56, TILE_RADIUS = 16, TILE_ICON = 29, GAP = 10;
    /** The slide in from the right, and how bright the blurred snapshot of the screen behind is kept. */
    static final int SLIDE_MS = 220;
    static final float SNAPSHOT_BRIGHTNESS = 0.7f;

    /** The sleep timer's steps, in minutes; 0 is off. Each press of OK moves to the next, and after the last, off. */
    static final int[] TIMER_STEPS = {0, 15, 30, 60, 90};

    /** The step after chosen (minutes): off goes to 15, 90 back to off, a value that is no step to off. */
    static int nextTimer(int chosen) {
        for (int i = 0; i < TIMER_STEPS.length; i++) {
            if (TIMER_STEPS[i] == chosen) return TIMER_STEPS[(i + 1) % TIMER_STEPS.length];
        }
        return 0;
    }

    /**
     * Whole minutes left before the timer ends at endMs, rounded up so the tile never reads 0 while it runs:
     * 0 when it is off (endMs 0) or already over.
     */
    static int minutesLeft(long endMs, long nowMs) {
        if (endMs <= 0 || endMs <= nowMs) return 0;
        return (int) ((endMs - nowMs + 59_999) / 60_000);
    }

    /** "1 h 05" style split of minutes for the tile: {hours, minutes}. */
    static int[] hoursMinutes(int minutes) {
        int m = Math.max(0, minutes);
        return new int[]{m / 60, m % 60};
    }

    /**
     * Standby works through an accessibility helper of this launcher (the "lock screen" global action, Android 9 and
     * later): an ordinary app cannot put a TV to sleep any other way.
     */
    static boolean canSleep(boolean helperBound, int sdk) {
        return helperBound && sdk >= 28;
    }

    /** The timer ends in standby, so it is offered only where standby works. */
    static boolean timerOffered(boolean helperBound, int sdk) {
        return canSleep(helperBound, sdk);
    }

    /** A settings tile is offered only when its screen opens on this TV. */
    static boolean settingsOffered(boolean resolves) {
        return resolves;
    }

    /**
     * An input row's two parts, as the Inputs menu writes it ("Freebox · HDMI 1"): {port, the device's name}, the
     * port first as the panel shows it; a row with no " · " is all port and an empty name.
     */
    static String[] inputLines(String label) {
        if (label == null) return new String[]{"", ""};
        int dot = label.lastIndexOf(" · ");
        if (dot < 0) return new String[]{label, ""};
        return new String[]{label.substring(dot + 3), label.substring(0, dot)};
    }
}
