package com.modula.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FftTest {

    @Test
    void anImpulseTransformsToAFlatSpectrum() {
        int n = 64;
        float[] re = new float[n];
        float[] im = new float[n];
        re[0] = 1f;

        Fft.transform(re, im);

        for (int i = 0; i < n; i++) {
            assertEquals(1.0, Math.hypot(re[i], im[i]), 1e-5, "bin " + i);
        }
    }

    @Test
    void aSingleBinToneLandsInThatBin() {
        int n = 128;
        int bin = 9;
        float[] re = new float[n];
        float[] im = new float[n];
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * bin * i / n;
            re[i] = (float) Math.cos(a);
            im[i] = (float) Math.sin(a);
        }

        Fft.transform(re, im);

        for (int i = 0; i < n; i++) {
            double magnitude = Math.hypot(re[i], im[i]);
            if (i == bin) {
                assertEquals(n, magnitude, 1e-3, "the tone's own bin");
            } else {
                assertTrue(magnitude < 1e-3, "energy leaked into bin " + i);
            }
        }
    }

    /** Energy is conserved, which catches a scaling or butterfly error a single tone would not. */
    @Test
    void conservesEnergy() {
        int n = 256;
        float[] re = new float[n];
        float[] im = new float[n];
        java.util.Random random = new java.util.Random(4);
        double before = 0;
        for (int i = 0; i < n; i++) {
            re[i] = (float) random.nextGaussian();
            im[i] = (float) random.nextGaussian();
            before += re[i] * re[i] + im[i] * im[i];
        }

        Fft.transform(re, im);

        double after = 0;
        for (int i = 0; i < n; i++) {
            after += re[i] * re[i] + im[i] * im[i];
        }
        assertEquals(before, after / n, before * 1e-4, "Parseval");
    }

    /** DC must land in the middle, or the spectrum display is mirrored about its own centre marker. */
    @Test
    void magnitudesPutDcInTheMiddle() {
        int n = 64;
        float[] re = new float[n];
        float[] im = new float[n];
        java.util.Arrays.fill(re, 1f); // pure DC

        float[] out = new float[n];
        Fft.magnitudesDb(re, im, out, -90.0);

        int peak = 0;
        for (int i = 1; i < n; i++) {
            if (out[i] > out[peak]) {
                peak = i;
            }
        }
        assertEquals(n / 2, peak, "DC belongs at the centre of the display");
    }

    @Test
    void rejectsLengthsThatAreNotPowersOfTwo() {
        assertThrows(IllegalArgumentException.class, () -> Fft.transform(new float[100], new float[100]));
        assertThrows(IllegalArgumentException.class, () -> Fft.transform(new float[64], new float[32]));
    }

    @Test
    void hannWindowIsSymmetricAndPeaksInTheMiddle() {
        float[] window = new float[64];
        Fft.hann(window);

        assertEquals(0.0, window[0], 1e-6);
        assertEquals(0.0, window[63], 1e-6);
        for (int i = 0; i < 32; i++) {
            assertEquals(window[i], window[63 - i], 1e-6f, "asymmetric at " + i);
        }
        assertTrue(window[32] > 0.99, "should peak near the middle");
    }
}
