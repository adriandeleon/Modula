package com.modula.dsp;

import java.util.Arrays;

/**
 * An integer-sample delay line for a real signal.
 *
 * <p>Exists to keep two paths time-aligned when only one of them passes through a filter. A
 * linear-phase FIR of N taps delays its output by (N−1)/2 samples, which is harmless on its own but
 * fatal when the filtered signal is used as a *phase reference* for the unfiltered one: the stereo
 * pilot band-pass delays the pilot by 165 samples, and at 38 kHz that is 26.125 cycles — the 0.125
 * costs a 45° phase error, which scales the recovered difference channel by cos(45°) and collapses
 * channel separation to about 14 dB. Nothing errors; the stereo image just quietly narrows.
 *
 * <p>Safe to use in place ({@code out} may alias {@code in}).
 *
 * <p>Stateful across blocks; see the note on {@link FirFilter}.
 */
public final class Delay {

    private final float[] buffer;
    private int index;

    public Delay(int samples) {
        if (samples < 0) {
            throw new IllegalArgumentException("samples must be >= 0, got " + samples);
        }
        this.buffer = new float[samples];
    }

    /** Number of samples of delay. */
    public int samples() {
        return buffer.length;
    }

    /** Writes {@code in} delayed by {@link #samples} into {@code out}. */
    public void process(float[] in, int count, float[] out) {
        if (buffer.length == 0) {
            if (in != out) {
                System.arraycopy(in, 0, out, 0, count);
            }
            return;
        }
        int i = index;
        for (int n = 0; n < count; n++) {
            float delayed = buffer[i];
            buffer[i] = in[n];
            i = i + 1 == buffer.length ? 0 : i + 1;
            out[n] = delayed;
        }
        index = i;
    }

    public void reset() {
        Arrays.fill(buffer, 0f);
        index = 0;
    }
}
