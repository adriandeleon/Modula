package com.modula.dsp;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeemphasisTest {

    private static final double SAMPLE_RATE = 48_000.0;

    @Test
    void passesDcUnchanged() {
        Deemphasis deemphasis = Deemphasis.forMicros(75.0, SAMPLE_RATE);
        float[] buf = new float[2000];
        Arrays.fill(buf, 1f);
        deemphasis.process(buf, buf.length);
        assertEquals(1.0f, buf[buf.length - 1], 1e-4f, "DC must survive de-emphasis");
    }

    @Test
    void attenuatesHighFrequenciesMoreThanLow() {
        double low = responseAt(1_000.0, 75.0);
        double high = responseAt(10_000.0, 75.0);
        assertTrue(high < low, "10 kHz (%.3f) should be attenuated more than 1 kHz (%.3f)".formatted(high, low));
        // Closed form for a single-pole response: 1/sqrt(1 + (2*pi*f*tau)^2).
        assertEquals(expected(1_000.0, 75.0), low, 0.02);
        assertEquals(expected(10_000.0, 75.0), high, 0.02);
    }

    @Test
    void fiftyMicrosecondsIsGentlerThanSeventyFive() {
        assertTrue(
                responseAt(10_000.0, 50.0) > responseAt(10_000.0, 75.0),
                "the 50 us curve must roll off later than the 75 us one");
    }

    @Test
    void isContinuousAcrossBlockBoundaries() {
        float[] signal = tone(1_000.0, 4_800);

        Deemphasis whole = Deemphasis.forMicros(75.0, SAMPLE_RATE);
        float[] a = signal.clone();
        whole.process(a, a.length);

        Deemphasis chunked = Deemphasis.forMicros(75.0, SAMPLE_RATE);
        float[] b = signal.clone();
        int pos = 0;
        for (int chunk : new int[] {17, 300, 1, 1_482, 3_000}) {
            float[] slice = Arrays.copyOfRange(b, pos, pos + chunk);
            chunked.process(slice, chunk);
            System.arraycopy(slice, 0, b, pos, chunk);
            pos += chunk;
        }

        assertEquals(signal.length, pos, "chunks must cover the signal exactly");
        for (int n = 0; n < a.length; n++) {
            assertEquals(a[n], b[n], 1e-5f, "block boundary changed the output at sample " + n);
        }
    }

    private static double responseAt(double hz, double tauMicros) {
        Deemphasis deemphasis = Deemphasis.forMicros(tauMicros, SAMPLE_RATE);
        float[] buf = tone(hz, 9_600);
        deemphasis.process(buf, buf.length);
        return peak(buf, buf.length / 2);
    }

    private static double expected(double hz, double tauMicros) {
        double wt = 2.0 * Math.PI * hz * tauMicros * 1e-6;
        return 1.0 / Math.sqrt(1.0 + wt * wt);
    }

    private static float[] tone(double hz, int count) {
        float[] x = new float[count];
        for (int n = 0; n < count; n++) {
            x[n] = (float) Math.sin(2.0 * Math.PI * hz * n / SAMPLE_RATE);
        }
        return x;
    }

    /** Peak magnitude over the settled tail, avoiding the filter's start-up transient. */
    private static double peak(float[] x, int from) {
        double max = 0.0;
        for (int n = from; n < x.length; n++) {
            max = Math.max(max, Math.abs(x[n]));
        }
        return max;
    }
}
