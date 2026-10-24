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

    /** Reported for a block with no envelope at all — nothing there, so as empty as a channel gets. */
    private static final double SILENT_RIPPLE_DB = 0.0;

    private final float trackingRate;
    private float carrier;
    private double rippleDb = SILENT_RIPPLE_DB;

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
        double sum = 0.0;
        double sumOfSquares = 0.0;
        for (int n = 0; n < count; n++) {
            float envelope = (float) Math.hypot(i[n], q[n]);
            level += trackingRate * (envelope - level);
            out[n] = envelope / Math.max(level, MIN_CARRIER) - 1f;
            sum += envelope;
            sumOfSquares += (double) envelope * envelope;
        }
        carrier = level;

        if (count > 0 && sum > 0.0) {
            double mean = sum / count;
            double variance = Math.max(sumOfSquares / count - mean * mean, 0.0);
            rippleDb = variance > 0.0 ? 10.0 * Math.log10(variance / (mean * mean)) : -120.0;
        }
    }

    /**
     * How much this block's envelope wandered, relative to its mean, in dB — <b>lower means a
     * steadier carrier, i.e. a station</b>.
     *
     * <p>AM's answer to the FM path's multiplex noise, for seek. Noise alone has a Rayleigh
     * envelope, whose spread is a fixed share of its mean whatever the noise level: about −5.6 dB.
     * A carrier holds the envelope up and only the programme moves it, so a station whose modulation
     * averages 30% rms sits near −10.5, and a quiet one far lower. A ratio rather than a level, so it does
     * not depend on the gain or the antenna. Programme audio counts as ripple, so a station shouting
     * at full modulation reads closer to empty than a quiet one — the threshold leaves room for that.
     */
    public double rippleDb() {
        return rippleDb;
    }

    /** The tracked carrier level, which is the honest measure of an AM station's strength. */
    public double carrierLevel() {
        return carrier;
    }

    public void reset() {
        carrier = 0f;
        rippleDb = SILENT_RIPPLE_DB;
    }
}
