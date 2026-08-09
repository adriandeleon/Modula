package com.modula.dsp;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FirFilterTest {

    /**
     * The load-bearing test of the whole project.
     *
     * <p>Filtering a signal in one call and in many irregular chunks must give bit-identical output.
     * Lose the history and the output glitches at every block edge; lose the decimation phase and the
     * output drifts. Either bug passes a single-block test and then buzzes at the block rate in real
     * time, which is miserable to diagnose from audio alone.
     */
    @Test
    void producesIdenticalOutputRegardlessOfBlockBoundaries() {
        float[] taps = FirDesign.lowPass(31, 0.1);
        float[] input = noise(1000, 42);
        int decimation = 3;

        FirFilter single = new FirFilter(taps, decimation);
        float[] wholeOut = new float[single.outputCapacity(input.length)];
        int wholeCount = single.filter(input, input.length, wholeOut);

        // Chunk sizes chosen so none is a multiple of the decimation factor.
        int[] chunks = {7, 13, 100, 1, 250, 379, 250};
        assertEquals(input.length, Arrays.stream(chunks).sum(), "chunks must cover the input exactly");

        FirFilter chunked = new FirFilter(taps, decimation);
        float[] chunkedOut = new float[wholeOut.length + chunks.length];
        int chunkedCount = 0;
        int pos = 0;
        for (int chunk : chunks) {
            float[] slice = Arrays.copyOfRange(input, pos, pos + chunk);
            float[] tmp = new float[chunked.outputCapacity(chunk)];
            int n = chunked.filter(slice, chunk, tmp);
            System.arraycopy(tmp, 0, chunkedOut, chunkedCount, n);
            chunkedCount += n;
            pos += chunk;
        }

        assertEquals(wholeCount, chunkedCount, "same input must yield the same number of output samples");
        assertArrayEquals(
                Arrays.copyOf(wholeOut, wholeCount),
                Arrays.copyOf(chunkedOut, chunkedCount),
                1e-6f,
                "block boundaries must not change the output");
    }

    @Test
    void outputCapacityIsAlwaysSufficient() {
        float[] taps = FirDesign.lowPass(15, 0.2);
        for (int decimation = 1; decimation <= 7; decimation++) {
            FirFilter filter = new FirFilter(taps, decimation);
            for (int count = 1; count <= 200; count++) {
                float[] out = new float[filter.outputCapacity(count)];
                int n = filter.filter(noise(count, count), count, out);
                assertTrue(n <= out.length, "overflowed capacity at decimation=" + decimation + " count=" + count);
            }
        }
    }

    @Test
    void decimatesByTheRequestedFactor() {
        float[] taps = FirDesign.lowPass(9, 0.2);
        FirFilter filter = new FirFilter(taps, 5);
        float[] out = new float[filter.outputCapacity(1000)];
        assertEquals(200, filter.filter(noise(1000, 7), 1000, out));
    }

    @Test
    void passesDcAtUnityGain() {
        float[] taps = FirDesign.lowPass(31, 0.1);
        FirFilter filter = new FirFilter(taps, 1);
        float[] input = new float[500];
        Arrays.fill(input, 1f);
        float[] out = new float[filter.outputCapacity(input.length)];
        int n = filter.filter(input, input.length, out);
        // Settled samples, past the filter's own transient.
        for (int i = taps.length; i < n; i++) {
            assertEquals(1.0f, out[i], 1e-5f);
        }
    }

    @Test
    void resetClearsStateSoRetuneDoesNotSmearTheOldStation() {
        float[] taps = FirDesign.lowPass(31, 0.1);
        float[] input = noise(200, 3);

        FirFilter fresh = new FirFilter(taps, 2);
        float[] freshOut = new float[fresh.outputCapacity(input.length)];
        int freshCount = fresh.filter(input, input.length, freshOut);

        FirFilter reused = new FirFilter(taps, 2);
        float[] scratch = new float[reused.outputCapacity(input.length)];
        reused.filter(noise(200, 99), input.length, scratch);
        reused.reset();
        float[] reusedOut = new float[reused.outputCapacity(input.length)];
        int reusedCount = reused.filter(input, input.length, reusedOut);

        assertEquals(freshCount, reusedCount);
        assertArrayEquals(Arrays.copyOf(freshOut, freshCount), Arrays.copyOf(reusedOut, reusedCount), 1e-6f);
    }

    private static float[] noise(int count, long seed) {
        Random random = new Random(seed);
        float[] x = new float[count];
        for (int n = 0; n < count; n++) {
            x[n] = (float) (random.nextDouble() * 2.0 - 1.0);
        }
        return x;
    }
}
