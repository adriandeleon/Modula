package com.modula.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirDesignTest {

    @Test
    void hasUnityDcGain() {
        for (double cutoff : new double[] {0.01, 0.05, 0.1, 0.25, 0.4}) {
            float[] h = FirDesign.lowPass(63, cutoff);
            double sum = 0.0;
            for (float v : h) {
                sum += v;
            }
            assertEquals(1.0, sum, 1e-5, "DC gain must be unity at cutoff " + cutoff);
        }
    }

    @Test
    void isSymmetricSoPhaseIsLinear() {
        float[] h = FirDesign.lowPass(51, 0.1);
        for (int n = 0; n < h.length / 2; n++) {
            assertEquals(h[n], h[h.length - 1 - n], 1e-7f, "asymmetric at tap " + n);
        }
    }

    @Test
    void attenuatesAboveTheCutoff() {
        float[] h = FirDesign.lowPass(101, 0.1);
        double passband = magnitudeAt(h, 0.05);
        double stopband = magnitudeAt(h, 0.25);
        assertTrue(passband > 0.99, "passband should be flat, got " + passband);
        assertTrue(stopband < 1e-3, "stopband should be well below -60 dB, got " + stopband);
    }

    @Test
    void highPassIsTheMirrorOfALowPass() {
        float[] h = FirDesign.highPass(101, 0.25);
        assertTrue(magnitudeAt(h, 0.05) < 1e-3, "should reject well below the cutoff");
        assertTrue(magnitudeAt(h, 0.45) > 0.99, "and pass well above it");
    }

    @Test
    void bandPassPassesItsCentreAndRejectsBothSides() {
        // The stereo pilot band: 17-21 kHz at a 240 kHz sample rate.
        float[] h = FirDesign.bandPass(331, 17_000.0 / 240_000, 21_000.0 / 240_000);

        assertEquals(1.0, magnitudeAt(h, 19_000.0 / 240_000), 0.02, "unity at the pilot");
        assertTrue(magnitudeAt(h, 5_000.0 / 240_000) < 1e-3, "must reject the mono sum");
        assertTrue(magnitudeAt(h, 38_000.0 / 240_000) < 1e-3, "must reject the difference channel");
    }

    @Test
    void bandPassRejectsAnInvertedBand() {
        assertThrows(IllegalArgumentException.class, () -> FirDesign.bandPass(101, 0.3, 0.1));
    }

    @Test
    void tapsForTransitionIsAlwaysOdd() {
        for (double width = 0.005; width < 0.3; width += 0.007) {
            int taps = FirDesign.tapsForTransition(width);
            assertEquals(1, taps & 1, "taps must be odd for width " + width);
        }
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> FirDesign.lowPass(64, 0.1), "even taps");
        assertThrows(IllegalArgumentException.class, () -> FirDesign.lowPass(1, 0.1), "too few taps");
        assertThrows(IllegalArgumentException.class, () -> FirDesign.lowPass(31, 0.0), "cutoff at zero");
        assertThrows(IllegalArgumentException.class, () -> FirDesign.lowPass(31, 0.5), "cutoff at Nyquist");
    }

    /** Magnitude of the frequency response at a normalised frequency, by direct DTFT evaluation. */
    private static double magnitudeAt(float[] h, double normalisedFrequency) {
        double re = 0.0;
        double im = 0.0;
        for (int n = 0; n < h.length; n++) {
            double a = -2.0 * Math.PI * normalisedFrequency * n;
            re += h[n] * Math.cos(a);
            im += h[n] * Math.sin(a);
        }
        return Math.hypot(re, im);
    }
}
