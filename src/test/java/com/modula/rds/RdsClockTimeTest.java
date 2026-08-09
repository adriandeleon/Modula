package com.modula.rds;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RdsClockTimeTest {

    /** Builds a 4A group with the fields in the places the standard puts them. */
    private static RdsGroup group(long mjd, int hour, int minute, int halfHours, boolean negative) {
        int b = (4 << 12) | (int) ((mjd >>> 15) & 0x03);
        int c = (int) ((mjd & 0x7FFF) << 1) | ((hour >>> 4) & 0x01);
        int d = ((hour & 0x0F) << 12) | ((minute & 0x3F) << 6) | (negative ? 0x20 : 0) | (halfHours & 0x1F);
        return new RdsGroup(0x1234, b, c, d);
    }

    /** MJD 60000 is 2023-02-25; the arithmetic is worth pinning against a known day. */
    @Test
    void decodesAKnownDateAndTime() {
        assertEquals(LocalDateTime.of(2023, 2, 25, 14, 30), RdsClockTime.decode(group(60_000, 14, 30, 0, false)));
    }

    @Test
    void appliesAPositiveLocalOffset() {
        // 12:00 UTC with +2h (four half-hours) is 14:00 local.
        assertEquals(LocalDateTime.of(2023, 2, 25, 14, 0), RdsClockTime.decode(group(60_000, 12, 0, 4, false)));
    }

    @Test
    void appliesANegativeLocalOffset() {
        // 12:00 UTC with -5h (ten half-hours) is 07:00 local — the Americas case.
        assertEquals(LocalDateTime.of(2023, 2, 25, 7, 0), RdsClockTime.decode(group(60_000, 12, 0, 10, true)));
    }

    /** A half-hour zone, which is the reason the field is in half-hours at all. */
    @Test
    void appliesAHalfHourOffset() {
        assertEquals(LocalDateTime.of(2023, 2, 25, 12, 30), RdsClockTime.decode(group(60_000, 12, 0, 1, false)));
    }

    @Test
    void anOffsetCanCrossMidnight() {
        assertNotNull(RdsClockTime.decode(group(60_000, 23, 30, 4, false)));
        assertEquals(LocalDateTime.of(2023, 2, 26, 1, 30), RdsClockTime.decode(group(60_000, 23, 30, 4, false)));
    }

    /**
     * Stations transmitting a broken clock are common, so nonsense must be refused rather than shown.
     */
    @Test
    void rejectsImpossibleValues() {
        assertNull(RdsClockTime.decode(group(60_000, 25, 0, 0, false)), "hour 25");
        assertNull(RdsClockTime.decode(group(60_000, 12, 61, 0, false)), "minute 61");
        assertNull(RdsClockTime.decode(group(0, 12, 0, 0, false)), "a stopped clock at the MJD epoch");
    }

    @Test
    void ignoresGroupsThatAreNot4A() {
        assertNull(RdsClockTime.decode(new RdsGroup(0x1234, 0 << 12, 0, 0)), "group 0");
        assertNull(RdsClockTime.decode(new RdsGroup(0x1234, (4 << 12) | 0x0800, 0, 0)), "4B is not clock-time");
        assertNull(RdsClockTime.decode(null));
    }
}
