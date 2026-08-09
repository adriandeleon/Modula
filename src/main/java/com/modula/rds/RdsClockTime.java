package com.modula.rds;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Clock-time and date, from group 4A.
 *
 * <p>Pure bit-unpacking plus a Modified Julian Day conversion. The group carries the date as an MJD,
 * the time as UTC hours and minutes, and a local offset in half-hours — so the station is telling you
 * UTC and how far its listeners are from it, not its own wall clock.
 *
 * <p>Broadcast once a minute, so it takes up to a minute to appear after tuning. It is also the
 * field stations most often get wrong: plenty transmit a stopped or badly offset clock, which is why
 * this validates rather than trusting what arrives.
 */
final class RdsClockTime {

    /** The MJD epoch, 17 November 1858, as a Julian day number. */
    private static final long MJD_EPOCH_JDN = 2_400_001L;

    /** MJD 0 as an epoch day, so an MJD becomes a LocalDate by subtraction. */
    private static final long MJD_TO_EPOCH_DAY = 40_587L;

    private RdsClockTime() {}

    /**
     * Decodes a 4A group.
     *
     * <p>The layout spans three blocks: the low two bits of B and all of C hold the MJD, C's low bit
     * and D's top bits hold the hour, then the minute, then a signed half-hour offset.
     *
     * @return the local time the station is describing, or null when the group is not a valid 4A
     */
    static LocalDateTime decode(RdsGroup group) {
        if (group == null || group.type() != 4 || group.isVersionB()) {
            return null;
        }
        int b = group.b() & 0xFFFF;
        int c = group.c() & 0xFFFF;
        int d = group.d() & 0xFFFF;

        long mjd = ((long) (b & 0x03) << 15) | (c >>> 1);
        int hour = ((c & 0x01) << 4) | (d >>> 12);
        int minute = (d >>> 6) & 0x3F;
        int offsetHalfHours = d & 0x1F;
        boolean negative = (d & 0x20) != 0;

        if (hour > 23 || minute > 59 || offsetHalfHours > 24) {
            return null; // a station transmitting nonsense, which is common enough to expect
        }
        // MJD 0 is 1858; anything below the epoch is a stopped or unset clock rather than a date.
        if (mjd < MJD_TO_EPOCH_DAY) {
            return null;
        }

        LocalDateTime utc = LocalDateTime.ofEpochSecond(
                (mjd - MJD_TO_EPOCH_DAY) * 86_400L + hour * 3_600L + minute * 60L, 0, ZoneOffset.UTC);
        int minutes = offsetHalfHours * 30;
        return negative ? utc.minusMinutes(minutes) : utc.plusMinutes(minutes);
    }

    /** Kept so the epoch constant is not merely decorative if the conversion is ever revisited. */
    static long mjdEpochJulianDay() {
        return MJD_EPOCH_JDN;
    }
}
