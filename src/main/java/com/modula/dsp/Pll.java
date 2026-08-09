package com.modula.dsp;

/**
 * A type-2 phase-locked loop that tracks a sinusoid of roughly known frequency.
 *
 * <p>Used to recover the 19 kHz stereo pilot. The pilot is the only reference for the 38 kHz
 * subcarrier the difference channel rides on, and that subcarrier is <b>suppressed</b> — nothing in
 * the signal marks its phase directly. Get the phase wrong and the difference channel folds into
 * itself: separation collapses and the stereo image smears rather than failing outright, which is
 * why the tests here assert on phase error rather than merely on "did it lock".
 *
 * <p>Structure is the textbook one: an NCO, a multiplier phase detector, and a proportional-integral
 * loop filter. The loop bandwidth is the single tuning knob — narrow enough to ignore programme
 * content leaking past the pilot band-pass, wide enough to follow a drifting transmitter.
 *
 * <p>Pure and allocation-free, but stateful across blocks; see the note on {@link FirFilter}.
 */
public final class Pll {

    /** Time constant of the lock detector's averaging, in seconds. */
    private static final double LOCK_TAU_SECONDS = 0.05;

    /**
     * A locked 19 kHz pilot at the standard 10% injection produces a detector level near 0.05. This
     * sits well below that and well above what programme leakage or noise sustains.
     */
    private static final double LOCK_THRESHOLD = 0.01;

    private final double sampleRate;
    private final double centreFrequency;
    private final double minFrequency;
    private final double maxFrequency;
    private final double alpha;
    private final double beta;
    private final double lockSmoothing;

    private double phase;
    private double frequency;
    private double lockLevel;
    private double cosOut = 1.0;
    private double sinOut;

    /**
     * @param sampleRate samples per second
     * @param centreHz free-running frequency, where the NCO starts and rests
     * @param loopBandwidthHz how fast the loop reacts; ~20 Hz suits a broadcast pilot
     * @param lockRangeHz how far either side of centre the NCO may be pulled
     */
    public Pll(double sampleRate, double centreHz, double loopBandwidthHz, double lockRangeHz) {
        if (!(sampleRate > 0.0)) {
            throw new IllegalArgumentException("sampleRate must be > 0, got " + sampleRate);
        }
        if (!(centreHz > 0.0) || centreHz >= sampleRate / 2.0) {
            throw new IllegalArgumentException("centreHz must be in (0, Nyquist), got " + centreHz);
        }
        if (!(loopBandwidthHz > 0.0)) {
            throw new IllegalArgumentException("loopBandwidthHz must be > 0, got " + loopBandwidthHz);
        }

        this.sampleRate = sampleRate;
        this.centreFrequency = 2.0 * Math.PI * centreHz / sampleRate;
        this.minFrequency = 2.0 * Math.PI * (centreHz - lockRangeHz) / sampleRate;
        this.maxFrequency = 2.0 * Math.PI * (centreHz + lockRangeHz) / sampleRate;
        this.frequency = centreFrequency;

        // Standard second-order loop coefficients for a critically-damped response.
        double loopBandwidth = 2.0 * Math.PI * loopBandwidthHz / sampleRate;
        double damping = Math.sqrt(2.0) / 2.0;
        double denominator = 1.0 + 2.0 * damping * loopBandwidth + loopBandwidth * loopBandwidth;
        this.alpha = 4.0 * damping * loopBandwidth / denominator;
        this.beta = 4.0 * loopBandwidth * loopBandwidth / denominator;

        this.lockSmoothing = 1.0 / (LOCK_TAU_SECONDS * sampleRate);
    }

    /**
     * Advances the loop by one input sample.
     *
     * @return the NCO phase used for this sample, in radians
     */
    public double advance(double sample) {
        double phaseNow = phase;
        double cos = Math.cos(phaseNow);
        double sin = Math.sin(phaseNow);
        cosOut = cos;
        sinOut = sin;

        // Multiplier phase detector. Averaged by the loop, sample * -sin(phase) is proportional to
        // the phase difference, positive when the NCO lags the input.
        double error = -sample * sin;

        // In-phase product: near zero while hunting, near half the pilot amplitude once locked.
        lockLevel += lockSmoothing * (sample * cos - lockLevel);

        frequency = Math.clamp(frequency + beta * error, minFrequency, maxFrequency);
        phase = wrap(phaseNow + frequency + alpha * error);
        return phaseNow;
    }

    /** Cosine of the NCO phase at the last {@link #advance}. */
    public double cosOut() {
        return cosOut;
    }

    /** Sine of the NCO phase at the last {@link #advance}. */
    public double sinOut() {
        return sinOut;
    }

    /**
     * Cosine of <b>twice</b> the NCO phase, via the double-angle identity.
     *
     * <p>This is the 38 kHz stereo subcarrier reconstructed from the 19 kHz pilot, and it is exactly
     * what the standard defines the subcarrier to be — the pilot's second harmonic, in phase. Doing
     * it by identity rather than a second {@link Math#cos} both halves the trigonometry and makes
     * the phase relationship exact by construction rather than by arithmetic that could drift.
     */
    public double cosDoubleOut() {
        return 2.0 * cosOut * cosOut - 1.0;
    }

    /** Whether the loop is tracking a real signal rather than hunting. */
    public boolean isLocked() {
        return lockLevel > LOCK_THRESHOLD;
    }

    /** The lock detector's current level; roughly half the input amplitude once locked. */
    public double lockLevel() {
        return lockLevel;
    }

    /** The NCO's current frequency in Hz — useful for asserting convergence in tests. */
    public double frequencyHz() {
        return frequency * sampleRate / (2.0 * Math.PI);
    }

    public void reset() {
        phase = 0.0;
        frequency = centreFrequency;
        lockLevel = 0.0;
        cosOut = 1.0;
        sinOut = 0.0;
    }

    private static double wrap(double radians) {
        double x = radians;
        while (x > Math.PI) {
            x -= 2.0 * Math.PI;
        }
        while (x < -Math.PI) {
            x += 2.0 * Math.PI;
        }
        return x;
    }
}
