package com.modula.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadoutsVolumeTest {

    @Test
    void percentIsTheSliderPosition() {
        assertEquals("0%", Readouts.volumePercent(0.0));
        assertEquals("50%", Readouts.volumePercent(0.5));
        assertEquals("70%", Readouts.volumePercent(0.7));
        assertEquals("100%", Readouts.volumePercent(1.0));
    }

    /** Unity is 0 dB and half amplitude is −6 dB: the check that the maths is gain, not power. */
    @Test
    void decibelsAreTwentyLogTen() {
        assertEquals("0.0 dB", Readouts.volumeDecibels(1.0));
        assertEquals(Readouts.MINUS + "6.0 dB", Readouts.volumeDecibels(0.5));
        assertEquals(Readouts.MINUS + "20.0 dB", Readouts.volumeDecibels(0.1));
    }

    /** Zero has no logarithm. Reporting it as -Infinity would be honest and useless. */
    @Test
    void silenceIsWordsNotInfinity() {
        assertEquals("muted", Readouts.volumeDecibels(0.0));
    }

    /** A true minus, not a hyphen: in a tabular monospace readout a hyphen reads as punctuation. */
    @Test
    void negativeValuesUseATypographicMinus() {
        assertTrue(Readouts.volumeDecibels(0.25).indexOf(Readouts.MINUS) == 0);
        assertTrue(Readouts.volumeDecibels(0.25).indexOf('-') < 0);
    }

    @Test
    void outOfRangeValuesAreClamped() {
        assertEquals("100%", Readouts.volumePercent(4.0));
        assertEquals("0%", Readouts.volumePercent(-1.0));
        assertEquals("0.0 dB", Readouts.volumeDecibels(9.0));
        assertEquals("muted", Readouts.volumeDecibels(-0.5));
    }
}
