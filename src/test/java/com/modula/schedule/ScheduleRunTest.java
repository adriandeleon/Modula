package com.modula.schedule;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import com.modula.schedule.ScheduleRun.Change;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleRunTest {

    private static final Recording MORNING = slot("morning");
    private static final Recording NEWS = slot("news");

    private static Recording slot(String id) {
        return new Recording(
                id,
                id,
                98_900_000L,
                "FM",
                LocalTime.of(9, 0),
                Duration.ofMinutes(30),
                Set.of(),
                LocalDate.of(2026, 11, 6),
                true);
    }

    /** The bug: a schedule that switched the receiver on left it playing when the slot ended. */
    @Test
    void aSlotThatSwitchedTheReceiverOnSwitchesItOff() {
        ScheduleRun run = new ScheduleRun();

        assertEquals(Change.BEGIN, run.tick(MORNING));
        run.poweredReceiverOn();
        assertEquals(Change.NONE, run.tick(MORNING));

        assertEquals(Change.END_AND_POWER_OFF, run.tick(null));
    }

    /** Someone already listening keeps listening: the schedule only borrowed the receiver. */
    @Test
    void aSlotThatFoundTheReceiverOnLeavesItOn() {
        ScheduleRun run = new ScheduleRun();

        assertEquals(Change.BEGIN, run.tick(MORNING));
        assertEquals(Change.END, run.tick(null));
    }

    /** Pressing Listen or Stop during the slot makes the receiver the listener's. */
    @Test
    void theListenersOwnHandWins() {
        ScheduleRun run = new ScheduleRun();
        run.tick(MORNING);
        run.poweredReceiverOn();

        run.listenerTookOver();

        assertEquals(Change.END, run.tick(null));
    }

    /** The second of two back-to-back slots found the receiver on only because the first switched it on. */
    @Test
    void backToBackSlotsPassOwnershipAlong() {
        ScheduleRun run = new ScheduleRun();
        run.tick(MORNING);
        run.poweredReceiverOn();

        assertEquals(Change.BEGIN, run.tick(NEWS), "a different recording taking over is a new start");
        assertEquals(Change.END_AND_POWER_OFF, run.tick(null));
    }

    /** Ownership ends with the slot, so a later slot that finds the receiver on leaves it on. */
    @Test
    void ownershipDoesNotOutliveItsSlot() {
        ScheduleRun run = new ScheduleRun();
        run.tick(MORNING);
        run.poweredReceiverOn();
        run.tick(null);

        assertFalse(run.isActive());
        assertEquals(Change.BEGIN, run.tick(NEWS));
        assertTrue(run.isActive());
        assertEquals(Change.END, run.tick(null));
        assertEquals(Change.NONE, run.tick(null), "an end is reported once");
    }
}
