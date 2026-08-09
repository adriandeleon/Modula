package com.modula.rds;

import com.modula.demod.PilotTracker;
import com.modula.dsp.FirDesign;
import com.modula.dsp.FirFilter;

/**
 * Recovers the RDS bit stream from the multiplex: 57 kHz down to baseband, then bi-phase symbols.
 *
 * <p>Three things make this awkward, and each is handled explicitly below.
 *
 * <p><b>The carrier is suppressed and its phase is ambiguous.</b> The standard locks the 57 kHz
 * subcarrier to the pilot's third harmonic but allows it to be <em>either in phase or in
 * quadrature</em>, and transmitters differ. So both branches are demodulated and the one carrying
 * more energy wins, with hysteresis so noise cannot make the choice flap.
 *
 * <p><b>The symbol rate does not divide the sample rate.</b> 1187.5 bps into 24 kHz is 20.21 samples
 * per symbol, so symbol timing needs a fractional clock rather than a counter.
 *
 * <p><b>The data is bi-phase coded</b>, which is what makes timing recovery tractable: every symbol
 * has a transition at its midpoint regardless of the data, so that transition is a free clock
 * reference. The detector integrates each half-symbol and takes the difference, and the timing loop
 * steers the mid-symbol sample onto the zero crossing.
 *
 * <p>The bits handed out are already differentially decoded, so the polarity ambiguity inherent in
 * suppressed-carrier demodulation is resolved and the sink can treat them as data.
 *
 * <p>Stateful across blocks. Not thread-safe.
 */
public final class RdsDemodulator {

    /** Receives each recovered data bit. */
    @FunctionalInterface
    public interface BitSink {
        void accept(boolean bit);
    }

    public static final double SYMBOL_RATE = 1187.5;

    /** Baseband rate after decimation; ~20 samples per symbol is ample for timing recovery. */
    private static final int DECIMATION = 10;

    private static final double BASEBAND_CUTOFF_HZ = 2_400.0;
    private static final double BASEBAND_TRANSITION_HZ = 5_600.0;

    /** Bang-bang timing loop gains: a fast phase nudge over a slow rate correction. */
    private static final double PHASE_GAIN = 0.008;

    private static final double RATE_GAIN = 2.0e-6;

    /** How far the symbol clock may be pulled, as a fraction of nominal. Well past any real crystal. */
    private static final double RATE_TOLERANCE = 0.002;

    /** Averaging for the branch-energy comparison, in output samples. */
    private static final double ENERGY_SMOOTHING = 1.0 / 12_000.0;

    /** How much better the other branch must look before switching. */
    private static final double SWITCH_MARGIN = 1.5;

    private final FirFilter lowPassInphase;
    private final FirFilter lowPassQuadrature;
    private final float[] mixedInphase;
    private final float[] mixedQuadrature;
    private final float[] basebandInphase;
    private final float[] basebandQuadrature;
    private final BitSink sink;

    private final double nominalStep;
    private final double minStep;
    private final double maxStep;

    private double phase;
    private double step;
    private double firstHalf;
    private double secondHalf;
    private double midSample;

    private boolean previousSymbol;
    private boolean havePreviousSymbol;

    private double energyInphase;
    private double energyQuadrature;
    private boolean useQuadrature;

    public RdsDemodulator(double ifRate, int maxInput, BitSink sink) {
        this.sink = sink;

        float[] taps = FirDesign.lowPass(
                FirDesign.tapsForTransition(BASEBAND_TRANSITION_HZ / ifRate), BASEBAND_CUTOFF_HZ / ifRate);
        this.lowPassInphase = new FirFilter(taps, DECIMATION);
        this.lowPassQuadrature = new FirFilter(taps, DECIMATION);

        this.mixedInphase = new float[maxInput];
        this.mixedQuadrature = new float[maxInput];
        int basebandCapacity = lowPassInphase.outputCapacity(maxInput);
        this.basebandInphase = new float[basebandCapacity];
        this.basebandQuadrature = new float[basebandCapacity];

        double basebandRate = ifRate / DECIMATION;
        this.nominalStep = SYMBOL_RATE / basebandRate;
        this.minStep = nominalStep * (1.0 - RATE_TOLERANCE);
        this.maxStep = nominalStep * (1.0 + RATE_TOLERANCE);
        this.step = nominalStep;
    }

