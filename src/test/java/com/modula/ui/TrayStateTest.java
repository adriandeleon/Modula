package com.modula.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrayStateTest {

    /** The bug: unticking the tray got the advice meant for a desktop that has none. */
    @Test
    void aTrayTurnedOffIsNotReportedAsMissing() {
        TrayState off = TrayState.atLaunch(false);

        assertEquals(TrayState.OFF, off);
        assertFalse(off.note().contains("No system tray"), off.note());
        assertFalse(off.isProblem(), "a choice the listener made is not a fault");
        assertTrue(off.allowsCloseToTray(), "both boxes can still be set together for next launch");
    }

    @Test
    void aDesktopWithNoTraySaysSo() {
        TrayState none = TrayState.UNAVAILABLE;

        assertTrue(none.note().contains("No system tray"), none.note());
        assertTrue(none.isProblem());
        assertFalse(none.allowsCloseToTray());
    }

    /** Asked for and not yet answered is not a missing tray either. */
    @Test
    void aTrayStillStartingIsNotMissing() {
        TrayState starting = TrayState.atLaunch(true);

        assertEquals(TrayState.STARTING, starting);
        assertFalse(starting.isProblem());
        assertFalse(starting.note().contains("No system tray"));
    }
}
