package com.modula.demod;

import com.modula.dsp.FirFilter;

/**
 * Recovers the L−R difference channel from the FM multiplex.
 *
 * <p>The multiplex carries four things stacked in frequency:
 *
 * <pre>
 *   0 – 15 kHz    L+R, the mono sum every receiver hears
 *      19 kHz     the stereo pilot, at 10% injection
 *   23 – 53 kHz   L−R, double-sideband on a *suppressed* 38 kHz carrier
 *   57 ± 2.4 kHz  RDS
 * </pre>
 *
 * <p>Because the 38 kHz carrier is suppressed it has to be rebuilt from the pilot, which
 * {@link PilotTracker} does. This class just mixes the multiplex down against it and filters.
 *
 * <p>Emits only the difference signal; the caller owns the sum path and the L/R matrix. That split
 * keeps this class testable in isolation and lets the sum path stay exactly what mono uses, so a
 * mono broadcast is not a special case — it is simply the pilot never locking.
 *
 * <p>Stateful across blocks. Not thread-safe.
 */
public final class StereoDecoder {

    private final FirFilter differenceLowPass;
    private final float[] mixed;

    /**
     * @param sumPathTaps the low-pass the caller's sum path uses — the very same taps, not a matching
     *     design. The two paths must reach the matrix with the same delay and the same sample count,
     *     and this class used to rebuild its filter from its own arithmetic, which agreed with the sum
     *     path's only because 24 kHz minus a 15 kHz cutoff happened to equal the 9 kHz transition
     *     written there. A filter on one path only is the mistake that quietly narrows the image.
     * @param decimation the sum path's decimation, multiplex rate over audio rate
     * @param maxInput largest multiplex block that will be handed to {@link #decodeDifference}
     */
    public StereoDecoder(float[] sumPathTaps, int decimation, int maxInput) {
        if (decimation < 1) {
            throw new IllegalArgumentException("decimation must be >= 1, got " + decimation);
        }
        this.differenceLowPass = new FirFilter(sumPathTaps, decimation);
        this.mixed = new float[maxInput];
    }

    /** Safe output size for a multiplex block of {@code inputCount} samples. */
    public int outputCapacity(int inputCount) {
        return differenceLowPass.outputCapacity(inputCount);
    }

    /**
     * Mixes the difference channel down to baseband using the tracker's 38 kHz reference.
     *
     * @return the number of samples written to {@code difference}, at the audio rate
     */
    public int decodeDifference(PilotTracker tracker, float[] difference) {
        int count = tracker.count();
        float[] aligned = tracker.alignedMpx();
        float[] subcarrier = tracker.subcarrier38();

        for (int n = 0; n < count; n++) {
            // Mixing down by 2*cos(38 kHz) leaves the difference signal at its original amplitude;
            // the factor of two undoes the halving inherent in the product-to-sum identity.
            mixed[n] = aligned[n] * 2f * subcarrier[n];
        }
        return differenceLowPass.filter(mixed, count, difference);
    }

    public void reset() {
        differenceLowPass.reset();
    }
}
