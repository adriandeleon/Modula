package com.modula.rds;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlternativeFrequenciesTest {

    private static RdsGroup zeroA(int first, int second) {
        return new RdsGroup(0x1234, 0 << 12, (first << 8) | second, 0);
    }

    @Test
    void codesBecomeFrequencies() {
        // 1 is the bottom of the band and 204 the top: 87.6 MHz and 107.9 MHz.
        assertEquals(87_600_000L, AlternativeFrequencies.frequencyFor(1));
        assertEquals(107_900_000L, AlternativeFrequencies.frequencyFor(204));
        assertEquals(0L, AlternativeFrequencies.frequencyFor(0));
        assertEquals(0L, AlternativeFrequencies.frequencyFor(205));
    }

    @Test
    void collectsBothCodesFromAGroup() {
        AlternativeFrequencies af = new AlternativeFrequencies();
        af.accept(zeroA(5, 10));
        assertEquals(List.of(88_000_000L, 88_500_000L), af.frequencies());
    }

    /** The list builds across groups, and a station repeats it endlessly. */
    @Test
    void accumulatesAcrossGroupsWithoutDuplicating() {
        AlternativeFrequencies af = new AlternativeFrequencies();
        af.accept(zeroA(5, 10));
        af.accept(zeroA(5, 20));
        assertEquals(List.of(88_000_000L, 88_500_000L, 89_500_000L), af.frequencies());
    }

    /** 250 says the next code is LF/MF, which cannot be reached from the FM band. */
    @Test
    void skipsTheFrequencyAfterAnLfMfMarker() {
        AlternativeFrequencies af = new AlternativeFrequencies();
        af.accept(zeroA(AlternativeFrequencies.LF_MF_FOLLOWS, 5));
        assertTrue(af.isEmpty(), "the code after the marker is an LF/MF channel, not 88.0 MHz");
    }

    @Test
    void ignoresCountAndFillerCodes() {
        AlternativeFrequencies af = new AlternativeFrequencies();
        af.accept(zeroA(AlternativeFrequencies.COUNT_BASE + 2, AlternativeFrequencies.FILLER));
        assertTrue(af.isEmpty());
        assertEquals(2, AlternativeFrequencies.announcedCount(AlternativeFrequencies.COUNT_BASE + 2));
        assertEquals(-1, AlternativeFrequencies.announcedCount(5));
    }

    /** 0B repeats the PI in block C, so reading it as frequencies would invent stations. */
    @Test
    void ignoresVersionBAndOtherGroups() {
        AlternativeFrequencies af = new AlternativeFrequencies();
        af.accept(new RdsGroup(0x1234, (0 << 12) | 0x0800, 0x0505, 0));
        af.accept(new RdsGroup(0x1234, 2 << 12, 0x0505, 0));
        af.accept(null);
        assertTrue(af.isEmpty());
    }

    @Test
    void clearForgetsEverything() {
        AlternativeFrequencies af = new AlternativeFrequencies();
        af.accept(zeroA(5, 10));
        af.clear();
        assertTrue(af.isEmpty());
        assertFalse(af.frequencies().iterator().hasNext());
    }
}
