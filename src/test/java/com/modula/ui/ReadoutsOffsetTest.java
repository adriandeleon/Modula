package com.modula.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The carrier-offset readout, which is a diagnosis rather than a measurement: it has to stay quiet
 * about an offset nobody needs to act on, and name the cause when there is one.
 */
class ReadoutsOffsetTest {

    private static final long FM_HZ = 100_000_000L;

    @Test
    void anUnmeasuredOffsetSaysNothing() {
        assertEquals("", Readouts.carrierOffset(Double.NaN, FM_HZ));
    }

    @Test
    void asmallOffsetSaysNothingEither() {
        assertEquals("", Readouts.carrierOffset(1_500.0, FM_HZ), "a well-behaved dongle must not nag");
        assertEquals("", Readouts.carrierOffset(-1_500.0, FM_HZ));
    }

    /** 72 ppm at 100 MHz is 7.2 kHz, which is the case this readout exists for. */
    @Test
    void aMiscalibratedDongleIsNamedInBothUnits() {
        String text = Readouts.carrierOffset(7_200.0, FM_HZ);
        assertTrue(text.contains("7.2 kHz"), text);
        assertTrue(text.contains("72 ppm"), text);
    }

    @Test
    void aNegativeOffsetUsesATrueMinusLikeEveryOtherReadout() {
        String text = Readouts.carrierOffset(-7_200.0, FM_HZ);
        assertTrue(text.contains(Readouts.MINUS + "7.2 kHz"), text);
        assertTrue(text.contains("72 ppm"), "the ppm is a magnitude, so it carries no sign: " + text);
    }

    /**
     * The ppm is the offset relative to where we tuned, so the same absolute error is enormous on
     * medium wave and unremarkable on FM. Reporting one without the other would misattribute it.
     */
    @Test
    void thePpmIsRelativeToTheTunedFrequency() {
        assertTrue(Readouts.carrierOffset(7_200.0, FM_HZ).contains("72 ppm"));
        assertTrue(Readouts.carrierOffset(7_200.0, 200_000_000L).contains("36 ppm"));
    }

    @Test
    void withNoFrequencyToDivideByItReportsTheHertzAlone() {
        String text = Readouts.carrierOffset(7_200.0, 0L);
        assertTrue(text.contains("7.2 kHz"), text);
        assertTrue(!text.contains("ppm"), "ppm with nothing to divide by would be invented: " + text);
    }

    @Test
    void theThresholdIsTheBoundaryItClaimsToBe() {
        assertEquals("", Readouts.carrierOffset(Readouts.OFFSET_WORTH_REPORTING_HZ - 1, FM_HZ));
        assertTrue(!Readouts.carrierOffset(Readouts.OFFSET_WORTH_REPORTING_HZ, FM_HZ)
                .isEmpty());
    }
}
