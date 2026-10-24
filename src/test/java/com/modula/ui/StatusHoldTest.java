package com.modula.ui;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusHoldTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void nothingIsHeldUntilAMessageIsShown() {
        assertFalse(new StatusHold().isHeld(0L));
        assertFalse(new StatusHold().isHeld(Long.MIN_VALUE + 1), "the sentinel must not read as a deadline");
    }

    /** The failure this exists for: the readout arriving ~110 ms after a message and replacing it. */
    @Test
    void theNextReadoutDoesNotReplaceAMessage() {
        StatusHold hold = new StatusHold();
        hold.hold(Duration.ofSeconds(4), 0L);

        assertTrue(hold.isHeld(110_000_000L), "a readout 110 ms later must wait");
        assertTrue(hold.isHeld(4 * SECOND - 1));
        assertFalse(hold.isHeld(4 * SECOND), "and take the line back once the message has been read");
    }

    @Test
    void aNewMessageRestartsTheHold() {
        StatusHold hold = new StatusHold();
        hold.hold(Duration.ofSeconds(4), 0L);
        hold.hold(Duration.ofSeconds(4), 3 * SECOND);

        assertTrue(hold.isHeld(6 * SECOND));
        assertFalse(hold.isHeld(7 * SECOND));
    }

    /** System.nanoTime has an arbitrary origin and may be negative or near the top of the range. */
    @Test
    void survivesAClockOriginAnywhere() {
        StatusHold hold = new StatusHold();
        long near = Long.MAX_VALUE - SECOND;
        hold.hold(Duration.ofSeconds(4), near);

        assertTrue(hold.isHeld(near + SECOND), "a deadline that overflows past MAX_VALUE still holds");
        assertFalse(hold.isHeld(near + 5 * SECOND));
    }

    @Test
    void longerMessagesAreHeldLongerWithinBounds() {
        assertEquals(StatusHold.MINIMUM, StatusHold.readingTime("Stopped."));
        assertEquals(StatusHold.MINIMUM, StatusHold.readingTime(null));

        // The Windows driver advice, about 170 characters: needs more than the minimum to be read.
        String advice = "x".repeat(170);
        Duration time = StatusHold.readingTime(advice);
        assertTrue(time.compareTo(StatusHold.MINIMUM) > 0 && time.compareTo(StatusHold.MAXIMUM) < 0, time.toString());

        assertEquals(StatusHold.MAXIMUM, StatusHold.readingTime("x".repeat(5_000)));
    }
}
