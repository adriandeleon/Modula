package com.modula.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the two grounds really get different control themes.
 *
 * <p>A small guard for a failure that is invisible in a diff and obvious on screen: both branches
 * pointing at the same theme, which is effectively what shipped — the user-agent stylesheet was set once
 * to the dark theme and never changed, so daylight was light tokens over dark controls.
 *
 * <p>The choice is deliberately separated from {@link Themes#apply} so it can be checked without a
 * toolkit, which is the same rule the DSP follows.
 */
class ThemesTest {

    @Test
    void eachGroundHasItsOwnControlTheme() {
        String night = Themes.stylesheetFor(false);
        String daylight = Themes.stylesheetFor(true);

        assertNotEquals(night, daylight, "both grounds resolved to the same control theme");
    }

    @Test
    void bothResolveToARealStylesheet() {
        for (boolean daylight : new boolean[] {false, true}) {
            String sheet = Themes.stylesheetFor(daylight);
            assertFalse(sheet == null || sheet.isBlank(), "no stylesheet for daylight=" + daylight);
            assertTrue(sheet.endsWith(".css"), "not a stylesheet: " + sheet);
        }
    }

    /** Named so a reader can tell which way round it is without running the application. */
    @Test
    void daylightIsTheLightOne() {
        assertTrue(Themes.stylesheetFor(true).contains("light"), Themes.stylesheetFor(true));
        assertTrue(Themes.stylesheetFor(false).contains("dark"), Themes.stylesheetFor(false));
    }
}
