package com.modula.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StyleClassesTest {

    /** The station label's case: an hour of statuses with no name, then RDS supplies one. */
    @Test
    void anHourOfStatusesLeavesOneCopyAndOneRemoveClearsIt() {
        List<String> classes = new ArrayList<>(List.of("station"));
        for (int status = 0; status < 9 * 3600; status++) {
            StyleClasses.set(classes, "station-pending", true);
        }
        assertEquals(1, Collections.frequency(classes, "station-pending"), "the list grew with every status");

        StyleClasses.set(classes, "station-pending", false);
        assertFalse(classes.contains("station-pending"), "the name stayed faint after it arrived");
        assertEquals(List.of("station"), classes, "other classes are left alone");
    }

    /** A list that already holds duplicates — from before this existed — is still cleared. */
    @Test
    void offRemovesEveryCopy() {
        List<String> classes = new ArrayList<>(List.of("lit", "badge", "lit"));
        StyleClasses.set(classes, "lit", false);
        assertEquals(List.of("badge"), classes);
    }
}
