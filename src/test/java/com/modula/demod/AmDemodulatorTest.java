package com.modula.demod;

import com.modula.TestSignals;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmDemodulatorTest {

    private static final double RATE = 48_000.0;
    private static final double TONE_HZ = 1_000.0;

    /** Exactly 100 cycles, so the correlation suffers no leakage. */
    private static final int ANALYSIS = 4_800;

    @Test
    void recoversTheModulation() {
        float[] audio = demodulate(0.5, 1.0, 48_000);
        var measured = TestSignals.measure(audio, audio.length - ANALYSIS, ANALYSIS, TONE_HZ, RATE);

        assertEquals(0.5, measured.amplitude(), 0.03, "recovered depth should be the modulation index");
        assertTrue(measured.purity() > 0.95, "should be a clean tone, purity " + measured.purity());
    }

    /**
     * The reason the carrier is divided out rather than subtracted: a station ten times stronger must
     * sound the same, not ten times louder. Subtracting the mean would make every fade a volume change.
     */
    @Test
    void loudnessDoesNotFollowSignalStrength() {
        double quiet = amplitudeOf(demodulate(0.5, 0.05, 48_000));
        double loud = amplitudeOf(demodulate(0.5, 1.0, 48_000));

        assertEquals(loud, quiet, 0.03, "a weak carrier and a strong one must demodulate to the same level");
    }

    @Test
    void trackerFollowsTheCarrierLevel() {
        AmDemodulator demodulator = new AmDemodulator(RATE);
        float[] i = new float[48_000];
        float[] q = new float[48_000];
        fill(i, q, 0.5, 0.8);
        demodulator.demodulate(i, q, i.length, new float[i.length]);

        assertEquals(0.8, demodulator.carrierLevel(), 0.02);
    }

    @Test
    void isContinuousAcrossBlockBoundaries() {
        int count = 24_000;
        float[] i = new float[count];
        float[] q = new float[count];
        fill(i, q, 0.5, 1.0);

        AmDemodulator whole = new AmDemodulator(RATE);
        float[] a = new float[count];
        whole.demodulate(i, q, count, a);

        AmDemodulator chunked = new AmDemodulator(RATE);
        float[] b = new float[count];
        int pos = 0;
        for (int chunk : new int[] {13, 1_987, 1, 9_999, 12_000}) {
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

    /** A dead channel must not have its noise divided up to full scale. */
    @Test
    void silenceStaysSilentRatherThanExploding() {
        AmDemodulator demodulator = new AmDemodulator(RATE);
        float[] out = new float[4_800];
        demodulator.demodulate(new float[4_800], new float[4_800], 4_800, out);

        for (float v : out) {
            assertTrue(Math.abs(v) <= 1.0f, "a zero carrier produced " + v);
        }
    }

    @Test
    void resetForgetsThePreviousStation() {
        AmDemodulator demodulator = new AmDemodulator(RATE);
        float[] i = new float[4_800];
        float[] q = new float[4_800];
        fill(i, q, 0.5, 1.0);
        demodulator.demodulate(i, q, i.length, new float[i.length]);
        demodulator.reset();

        assertEquals(0.0, demodulator.carrierLevel(), 1e-9);
    }

    private static double amplitudeOf(float[] audio) {
        return TestSignals.measure(audio, audio.length - ANALYSIS, ANALYSIS, TONE_HZ, RATE)
                .amplitude();
    }

    private static float[] demodulate(double depth, double carrier, int count) {
        float[] i = new float[count];
        float[] q = new float[count];
        fill(i, q, depth, carrier);
        float[] out = new float[count];
        new AmDemodulator(RATE).demodulate(i, q, count, out);
        return out;
    }

    /** A carrier at baseband whose envelope carries the tone: the whole of an AM signal. */
    private static void fill(float[] i, float[] q, double depth, double carrier) {
        for (int n = 0; n < i.length; n++) {
            double envelope = carrier * (1.0 + depth * Math.sin(2.0 * Math.PI * TONE_HZ * n / RATE));
            i[n] = (float) envelope;
            q[n] = 0f;
        }
    }
}
