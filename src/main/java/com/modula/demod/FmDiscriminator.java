package com.modula.demod;

/**
 * FM demodulation by phase differentiation: the argument of {@code conj(previous) * current} is the
 * phase advance per sample, which is proportional to instantaneous frequency.
 *
 * <p>The output is scaled so that full deviation maps to ±1.0, making it independent of the IF
 * sample rate — swap the decimation chain and the audio level does not move.
 *
 * <p>Uses a real {@link Math#atan2}, deliberately. The cheap {@code (i·q' − q·i')/(i²+q²)}
 * approximation only holds for small phase steps, and broadcast FM at a 240 kHz IF reaches ~2 rad
 * per sample at full deviation — well outside where it is accurate. {@code atan2} will dominate a
 * profile of this chain; a polynomial approximation is the known fix, but measure first.
 *
 * <p>Stateful across blocks: carries the previous sample.
 */
public final class FmDiscriminator {

    /** Peak deviation of broadcast FM, in Hz. */
    public static final double BROADCAST_DEVIATION_HZ = 75_000.0;

    private final float gain;
    private float prevI;
    private float prevQ;

    public FmDiscriminator(double sampleRate, double maxDeviationHz) {
        if (!(sampleRate > 0.0)) {
            throw new IllegalArgumentException("sampleRate must be > 0, got " + sampleRate);
        }
        if (!(maxDeviationHz > 0.0)) {
            throw new IllegalArgumentException("maxDeviationHz must be > 0, got " + maxDeviationHz);
        }
        this.gain = (float) (sampleRate / (2.0 * Math.PI * maxDeviationHz));
    }

    /**
     * Demodulates {@code count} complex samples into {@code out}, which must hold at least
     * {@code count} floats. The result is the FM multiplex signal: mono sum below 15 kHz, the 19 kHz
     * stereo pilot, the 38 kHz difference channel and the 57 kHz RDS subcarrier above it.
     */
    public void demodulate(float[] i, float[] q, int count, float[] out) {
        float li = prevI;
        float lq = prevQ;
        float g = gain;
        for (int n = 0; n < count; n++) {
            float ci = i[n];
            float cq = q[n];
            // conj(prev) * current
            float re = li * ci + lq * cq;
            float im = li * cq - lq * ci;
            out[n] = (float) Math.atan2(im, re) * g;
            li = ci;
            lq = cq;
        }
        prevI = li;
        prevQ = lq;
    }

    public void reset() {
        prevI = 0f;
        prevQ = 0f;
    }
}
