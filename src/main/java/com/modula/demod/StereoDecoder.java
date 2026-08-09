package com.modula.demod;

import com.modula.dsp.FirDesign;
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
     * @param ifRate multiplex sample rate
     * @param audioRate output sample rate; {@code ifRate} must be an integer multiple of it
     * @param audioCutoffHz low-pass applied to the recovered difference signal
     * @param maxInput largest multiplex block that will be handed to {@link #decodeDifference}
     */
    public StereoDecoder(double ifRate, double audioRate, double audioCutoffHz, int maxInput) {
        int decimation = (int) Math.round(ifRate / audioRate);
        if (Math.abs(decimation * audioRate - ifRate) > 1e-6) {
            throw new IllegalArgumentException(
                    "ifRate %f is not an integer multiple of audioRate %f".formatted(ifRate, audioRate));
        }

        // Matches the sum path's filter, so the two arrive at the matrix with the same delay and the
        // same sample count.
        float[] audioTaps = FirDesign.lowPass(
                FirDesign.tapsForTransition((audioRate / 2.0 - audioCutoffHz) / ifRate), audioCutoffHz / ifRate);
        this.differenceLowPass = new FirFilter(audioTaps, decimation);
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
