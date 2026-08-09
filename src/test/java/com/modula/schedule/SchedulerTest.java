package com.modula.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);

    private static Recording weekly(String id, LocalTime start, int minutes, DayOfWeek... days) {
        return new Recording(id, id, 98_900_000L, "FM", start, Duration.ofMinutes(minutes), Set.of(days), null, true);
    }

    private static Recording once(String id, LocalDate date, LocalTime start, int minutes) {
        return new Recording(id, id, 98_900_000L, "FM", start, Duration.ofMinutes(minutes), Set.of(), date, true);
    }

    @Test
    void aWeeklyRecordingIsActiveOnlyWithinItsWindow() {
        Recording r = weekly("show", LocalTime.of(9, 0), 60, DayOfWeek.MONDAY);
        assertFalse(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(8, 59))));
        assertTrue(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(9, 0))), "the first instant counts");
        assertTrue(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(9, 59))));
        assertFalse(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(10, 0))), "the end is exclusive");
        assertFalse(r.isActiveAt(LocalDateTime.of(MONDAY.plusDays(1), LocalTime.of(9, 30))), "wrong day");
    }

    /**
     * The case a naive implementation gets wrong: an occurrence that began yesterday is still running
     * on a date its own start never mentions.
     */
    @Test
    void anOccurrenceCanCrossMidnight() {
        Recording r = weekly("late", LocalTime.of(23, 30), 60, DayOfWeek.MONDAY);
        assertTrue(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(23, 45))));
        assertTrue(
                r.isActiveAt(LocalDateTime.of(MONDAY.plusDays(1), LocalTime.of(0, 15))),
                "still running into Tuesday, though it repeats on Monday");
        assertFalse(r.isActiveAt(LocalDateTime.of(MONDAY.plusDays(1), LocalTime.of(0, 31))));
    }

    @Test
    void aOneOffFiresOnItsDateAndNeverAgain() {
        Recording r = once("once", MONDAY, LocalTime.of(9, 0), 30);
        assertTrue(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(9, 10))));
        assertFalse(r.isActiveAt(LocalDateTime.of(MONDAY.plusWeeks(1), LocalTime.of(9, 10))));
        assertNull(r.nextStart(LocalDateTime.of(MONDAY, LocalTime.of(9, 1))), "already begun, so never again");
    }

    @Test
    void aDisabledRecordingNeverFires() {
        Recording r = new Recording(
                "off",
                "off",
                98_900_000L,
                "FM",
                LocalTime.of(9, 0),
                Duration.ofMinutes(60),
                Set.of(DayOfWeek.MONDAY),
                null,
                false);
        assertFalse(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(9, 30))));
        assertNull(r.nextStart(LocalDateTime.of(MONDAY, LocalTime.of(0, 0))));
    }

    /** "Repeat on no days" means never, not every day. */
    @Test
    void aRepeatWithNoDaysAndNoDateNeverFires() {
        Recording r = new Recording(
                "empty", "empty", 98_900_000L, "FM", LocalTime.of(9, 0), Duration.ofMinutes(60), Set.of(), null, true);
        assertFalse(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(9, 30))));
        assertNull(r.nextStart(LocalDateTime.of(MONDAY, LocalTime.of(0, 0))));
    }

    @Test
    void findsTheNextStart() {
        Recording r = weekly("show", LocalTime.of(9, 0), 60, DayOfWeek.WEDNESDAY);
        assertEquals(
                LocalDateTime.of(MONDAY.plusDays(2), LocalTime.of(9, 0)),
                r.nextStart(LocalDateTime.of(MONDAY, LocalTime.of(12, 0))));
    }

    /** Today still counts when the time has not passed yet. */
    @Test
    void nextStartCanBeLaterToday() {
        Recording r = weekly("show", LocalTime.of(23, 0), 30, DayOfWeek.MONDAY);
        assertEquals(
                LocalDateTime.of(MONDAY, LocalTime.of(23, 0)),
                r.nextStart(LocalDateTime.of(MONDAY, LocalTime.of(8, 0))));
    }

    /** When two overlap, the one that started most recently wins, so a new entry is not ignored. */
    @Test
    void theMostRecentlyStartedOverlapWins() {
        Recording early = weekly("early", LocalTime.of(9, 0), 120, DayOfWeek.MONDAY);
        Recording later = weekly("later", LocalTime.of(10, 0), 60, DayOfWeek.MONDAY);
        LocalDateTime now = LocalDateTime.of(MONDAY, LocalTime.of(10, 30));
        assertSame(later, Scheduler.activeAt(List.of(early, later), now));
        assertSame(later, Scheduler.activeAt(List.of(later, early), now), "order of the list must not matter");
    }

    @Test
    void nothingActiveIsNull() {
        assertNull(Scheduler.activeAt(
                List.of(weekly("s", LocalTime.of(9, 0), 60, DayOfWeek.MONDAY)),
                LocalDateTime.of(MONDAY, LocalTime.of(12, 0))));
        assertNull(Scheduler.activeAt(List.of(), LocalDateTime.of(MONDAY, LocalTime.NOON)));
        assertNull(Scheduler.activeAt(null, LocalDateTime.of(MONDAY, LocalTime.NOON)));
    }

    @Test
    void reportsTheSoonestUpcomingStart() {
        Recording wed = weekly("wed", LocalTime.of(9, 0), 60, DayOfWeek.WEDNESDAY);
        Recording tue = weekly("tue", LocalTime.of(20, 0), 60, DayOfWeek.TUESDAY);
        assertEquals(
                LocalDateTime.of(MONDAY.plusDays(1), LocalTime.of(20, 0)),
                Scheduler.nextStart(List.of(wed, tue), LocalDateTime.of(MONDAY, LocalTime.of(12, 0))));
    }

    /** A zero or negative duration would make a schedule that is never active; it is corrected. */
    @Test
    void anImpossibleDurationIsCorrected() {
        Recording r = new Recording(
                "z", "z", 98_900_000L, "FM", LocalTime.of(9, 0), Duration.ZERO, Set.of(DayOfWeek.MONDAY), null, true);
        assertTrue(r.duration().toMinutes() > 0);
        assertTrue(r.isActiveAt(LocalDateTime.of(MONDAY, LocalTime.of(9, 1))));
    }
}
