package com.modula.demod;

/**
 * Envelope detection for amplitude modulation.
 *
 * <p>The magnitude of the complex baseband <i>is</i> the transmitted envelope, so the demodulator
 * proper is one {@code hypot} per sample. Everything interesting is what happens next.
 *
 * <p><b>The carrier is removed by dividing rather than subtracting.</b> An AM signal is
 * {@code carrier × (1 + m·audio)}, so the envelope's mean is the carrier and the audio rides on it
 * proportionally. Subtracting the mean leaves audio whose loudness tracks signal strength — a strong
 * station blares and a weak one whispers, and every fade is a volume change. Dividing by it recovers
 * the modulation itself, which is both correct and a working AGC for free.
 *
 * <p>The carrier estimate is a slow one-pole average, slow enough to pass the lowest programme
 * content (a few tens of hertz) while still following a fade.
 *
 * <p>Stateful across blocks; see the note on {@code dsp.FirFilter}.
 */
public final class AmDemodulator {

    /** Below the lowest audio anyone broadcasts, above the rate a fade happens at. */
    private static final double CARRIER_TRACK_HZ = 20.0;

    /** Keeps a dead carrier from dividing the noise up to full scale. */
    private static final float MIN_CARRIER = 1e-4f;

    private final float trackingRate;
    private float carrier;

    public AmDemodulator(double sampleRate) {
        if (!(sampleRate > 0.0)) {
            throw new IllegalArgumentException("sampleRate must be > 0, got " + sampleRate);
        }
        this.trackingRate = (float) (2.0 * Math.PI * CARRIER_TRACK_HZ / sampleRate);
    }

    /**
     * Demodulates {@code count} complex samples into {@code out}, scaled so full modulation is ±1.
     */
    public void demodulate(float[] i, float[] q, int count, float[] out) {
        float level = carrier;
        for (int n = 0; n < count; n++) {
            float envelope = (float) Math.hypot(i[n], q[n]);
            level += trackingRate * (envelope - level);
            out[n] = envelope / Math.max(level, MIN_CARRIER) - 1f;
        }
        carrier = level;
    }

    /** The tracked carrier level, which is the honest measure of an AM station's strength. */
    public double carrierLevel() {
        return carrier;
    }

    public void reset() {
        carrier = 0f;
    }
}