    /** Demodulates one multiplex block, pushing any recovered bits to the sink. */
    public void process(PilotTracker tracker) {
        int count = tracker.count();
        float[] aligned = tracker.alignedMpx();
        float[] inphase = tracker.subcarrier57();
        float[] quadrature = tracker.subcarrier57Quadrature();

        for (int n = 0; n < count; n++) {
            float sample = aligned[n] * 2f;
            mixedInphase[n] = sample * inphase[n];
            mixedQuadrature[n] = sample * quadrature[n];
        }

        int baseband = lowPassInphase.filter(mixedInphase, count, basebandInphase);
        lowPassQuadrature.filter(mixedQuadrature, count, basebandQuadrature);

        chooseBranch(baseband);
        processBaseband(useQuadrature ? basebandQuadrature : basebandInphase, baseband);
    }

    /**
     * Recovers symbols from an already-demodulated baseband, skipping the 57 kHz mix.
     *
     * <p>The two halves are genuinely different jobs — carrier recovery and symbol recovery — and
     * separating them lets a recorded baseband be replayed through the real symbol detector, which is
     * how the off-air golden-file test works without committing megabytes of multiplex.
     *
     * <p>Scale-independent: the detector takes the sign of the half-symbol difference and the timing
     * loop is bang-bang, so a normalised recording behaves exactly as the live signal does.
     */
    public void processBaseband(float[] samples, int count) {
        for (int n = 0; n < count; n++) {
            advance(samples[n]);
        }
    }

    /** Which branch the subcarrier turned out to be on; exposed for diagnostics. */
    public boolean isUsingQuadrature() {
        return useQuadrature;
    }

    /** The symbol clock's recovered rate in bits per second — should sit very near 1187.5. */
    public double symbolRateHz() {
        return SYMBOL_RATE * step / nominalStep;
    }

    public void reset() {
        lowPassInphase.reset();
        lowPassQuadrature.reset();
        phase = 0.0;
        step = nominalStep;
        firstHalf = 0.0;
        secondHalf = 0.0;
        midSample = 0.0;
        havePreviousSymbol = false;
        energyInphase = 0.0;
        energyQuadrature = 0.0;
        useQuadrature = false;
    }

    /**
     * The subcarrier sits entirely on one branch or the other, so their relative energy says which.
     * Hysteresis keeps a marginal signal from flapping between them and destroying symbol timing
     * every time it does.
     */
    private void chooseBranch(int count) {
        for (int n = 0; n < count; n++) {
            energyInphase += ENERGY_SMOOTHING * (Math.abs(basebandInphase[n]) - energyInphase);
            energyQuadrature += ENERGY_SMOOTHING * (Math.abs(basebandQuadrature[n]) - energyQuadrature);
        }
        if (useQuadrature) {
            if (energyInphase > energyQuadrature * SWITCH_MARGIN) {
                useQuadrature = false;
            }
        } else if (energyQuadrature > energyInphase * SWITCH_MARGIN) {
            useQuadrature = true;
        }
    }

    /** One baseband sample through the integrate-and-dump detector and the timing loop. */
    private void advance(float sample) {
        double before = phase;
        phase += step;

        if (before < 0.5) {
            firstHalf += sample;
            if (phase >= 0.5) {
                // The mid-symbol transition: at correct timing this sample sits on the zero crossing.
                midSample = sample;
            }
        } else {
            secondHalf += sample;
        }

        if (phase < 1.0) {
            return;
        }
        phase -= 1.0;

        double symbol = firstHalf - secondHalf;
        firstHalf = 0.0;
        secondHalf = 0.0;

        // Bang-bang detector: which side of the crossing the mid sample fell on says whether the
        // clock is early or late. Dimensionless, so it needs no scaling against signal level.
        double error = Math.signum(midSample) * Math.signum(symbol);
        phase -= PHASE_GAIN * error;
        step = Math.clamp(step - RATE_GAIN * error, minStep, maxStep);

        // A slightly negative phase is meaningful and must be left alone: it says this symbol ended
        // early, so the next one starts a little later. Wrapping it up to ~1.0 instead — the obvious
        // defensive move — makes the very next sample cross the boundary again and emit a spurious
        // symbol, which measured as 14% more bits than were ever transmitted.

        boolean current = symbol > 0.0;
        if (havePreviousSymbol) {
            // RDS is differentially encoded, which is what makes the 180-degree carrier ambiguity
            // harmless: only the change between symbols carries data.
            sink.accept(current != previousSymbol);
        }
        previousSymbol = current;
        havePreviousSymbol = true;
    }
}
