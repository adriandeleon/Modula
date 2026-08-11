package com.modula.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TunerGainTest {

    /** The R820T2's real gain table in tenths of a dB, which is what most dongles carry. */
    private static final int[] R820T2 = {
        0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421,
        434, 439, 445, 480, 496
    };

    @Test
    void anExactlySupportedGainIsTakenAsIs() {
        assertEquals(297, TunerGain.nearest(R820T2, 297));
    }

    @Test
    void anUnsupportedGainMovesToTheNearestStep() {
        assertEquals(297, TunerGain.nearest(R820T2, 300));
        assertEquals(280, TunerGain.nearest(R820T2, 285));
    }

    /**
     * The failure being avoided is an over-driven ADC, so an exact tie has to resolve downward — the
     * option with more headroom is the safe one.
     */
    @Test
    void aTieResolvesToTheLowerGain() {
        int[] supported = {100, 200};
        assertEquals(100, TunerGain.nearest(supported, 150));
    }

    @Test
    void theOrderOfTheSupportedListDoesNotMatter() {
        int[] shuffled = {496, 0, 297, 144, 421};
        assertEquals(297, TunerGain.nearest(shuffled, 300));
    }

    /**
     * {@code rtl_tcp} carries a gain count in its header but not the values, so there is nothing to
     * enumerate over that transport. Returning the target lets the server's own librtlsdr map it,
     * rather than silently setting no gain at all.
     */
    @Test
    void nothingToChooseFromReturnsTheTargetUnchanged() {
        assertEquals(TunerGain.TARGET_TENTHS, TunerGain.nearest(new int[0], TunerGain.TARGET_TENTHS));
        assertEquals(TunerGain.TARGET_TENTHS, TunerGain.nearest(null, TunerGain.TARGET_TENTHS));
        assertEquals(TunerGain.TARGET_TENTHS, TunerGain.choose(new int[0]));
    }

    @Test
    void theShippedTargetLandsWellInsideTheTunersRange() {
        int chosen = TunerGain.choose(R820T2);
        assertTrue(chosen > 0, "a gain of zero would be no gain at all");
        assertTrue(
                chosen < R820T2[R820T2.length - 1],
                "the top of the range overloads on a local FM station, so the target must not reach it");
        assertEquals(297, chosen, "30 dB should land on the 29.7 dB step");
    }

    /**
     * The calibration hatch has to be lenient: a mistyped diagnostic flag must not stop a radio starting,
     * so an unparseable value falls back to the default rather than throwing.
     */
    @Test
    void aGainOverrideIsReadInDecibelsAndBadInputIsIgnored() {
        assertEquals(400, TunerGain.parseDecibels("40").orElseThrow());
        assertEquals(497, TunerGain.parseDecibels("49.7").orElseThrow());
        assertEquals(400, TunerGain.parseDecibels("  40  ").orElseThrow());

        assertTrue(TunerGain.parseDecibels("high").isEmpty());
        assertTrue(TunerGain.parseDecibels("").isEmpty());
        assertTrue(TunerGain.parseDecibels("-5").isEmpty(), "a negative gain is not a gain");
        assertTrue(TunerGain.parseDecibels("NaN").isEmpty());
    }

    @Test
    void gainsAreDescribedInDecibels() {
        assertEquals("29.7 dB", TunerGain.describe(297));
        assertEquals("0.0 dB", TunerGain.describe(0));
        assertEquals("49.6 dB", TunerGain.describe(496));
    }
}
