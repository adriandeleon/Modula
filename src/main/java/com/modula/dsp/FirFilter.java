package com.modula.dsp;

/**
 * A decimating FIR filter over a real signal.
 *
 * <p><b>The whole point of this class is that it is correct across block boundaries.</b> It carries
 * both the tail of the previous block ({@link #history}) and the decimation {@link #phase} — the
 * offset of the next output sample within the incoming block. Drop either and the filter still
 * passes a single-block unit test while buzzing at exactly the block rate in real time, which is
 * the single most common bug in a streaming DSP chain. {@code FirFilterTest} pins this by asserting
 * that one large call and many small calls produce identical output.
 *
 * <p>Not thread-safe: one instance belongs to one chain on one thread. For complex input, use two
 * instances (I and Q); fed the same counts they stay in lockstep.
 */
public final class FirFilter {

    private final float[] taps;
    private final int decimation;
    private final float[] history; // taps.length - 1 trailing samples of the previous block
    private float[] work; // history ++ input, so the inner loop needs no bounds branch
    private int phase;

    public FirFilter(float[] taps, int decimation) {
        if (taps.length < 1) {
            throw new IllegalArgumentException("taps must not be empty");
        }
        if (decimation < 1) {
            throw new IllegalArgumentException("decimation must be >= 1, got " + decimation);
        }
        this.taps = taps.clone();
        this.decimation = decimation;
        this.history = new float[taps.length - 1];
        this.work = new float[0];
    }

    /** Safe output-array size for an input of {@code inputCount} samples. */
    public int outputCapacity(int inputCount) {
        return inputCount / decimation + 1;
    }

    public int decimation() {
        return decimation;
    }

    /**
     * Filters and decimates {@code count} samples of {@code in} into {@code out}.
     *
     * @return the number of samples written to {@code out} — <b>not</b> constant across calls, since
     *     {@code count} need not be a multiple of the decimation factor. Callers must thread this
     *     count downstream rather than reading {@code out.length}.
     */
    public int filter(float[] in, int count, float[] out) {
        int n = taps.length;
        int hist = history.length;

        if (work.length < hist + count) {
            // Grows only until the largest block seen; steady state is allocation-free.
            work = new float[hist + count];
        }
        System.arraycopy(history, 0, work, 0, hist);
        System.arraycopy(in, 0, work, hist, count);

        int outCount = 0;
        int idx = phase;
        while (idx < count) {
            int base = idx + hist;
            float acc = 0f;
            for (int k = 0; k < n; k++) {
                acc += taps[k] * work[base - k];
            }
            out[outCount++] = acc;
            idx += decimation;
        }
        phase = idx - count;

        if (count >= hist) {
            System.arraycopy(in, count - hist, history, 0, hist);
        } else {
            System.arraycopy(history, count, history, 0, hist - count);
            System.arraycopy(in, 0, history, hist - count, count);
        }
        return outCount;
    }

    /** Clears the filter state. Call on retune, so the previous station's tail is not smeared in. */
    public void reset() {
        java.util.Arrays.fill(history, 0f);
        phase = 0;
    }
}
