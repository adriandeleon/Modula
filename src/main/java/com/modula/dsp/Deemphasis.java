package com.modula.dsp;

/**
 * FM de-emphasis: a single-pole IIR low-pass undoing the pre-emphasis applied at the transmitter.
 *
 * <p>75 µs in the Americas and South Korea, 50 µs everywhere else. Skip this entirely and FM sounds
 * harsh and hissy; apply the wrong one and it sounds subtly thin or muffled. It is driven from
 * {@code band.Region} so the choice can never drift out of step with channel spacing.
 *
 * <p>Stateful across blocks — see the note on {@link FirFilter}.
 */
public final class Deemphasis {

    private final float feedback;
    private float y;

    private Deemphasis(float feedback) {
        this.feedback = feedback;
    }

    public static Deemphasis forMicros(double tauMicros, double sampleRate) {
        if (!(tauMicros > 0.0)) {
            throw new IllegalArgumentException("tauMicros must be > 0, got " + tauMicros);
        }
        if (!(sampleRate > 0.0)) {
            throw new IllegalArgumentException("sampleRate must be > 0, got " + sampleRate);
        }
        double x = Math.exp(-1.0 / (sampleRate * tauMicros * 1e-6));
        return new Deemphasis((float) x);
    }

    /** Filters {@code count} samples of {@code buf} in place. */
    public void process(float[] buf, int count) {
        float a = feedback;
        float oneMinusA = 1f - a;
        float prev = y;
        for (int n = 0; n < count; n++) {
            prev = oneMinusA * buf[n] + a * prev;
            buf[n] = prev;
        }
        y = prev;
    }

    public void reset() {
        y = 0f;
    }
}
