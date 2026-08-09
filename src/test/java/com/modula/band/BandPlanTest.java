package com.modula.band;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BandPlanTest {

    @Test
    void americanFmSitsOnTheOddTwoHundredKilohertzGrid() {
        BandPlan fm = BandPlan.fm(Region.AMERICAS);
        assertEquals(88_100_000L, fm.minHz());
        assertEquals(107_900_000L, fm.maxHz());
        assertEquals(100, fm.channelCount());
        assertEquals(101_500_000L, fm.snap(101_487_000L), "should snap to the nearest real channel");
    }

    @Test
    void europeanFmUsesHundredKilohertzSpacing() {
        BandPlan fm = BandPlan.fm(Region.EUROPE);
        assertEquals(87_500_000L, fm.minHz());
        assertEquals(108_000_000L, fm.maxHz());
        assertEquals(100_300_000L, fm.snap(100_280_000L));
    }

    @Test
    void tuningWrapsAtBothEndsOfTheBand() {
        BandPlan fm = BandPlan.fm(Region.AMERICAS);
        assertEquals(fm.minHz(), fm.next(fm.maxHz()), "past the top wraps to the bottom");
        assertEquals(fm.maxHz(), fm.previous(fm.minHz()), "below the bottom wraps to the top");
    }

    @Test
    void steppingIsReversible() {
        BandPlan fm = BandPlan.fm(Region.AMERICAS);
        long start = 98_100_000L;
        assertEquals(start, fm.previous(fm.next(start)));
        assertEquals(start, fm.next(fm.previous(start)));
    }

    @Test
    void snapClampsIntoTheBand() {
        BandPlan fm = BandPlan.fm(Region.AMERICAS);
        assertEquals(fm.minHz(), fm.snap(1_000_000L));
        assertEquals(fm.maxHz(), fm.snap(500_000_000L));
    }

    @Test
    void containsRejectsFrequenciesOutsideTheBand() {
        BandPlan fm = BandPlan.fm(Region.AMERICAS);
        assertTrue(fm.contains(101_500_000L));
        assertFalse(fm.contains(87_900_000L));
        assertFalse(fm.contains(108_100_000L));
    }

    @Test
    void everyChannelRoundTripsThroughItsIndex() {
        BandPlan fm = BandPlan.fm(Region.EUROPE);
        for (int channel = 0; channel < fm.channelCount(); channel++) {
            long hz = fm.frequencyOf(channel);
            assertEquals(channel, fm.channelOf(hz), "channel " + channel);
            assertEquals(hz, fm.snap(hz), "snapping a grid frequency must be a no-op");
        }
    }

    @Test
    void mediumWaveSpacingFollowsTheRegion() {
        assertEquals(10_000, BandPlan.mediumWave(Region.AMERICAS).spacingHz());
        assertEquals(9_000, BandPlan.mediumWave(Region.EUROPE).spacingHz());
    }

    @Test
    void airbandIsWithinReachOfAStockTuner() {
        BandPlan air = BandPlan.airband();
        assertTrue(air.minHz() > 24_000_000L, "airband must clear the R820T2 tuner floor");
        assertEquals(137_000_000L, air.maxHz());
    }
}
