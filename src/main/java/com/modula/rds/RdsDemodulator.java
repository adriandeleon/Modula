package com.modula.rds;

import com.modula.demod.PilotTracker;
import com.modula.dsp.FirDesign;
import com.modula.dsp.FirFilter;

/**
 * Recovers the RDS bit stream from the multiplex: 57 kHz down to baseband, then bi-phase symbols.
 *
 * <p>Four things make this awkward, and each is handled explicitly below.
 *
 * <p><b>The carrier is suppressed, and the pilot does not reliably say where it is.</b> The standard
 * locks the 57 kHz subcarrier to the pilot's third harmonic, in phase or in quadrature. This used to
 * take the standard at its word: demodulate both branches, keep whichever carried more energy. Off
 * air, that held for some stations and not others — on 106.9 the subcarrier turned steadily against
 * the pilot's harmonic, about ten degrees a second (an RDS encoder on its own crystal, roughly half a
 * part per million off), so every few seconds it passed between the branches, both lost half their
 * signal, and the choice flapped. The receiver decoded 30 of ~340 groups in 30 seconds and never
 * showed a station name. So the pilot now only gets the subcarrier close, and a Costas loop on the
 * complex baseband tracks the rest: 340 groups, the name in under a second. A station that does lock
 * its RDS to the pilot simply leaves the loop sitting still.
 *
 * <p><b>The symbol rate does not divide the sample rate.</b> 1187.5 bps into 24 kHz is 20.21 samples
 * per symbol, so symbol timing needs a fractional clock rather than a counter.
 *
 * <p><b>The data is bi-phase coded</b>, which is what makes timing recovery tractable: every symbol
 * has a transition at its midpoint regardless of the data, so that transition is a free clock
 * reference. The detector integrates each half-symbol and takes the difference, and the timing loop
 * steers the mid-symbol sample onto the zero crossing.
 *
 * <p><b>That same structure has a false lock half a symbol away.</b> Between two symbols there is a
 * transition too whenever they are equal, so a loop that starts with its boundaries on the
 * mid-symbol crossings can settle there: its error averages to zero and it never leaves. Whether it
 * starts there is luck — the same 94.9 capture decoded 214 groups or none depending only on where in
 * it decoding began. At the right phase the two half-symbol sums are always opposite; half a symbol
 * off, they are as often equal as opposite. When their sum rivals their difference, the clock slips
 * half a symbol — but only while block sync is lost, because valid CRCs prove the timing is right.
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

    /**
     * Costas loop gain per baseband sample — a bandwidth of roughly 15 Hz. Measured off air: 2e-3 to
     * 1.6e-2 all decode a rotating carrier fully; 1e-3 starts losing groups. Taken from the middle.
     */
    private static final double CARRIER_GAIN = 4.0e-3;

    /**
     * How far the loop may pull the carrier from the pilot's harmonic. An encoder on its own crystal
     * is off by a fraction of a hertz; bounding the pull keeps the loop from wandering into a false
     * lock offset by a fraction of the symbol rate, which a squaring loop on bi-phase data can.
     */
    private static final double CARRIER_PULL_HZ = 1.0;

    /** Averaging for the loop's amplitude normalisation, in baseband samples. */
    private static final double CARRIER_POWER_SMOOTHING = 1.0e-3;

    /** Half-symbol false lock: averaging, in symbols, of the two half-symbol measures. */
    private static final double SLIP_AVERAGING = 64.0;

    /** ...the share of the difference their sum must reach to call the timing half a symbol off... */
    private static final double SLIP_RATIO = 0.5;

    /** ...and the symbols to wait after a slip before judging again, so one cannot chase another. */
    private static final int SLIP_DWELL = 256;

    private final FirFilter lowPassInphase;
    private final FirFilter lowPassQuadrature;
    private final float[] mixedInphase;
    private final float[] mixedQuadrature;
    private final float[] basebandInphase;
    private final float[] basebandQuadrature;
    private final float[] tracked;
    private final BitSink sink;
    private final double maxCarrierStep;

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

    private double carrierPhase;
    private double carrierStep;
    private double carrierPower;

    private double halfDifference;
    private double halfSum;
    private int symbolsSinceSlip;
    private boolean timingTrusted;

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
        this.tracked = new float[basebandCapacity];

        double basebandRate = ifRate / DECIMATION;
        this.maxCarrierStep = 2.0 * Math.PI * CARRIER_PULL_HZ / basebandRate;
        this.nominalStep = SYMBOL_RATE / basebandRate;
        this.minStep = nominalStep * (1.0 - RATE_TOLERANCE);
        this.maxStep = nominalStep * (1.0 + RATE_TOLERANCE);
        this.step = nominalStep;
    }

    /** Demodulates one multiplex block, pushing any recovered bits to the sink. */
    public void process(PilotTracker tracker) {
        processBaseband(tracked, recoverBaseband(tracker, tracked));
    }

    /**
     * The carrier half on its own: mixes the subcarrier down against the pilot's third harmonic,
     * tracks what is left of its phase, and writes the real baseband the symbols are read from.
     *
     * <p>Public so that {@code scripts/MakeRdsFixture} distils its fixture through exactly this,
     * rather than through a copy of it — a fixture made by a different front end than the receiver's
     * has already once decoded perfectly in a test while the receiver could not lock to it.
     *
     * @param out at least {@link #basebandCapacity()} long
     * @return the number of baseband samples written, at 24 kHz
     */
    public int recoverBaseband(PilotTracker tracker, float[] out) {
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

        for (int n = 0; n < baseband; n++) {
            double cos = Math.cos(carrierPhase);
            double sin = Math.sin(carrierPhase);
            double re = basebandInphase[n] * cos + basebandQuadrature[n] * sin;
            double im = basebandQuadrature[n] * cos - basebandInphase[n] * sin;

            // Costas: for a two-phase signal re·im is proportional to the phase error whichever way
            // the data points, so it steers without knowing the bits. Normalised by the signal's own
            // power, so the loop's bandwidth does not depend on how loud the subcarrier is.
            double power = re * re + im * im;
            carrierPower =
                    carrierPower == 0.0 ? power : carrierPower + CARRIER_POWER_SMOOTHING * (power - carrierPower);
            double error = carrierPower > 0.0 ? Math.clamp(re * im / carrierPower, -1.0, 1.0) : 0.0;
            carrierStep = Math.clamp(
                    carrierStep + CARRIER_GAIN * CARRIER_GAIN / 4.0 * error, -maxCarrierStep, maxCarrierStep);
            carrierPhase = Math.IEEEremainder(carrierPhase + CARRIER_GAIN * error + carrierStep, 2.0 * Math.PI);

            out[n] = (float) re;
        }
        return baseband;
    }

    /** The size {@link #recoverBaseband}'s output array needs to be. */
    public int basebandCapacity() {
        return tracked.length;
    }

    /**
     * Whether the timing is known good — block sync locked, i.e. CRCs are passing. While it is, the
     * half-symbol check stands down: it is a guess made from signal shape, and valid blocks are
     * proof. Without this a weak station, where the shape is too noisy to read, would be slipped
     * back and forth while it was decoding.
     */
    public void setTimingTrusted(boolean trusted) {
        this.timingTrusted = trusted;
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
        carrierPhase = 0.0;
        carrierStep = 0.0;
        carrierPower = 0.0;
        halfDifference = 0.0;
        halfSum = 0.0;
        symbolsSinceSlip = 0;
        timingTrusted = false;
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
        double together = firstHalf + secondHalf;
        firstHalf = 0.0;
        secondHalf = 0.0;

        // Half a symbol off, the window straddles two symbols and its halves agree as often as not;
        // see the class note. Not while block sync vouches for the timing.
        halfDifference += (Math.abs(symbol) - halfDifference) / SLIP_AVERAGING;
        halfSum += (Math.abs(together) - halfSum) / SLIP_AVERAGING;
        if (++symbolsSinceSlip > SLIP_DWELL && !timingTrusted && halfSum > SLIP_RATIO * halfDifference) {
            phase += 0.5;
            if (phase >= 1.0) {
                phase -= 1.0;
            }
            // The two measures trade places across the slip; swapping them stops the next symbol
            // reading the old averages as evidence for slipping straight back.
            double swap = halfDifference;
            halfDifference = halfSum;
            halfSum = swap;
            symbolsSinceSlip = 0;
        }

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
