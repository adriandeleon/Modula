package com.modula.demod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FmDiscriminatorTest {

    private static final double SAMPLE_RATE = 240_000.0;
    private static final double DEVIATION = 75_000.0;

    @Test
    void aConstantFrequencyOffsetGivesAConstantLevel() {
        // Half of full deviation must read as half of full scale.
        float[] out = demodulate(37_500.0, 1_000);
        for (int n = 1; n < out.length; n++) {
            assertEquals(0.5f, out[n], 1e-3f, "sample " + n);
        }
    }

    @Test
    void isSignedAboutTheCarrier() {
        assertEquals(-0.5f, demodulate(-37_500.0, 500)[100], 1e-3f);
        assertEquals(0.0f, demodulate(0.0, 500)[100], 1e-3f);
    }

    @Test
    void scalesFullDeviationToFullScale() {
        assertEquals(1.0f, demodulate(DEVIATION, 500)[100], 1e-3f);
    }

    @Test
    void isContinuousAcrossBlockBoundaries() {
        int count = 900;
        float[] i = new float[count];
        float[] q = new float[count];
        fill(i, q, 20_000.0, 0);

        FmDiscriminator whole = new FmDiscriminator(SAMPLE_RATE, DEVIATION);
        float[] a = new float[count];
        whole.demodulate(i, q, count, a);

        FmDiscriminator chunked = new FmDiscriminator(SAMPLE_RATE, DEVIATION);
        float[] b = new float[count];
        int pos = 0;
        for (int chunk : new int[] {13, 187, 1, 399, 300}) {
            float[] ci = new float[chunk];
            float[] cq = new float[chunk];
            float[] co = new float[chunk];
            System.arraycopy(i, pos, ci, 0, chunk);
            System.arraycopy(q, pos, cq, 0, chunk);
            chunked.demodulate(ci, cq, chunk, co);
            System.arraycopy(co, 0, b, pos, chunk);
            pos += chunk;
        }

        assertEquals(count, pos, "chunks must cover the signal exactly");
        for (int n = 0; n < count; n++) {
            assertEquals(a[n], b[n], 1e-6f, "block boundary changed the output at sample " + n);
        }
    }

    @Test
    void resetClearsThePreviousSample() {
        FmDiscriminator discriminator = new FmDiscriminator(SAMPLE_RATE, DEVIATION);
        float[] i = new float[100];
        float[] q = new float[100];
        float[] out = new float[100];
        fill(i, q, 60_000.0, 0);
        discriminator.demodulate(i, q, 100, out);
        discriminator.reset();

        fill(i, q, 0.0, 0);
        discriminator.demodulate(i, q, 100, out);
        assertTrue(Math.abs(out[1]) < 1e-3f, "state from the previous station leaked through reset");
    }

    private static float[] demodulate(double offsetHz, int count) {
        float[] i = new float[count];
        float[] q = new float[count];
        fill(i, q, offsetHz, 0);
        float[] out = new float[count];
        new FmDiscriminator(SAMPLE_RATE, DEVIATION).demodulate(i, q, count, out);
        return out;
    }

    /** A complex exponential at a fixed frequency offset from the carrier. */
    private static void fill(float[] i, float[] q, double offsetHz, int startSample) {
        for (int n = 0; n < i.length; n++) {
            double phase = 2.0 * Math.PI * offsetHz * (startSample + n) / SAMPLE_RATE;
            i[n] = (float) Math.cos(phase);
            q[n] = (float) Math.sin(phase);
        }
    }
}
