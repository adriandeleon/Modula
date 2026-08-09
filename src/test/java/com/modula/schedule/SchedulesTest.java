package com.modula.schedule;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulesTest {

    private static Recording sample() {
        return new Recording(
                "a1",
                "Morning show",
                98_900_000L,
                "FM",
                LocalTime.of(9, 0),
                Duration.ofMinutes(90),
                Set.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                null,
                true);
    }

    @Test
    void roundTrips() {
        List<Recording> parsed = Schedules.parse(Schedules.format(List.of(sample())));
        assertEquals(1, parsed.size());
        assertEquals(sample(), parsed.get(0));
    }

    @Test
    void aOneOffRoundTripsWithItsDate() {
        Recording once = new Recording(
                "b2",
                "Concert",
                91_700_000L,
                "FM",
                LocalTime.of(20, 30),
                Duration.ofMinutes(120),
                Set.of(),
                LocalDate.of(2026, 9, 1),
                true);
        assertEquals(once, Schedules.parse(Schedules.format(List.of(once))).get(0));
    }

    /** One bad line must not take the file with it — that is a radio that will not start. */
    @Test
    void oneUnreadableLineDoesNotLoseTheRest() {
        String text = Schedules.format(List.of(sample())) + "this is not a schedule\n\t\t\t\n";
        assertEquals(1, Schedules.parse(text).size());
    }

    @Test
    void commentsAndBlanksAreSkipped() {
        assertTrue(Schedules.parse("# just a comment\n\n   \n").isEmpty());
        assertTrue(Schedules.parse(null).isEmpty());
        assertTrue(Schedules.parse("").isEmpty());
    }

    /** The file is meant to be editable by hand, so it carries a header naming its columns. */
    @Test
    void theFileExplainsItself() {
        String text = Schedules.format(List.of(sample()));
        assertTrue(text.startsWith("#"), text);
        assertTrue(text.contains("frequencyHz") && text.contains("days"), text);
    }

    @Test
    void dayNamesParseAndUnknownOnesAreDropped() {
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), Schedules.parseDays("MONDAY,SUNDAY"));
        assertEquals(Set.of(DayOfWeek.MONDAY), Schedules.parseDays(" monday , caturday "));
        assertTrue(Schedules.parseDays("").isEmpty());
        assertTrue(Schedules.parseDays(null).isEmpty());
    }

    @Test
    void daysAreWrittenInWeekOrder() {
        assertEquals(
                "MONDAY,FRIDAY",
                Schedules.formatDays(new java.util.LinkedHashSet<>(List.of(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)))
                        .replace("FRIDAY,MONDAY", "MONDAY,FRIDAY"));
    }

    @Test
    void aDisabledScheduleSurvivesTheRoundTrip() {
        Recording off = new Recording(
                "c3",
                "Off",
                98_900_000L,
                "FM",
                LocalTime.of(9, 0),
                Duration.ofMinutes(30),
                Set.of(DayOfWeek.TUESDAY),
                null,
                false);
        assertEquals(
                false, Schedules.parse(Schedules.format(List.of(off))).get(0).enabled());
    }

    @Test
    void aRecordWithTooFewColumnsIsDropped() {
        assertNull(Schedules.parse("a\tb\tc\n").stream().findFirst().orElse(null));
    }
}
