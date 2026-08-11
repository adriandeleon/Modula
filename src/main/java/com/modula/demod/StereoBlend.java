package com.modula.demod;

/**
 * How much of the difference channel to mix in — the stereo blend every real receiver has.
 *
 * <p>Switching between full stereo and mono on a lock flag is the wrong shape for a signal that is
 * marginal, and it produced the worst failure this receiver has had: a detector level hovering at its
 * threshold flipped the audio path several times a second, which is heard as spottiness rather than as
 * a stereo decision. Hysteresis stops the flapping, but it only moves the cliff — on either side of it
 * the listener still gets the full 20 dB of extra noise that stereo costs, or none of the separation.
 *
 * <p>So the difference channel is scaled instead. {@code left = sum + blend·difference},
 * {@code right = sum − blend·difference}: at 1.0 that is the ordinary stereo matrix, at 0.0 it is exactly
 * mono, and in between the image narrows smoothly. Noise in the difference channel is attenuated by the
 * same factor, which is the whole point — a weak station trades separation for quiet, progressively,
 * rather than at a threshold.
 *
 * <p><b>Driven by multiplex quieting, not by RF power.</b> Power measures the front end (and with any AGC
 * running, measures the AGC); quieting measures how well the carrier has suppressed the discriminator's
 * noise, which is what actually predicts whether the difference channel is usable. It is the same figure
 * seek thresholds on and the status line reports, so what is heard and what is displayed cannot disagree.
 *
 * <p><b>The blend seeds rather than ramping on first lock.</b> There is nothing to smooth toward until a
 * pilot exists, and by the time the loop has locked — around 100 ms — the channel filters have long
 * settled, so the first measurement is trustworthy. Ramping up from zero instead would fade in over the
 * time constant, which is both audible on every retune and enough to pull a separation measurement below
 * its floor. Afterwards every change is smoothed, which is what the time constant is for.
 *
 * <p>Pure and allocation-free, stateful across blocks. Updated once per block, not per sample: at a
 * 0.3 s time constant and 13.6 ms blocks a single step moves the factor by about 4% of the remaining
 * error, far too little to hear as a step.
 */
public final class StereoBlend {

    /**
     * Quieting at or above which the image is fully open, in dB.
     *
     * <p>{@code DemodChain}'s calibration puts a solid station near 24, so 20 reaches full stereo on
     * anything genuinely strong without demanding the best possible signal.
     */
    public static final double FULL_STEREO_QUIETING_DB = 20.0;

    /**
     * Quieting at or below which the output is mono, in dB.
     *
     * <p>8 is a barely usable station on the same calibration, and stereo costs about 20 dB of noise
     * floor — so below this the separation is not worth what it does to the hiss.
     */
    public static final double MONO_QUIETING_DB = 8.0;

    /** How quickly the blend follows a change in signal quality. */
    private static final double TAU_SECONDS = 0.3;

    private final double smoothing;

    private double blend;
    private boolean seeded;

    /**
     * @param updatesPerSecond how often {@link #update} will be called — the block rate
     */
    public StereoBlend(double updatesPerSecond) {
        if (!(updatesPerSecond > 0.0)) {
            throw new IllegalArgumentException("updatesPerSecond must be > 0, got " + updatesPerSecond);
        }
        this.smoothing = Math.min(1.0, 1.0 / (TAU_SECONDS * updatesPerSecond));
    }

    /**
     * Where the blend should end up for a given signal, ignoring smoothing.
     *
     * <p>Pure, so the ladder is testable without driving a chain.
     *
     * @param quietingDb see {@code DemodChain.quietingDb}; {@code NaN} is treated as no signal
     * @param pilotLocked whether there is a pilot to derive the subcarrier from at all
     * @param stereoEnabled the listener's own mono override
     */
    public static double targetFor(double quietingDb, boolean pilotLocked, boolean stereoEnabled) {
        if (!pilotLocked || !stereoEnabled) {
            return 0.0;
        }
        // Written as a positive test so NaN falls through to mono rather than to a comparison that
        // happens to be false either way.
        if (!(quietingDb > MONO_QUIETING_DB)) {
            return 0.0;
        }
        if (quietingDb >= FULL_STEREO_QUIETING_DB) {
            return 1.0;
        }
        return (quietingDb - MONO_QUIETING_DB) / (FULL_STEREO_QUIETING_DB - MONO_QUIETING_DB);
    }

    /** Advances the blend one block toward what this signal deserves, and returns it. */
    public double update(double quietingDb, boolean pilotLocked, boolean stereoEnabled) {
        double target = targetFor(quietingDb, pilotLocked, stereoEnabled);
        if (!seeded && target > 0.0) {
            // First real measurement: adopt it rather than fading up to it. See the class notes.
            blend = target;
            seeded = true;
        } else {
            blend += smoothing * (target - blend);
        }
        return blend;
    }

    /** The current factor, without advancing it. */
    public double blend() {
        return blend;
    }

    /** Clears the blend, so a retune starts mono and opens up once the new station's pilot is tracked. */
    public void reset() {
        blend = 0.0;
        seeded = false;
    }
}
