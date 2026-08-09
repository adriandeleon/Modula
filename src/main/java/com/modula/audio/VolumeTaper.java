package com.modula.audio;

/**
 * The curve between where the slider sits and what the gain actually is.
 *
 * <p>A slider wired straight to amplitude feels wrong, and the decibel readout makes it visible:
 * halfway along is only −6 dB, which is barely quieter, while everything genuinely useful is crammed
 * into the bottom tenth of the travel. Loudness is perceived roughly logarithmically, so the control
 * has to be too.
 *
 * <p>Squared, which puts half travel at about −12 dB. That is close to the −10 dB usually described
 * as "half as loud", so the middle of the slider lands near the middle of the perceived range. A
 * cubic taper is the other common choice and spreads the bottom end further, at the cost of making
 * the top half feel coarse on a control this short.
 *
 * <p>Pure, and exactly invertible, which is what lets the stored setting keep meaning gain: a
 * configuration written before the taper existed still loads to the same loudness.
 */
public final class VolumeTaper {

    /** Squared. See the class note for why not 1 (linear) or 3 (cubic). */
    static final double EXPONENT = 2.0;

    private VolumeTaper() {}

    /** The gain for a slider position in 0..1. */
    public static double gain(double position) {
        return Math.pow(Math.clamp(position, 0.0, 1.0), EXPONENT);
    }

    /** Where the slider must sit to produce this gain — the inverse, so nothing drifts on a reload. */
    public static double position(double gain) {
        return Math.pow(Math.clamp(gain, 0.0, 1.0), 1.0 / EXPONENT);
    }
}
