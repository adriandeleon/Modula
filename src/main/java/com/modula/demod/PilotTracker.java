package com.modula.demod;

import com.modula.dsp.Delay;
import com.modula.dsp.FirDesign;
import com.modula.dsp.FirFilter;
import com.modula.dsp.Pll;

/**
 * Recovers the 19 kHz stereo pilot and everything derived from it.
 *
 * <p>The pilot is shared infrastructure, not a stereo detail: the standard defines the 38 kHz stereo
 * subcarrier as its <b>second</b> harmonic and the 57 kHz RDS subcarrier as its <b>third</b>, both
 * phase-locked to it. Both of those carriers are suppressed, so the pilot is the only thing in the
 * signal that says where their phase is. Tracking it once and handing the references to both
 * decoders costs one band-pass and one loop instead of two.
 *
 * <p>The harmonics come from the double- and triple-angle identities rather than more trigonometry,
 * which makes the phase relationships exact by construction and halves the cost.
 *
 * <p>Also owns the alignment {@link Delay}: the band-pass delays the pilot, so the reference
 * describes the multiplex as it was that many samples ago, and {@link #alignedMpx()} is the version
 * of the signal that reference actually belongs to. Anything mixing against these carriers must use
 * it, and any path bypassing this class must be delayed by {@link #groupDelaySamples()} to match.
 *
 * <p>Stateful across blocks. Not thread-safe.
 */
public final class PilotTracker {

    public static final double PILOT_HZ = 19_000.0;

    private static final double BAND_LOW_HZ = 17_000.0;
    private static final double BAND_HIGH_HZ = 21_000.0;
    private static final double BAND_TRANSITION_HZ = 4_000.0;

    /**
     * Loop bandwidth. Narrow enough to ignore programme leakage past the band-pass, wide enough to
     * follow a drifting transmitter — but the binding constraint is <b>acquisition time</b>, which is
     * user-visible on every retune.
     *
     * <p>Measured pull-in against a 30 Hz offset: at 20 Hz the loop is still ringing at 500 ms
     * (+6.9 Hz) and only settles near 1 s, so stereo would engage a full second after tuning. At
     * 50 Hz it is within 3 Hz by 100 ms and locked by then. At 100 Hz acquisition is essentially
     * immediate but the loop starts admitting noise. 50 Hz is the compromise.
     */
    private static final double LOOP_BANDWIDTH_HZ = 50.0;

    private static final double LOCK_RANGE_HZ = 100.0;

    private final FirFilter bandPass;
    private final Delay alignment;
    private final Pll pll;

    private final float[] pilot;
    private final float[] aligned;
    private final float[] second;
    private final float[] third;
    private final float[] thirdQuadrature;

    private int count;

    public PilotTracker(double ifRate, int maxInput) {
        float[] taps = FirDesign.bandPass(
                FirDesign.tapsForTransition(BAND_TRANSITION_HZ / ifRate), BAND_LOW_HZ / ifRate, BAND_HIGH_HZ / ifRate);
        this.bandPass = new FirFilter(taps, 1);
        this.alignment = new Delay((taps.length - 1) / 2);
        this.pll = new Pll(ifRate, PILOT_HZ, LOOP_BANDWIDTH_HZ, LOCK_RANGE_HZ);

        this.pilot = new float[maxInput];
        this.aligned = new float[maxInput];
        this.second = new float[maxInput];
        this.third = new float[maxInput];
        this.thirdQuadrature = new float[maxInput];
    }

    /** Tracks one multiplex block, filling the aligned signal and every derived carrier. */
    public void process(float[] mpx, int count) {
        this.count = count;
        bandPass.filter(mpx, count, pilot);
        alignment.process(mpx, count, aligned);

        for (int n = 0; n < count; n++) {
            pll.advance(pilot[n]);
            double cos = pll.cosOut();
            double sin = pll.sinOut();

            // The loop locks its cosine to the pilot, so against a pilot of sin(ωt) its phase is
            // θ = ωt − π/2, and the standard's subcarrier sin(2ωt) = −sin(2θ) = −2·sinθ·cosθ.
            second[n] = (float) (-2.0 * sin * cos);
            // cos(3θ) = 4cos³θ − 3cosθ, sin(3θ) = 3sinθ − 4sin³θ
            third[n] = (float) (4.0 * cos * cos * cos - 3.0 * cos);
            thirdQuadrature[n] = (float) (3.0 * sin - 4.0 * sin * sin * sin);
        }
    }

    /** Samples valid in the arrays below, from the last {@link #process}. */
    public int count() {
        return count;
    }

    /** The multiplex, delayed to match the recovered carriers. Mix against this, never the raw input. */
    public float[] alignedMpx() {
        return aligned;
    }

    /**
     * sin(2ωt) for a pilot of sin(ωt) — the 38 kHz stereo subcarrier, phased as the standard defines
     * it: crossing zero with positive slope at every such crossing of the pilot (FCC §73.322, ITU-R
     * BS.450).
     *
     * <p><b>This was cos(2θ) — the "obvious" second harmonic of the loop's cosine — and that is in
     * quadrature with the real subcarrier</b>, because the loop's cosine sits a quarter-cycle behind
     * a sine pilot. Every synthetic test passed, since the test signals were built to the same
     * convention; on air the difference channel came out as noise, so the STEREO badge lit over
     * near-mono audio. Off-air captures showed the difference channel's energy 8–9 dB stronger on
     * the axis this now uses. {@code StereoDecoderTest} pins the zero-crossing rule directly.
     */
    public float[] subcarrier38() {
        return second;
    }

    /**
     * cos(3θ) = −sin(3ωt) — the 57 kHz RDS subcarrier in phase with the pilot's third harmonic. The
     * sign is immaterial: RDS is differentially coded, so carrier polarity cannot corrupt it.
     */
    public float[] subcarrier57() {
        return third;
    }

    /**
     * sin(3θ) = cos(3ωt) — the quadrature 57 kHz reference.
     *
     * <p>Needed because the standard allows the RDS subcarrier to be locked to the pilot's third
     * harmonic <b>either in phase or in quadrature</b>, and transmitters differ. Demodulating both
     * and keeping whichever carries more energy resolves it without a second loop.
     */
    public float[] subcarrier57Quadrature() {
        return thirdQuadrature;
    }

    /** Whether a pilot is present and tracked — i.e. whether this station is broadcasting in stereo. */
    public boolean isLocked() {
        return pll.isLocked();
    }

    public double pilotHz() {
        return pll.frequencyHz();
    }

    /** Samples of delay imposed here; any path bypassing this class must be delayed to match. */
    public int groupDelaySamples() {
        return alignment.samples();
    }

    public void reset() {
        bandPass.reset();
        alignment.reset();
        pll.reset();
        count = 0;
    }
}
