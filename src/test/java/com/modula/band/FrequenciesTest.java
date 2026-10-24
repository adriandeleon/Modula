package com.modula.band;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FrequenciesTest {

    @Test
    void fmReadsInMegahertzToOneDecimal() {
        assertEquals("98.9", Frequencies.digits(98_900_000L));
        assertEquals("MHz", Frequencies.unit(98_900_000L));
        assertEquals("107.9", Frequencies.digits(107_900_000L));
        assertEquals("87.5", Frequencies.digits(87_500_000L));
    }

    /** The bug: 1000 to 1040 kHz all read "1.0 MHz", so the dial could not tell five stations apart. */
    @Test
    void mediumWaveReadsInKilohertzAndEveryChannelIsDistinct() {
        assertEquals("1010", Frequencies.digits(1_010_000L));
        assertEquals("kHz", Frequencies.unit(1_010_000L));
        assertEquals("531", Frequencies.digits(531_000L));
        assertEquals("1010 kHz", Frequencies.withUnit(1_010_000L));
        assertEveryChannelDistinct(BandPlan.mediumWave(Region.AMERICAS));
        assertEveryChannelDistinct(BandPlan.mediumWave(Region.EUROPE));
    }

    /** The bug: 118.000 and 118.025 both read "118.0". */
    @Test
    void airbandShowsItsTwentyFiveKilohertzGrid() {
        assertEquals("118.025", Frequencies.digits(118_025_000L));
        assertEquals("118.000", Frequencies.digits(118_000_000L));
        assertEquals("MHz", Frequencies.unit(118_025_000L));
        assertEveryChannelDistinct(BandPlan.airband());
    }

    @Test
    void fmIsDistinctOnBothGrids() {
        assertEveryChannelDistinct(BandPlan.fm(Region.AMERICAS));
        assertEveryChannelDistinct(BandPlan.fm(Region.EUROPE));
    }

    @Test
    void typedFrequenciesAreReadInTheBandsOwnUnit() {
        BandPlan mw = BandPlan.mediumWave(Region.AMERICAS);
        assertEquals(1_010_000L, Frequencies.parse("1010", mw), "kHz on medium wave");
        assertEquals(1_010_000L, Frequencies.parse("1.01", mw), "MHz still works there");
        assertEquals(98_900_000L, Frequencies.parse("98.9", BandPlan.fm(Region.AMERICAS)));
        assertEquals(118_025_000L, Frequencies.parse("118.025", BandPlan.airband()));
        assertEquals(-1L, Frequencies.parse("abc", mw));
        assertEquals(-1L, Frequencies.parse("-5", mw));
        assertEquals(-1L, Frequencies.parse("Infinity", mw));
    }

    private static void assertEveryChannelDistinct(BandPlan band) {
        for (int c = 1; c < band.channelCount(); c++) {
            assertNotEquals(
                    Frequencies.digits(band.frequencyOf(c - 1)),
                    Frequencies.digits(band.frequencyOf(c)),
                    "%s channels %d and %d read the same".formatted(band.name(), c - 1, c));
        }
    }
}
