package com.modula.radio;

import com.modula.band.BandPlan;
import com.modula.band.Modulation;
import com.modula.band.Region;
import com.modula.demod.AmDemodulator;
import com.modula.demod.FmDiscriminator;
import com.modula.demod.PilotTracker;
import com.modula.demod.StereoBlend;
import com.modula.demod.StereoDecoder;
import com.modula.dsp.Deemphasis;
import com.modula.dsp.Delay;
import com.modula.dsp.Fft;
import com.modula.dsp.FirDesign;
import com.modula.dsp.FirFilter;
import com.modula.dsp.PowerMeter;
import com.modula.rds.RdsReceiver;
import com.modula.rds.StationInfo;
import com.modula.source.SampleFormat;

/**
 * The assembled WBFM receive chain.
 *
 * <pre>
 *   u8 IQ @ 1.2 MSPS
 *    └ deinterleave + DC-correct  →  float i[], q[]
 *      └ complex low-pass, ÷5     →  240 kHz  (channel select)
 *        └ FM discriminator       →  240 kHz  multiplex, real
 *          ├ low-pass 15 kHz, ÷5  →  48 kHz   sum        (L+R)
 *          └ StereoDecoder        →  48 kHz   difference (L−R)
 *            └ matrix + de-emphasis per channel  →  interleaved stereo PCM
 * </pre>
 *
 * <p>The rates are chosen so both decimations are exactly ÷5, keeping every stage integer. 240 kHz
 * is also wide enough to carry the 57 kHz RDS subcarrier, so RDS can be added without touching the
 * front end.
 *
 * <p>Output is <b>always</b> two-channel interleaved, even for a mono broadcast (where L and R are
 * simply equal). Switching channel count at runtime would mean reopening the sound card mid-listen,
 * and a mono station is not worth a discontinuity.
 *
 * <p><b>Every buffer is allocated once, in the constructor.</b> The chain is a fixed pipeline rather
 * than a dynamic graph, so all sizes are known up front and no pool is needed. Steady-state
 * allocation is zero, which is what keeps the GC out of the audio path entirely.
 *
 * <p>Not thread-safe: one chain belongs to one {@link RadioEngine} thread.
 *
 * <p>Cost note: the channel filter is the expensive stage — 111 taps over I and Q at 1.2 MSPS is
 * ~266 M multiply-accumulates per second — followed by the pilot band-pass at ~79 M. Both are
 * comfortable on a modern core, and the standard fix if they stop being so is multi-stage decimation
 * rather than the Vector API.
 */
public final class DemodChain {

    public static final int INPUT_RATE = 1_200_000;
    public static final int IF_RATE = 240_000;
    public static final int AUDIO_RATE = 48_000;

    /** Interleaved stereo, so two samples per frame. */
    public static final int CHANNELS = 2;

    /**
     * Bins in the spectrum snapshot. The span is the sample rate — ±600 kHz — which is a fact of the
     * front end rather than a setting, so there is nothing to configure here either.
     */
    public static final int SPECTRUM_BINS = 1024;

    /** Quietest level the spectrum reports, so an empty band has a floor to draw against. */
    public static final double SPECTRUM_FLOOR_DBFS = -90.0;

    /**
     * Multiplex noise on an empty channel, in dBFS — the zero point for {@link #quietingDb}.
     *
     * <p>From the same calibration as {@code SeekPolicy}: empty reads about −6, a barely usable station
     * −14, a solid one −30 or below. <b>Measured against synthesised signals</b>, so it is the number to
     * re-derive against a real band.
     */
    public static final double EMPTY_CHANNEL_NOISE_DBFS = -6.0;

    /** Complex samples per processed block; ~13.6 ms of audio at {@link #INPUT_RATE}. */
    public static final int BLOCK_PAIRS = 16_384;

    private static final int IF_DECIMATION = INPUT_RATE / IF_RATE;
    private static final int AUDIO_DECIMATION = IF_RATE / AUDIO_RATE;

    private static final double CHANNEL_CUTOFF_HZ = 100_000.0;
    private static final double CHANNEL_TRANSITION_HZ = 60_000.0;
    private static final double AUDIO_CUTOFF_HZ = 15_000.0;
    private static final double AUDIO_TRANSITION_HZ = 9_000.0;

    /**
     * Nothing legitimate lives above 60 kHz in the multiplex — the mono sum ends at 15 kHz, the
     * difference channel at 53 kHz, RDS at about 60. So the energy up here is pure discriminator
     * noise, and it is the honest way to tell a real station from an empty channel.
     */
    private static final double NOISE_CUTOFF_HZ = 60_000.0;

    private static final double NOISE_TRANSITION_HZ = 20_000.0;

    /**
     * AM channel half-width. Broadcast AM occupies about 10 kHz total and aviation AM less, so the
     * second stage doubles as the channel filter — there is no wide IF to keep, and no subcarriers
     * above the audio to make room for.
     */
    private static final double AM_CUTOFF_HZ = 5_000.0;

    private static final double AM_TRANSITION_HZ = 19_000.0;

    private final float[] rawI;
    private final float[] rawQ;
    private final float[] ifI;
    private final float[] ifQ;
    private final float[] mpx;
    private final float[] mpxAligned;
    private final float[] noise;
    private final float[] sum;
    private final float[] difference;
    private final float[] left;
    private final float[] right;

    private final FirFilter channelI;
    private final FirFilter channelQ;
    private final FirFilter sumLowPass;
    private final FirFilter noiseHighPass;
    private final Delay sumAlignment;
    private final Modulation modulation;
    private final FirFilter amChannelI;
    private final FirFilter amChannelQ;
    private final float[] amI;
    private final float[] amQ;
    private final AmDemodulator amDemodulator;

    private final FmDiscriminator discriminator;
    private final PilotTracker pilotTracker;
    private final StereoBlend stereoBlend;
    private final StereoDecoder stereoDecoder;
    private final RdsReceiver rdsReceiver;
    private final Deemphasis deemphasisLeft;
    private final Deemphasis deemphasisRight;

    private final float[] spectrumRe = new float[SPECTRUM_BINS];
    private final float[] spectrumIm = new float[SPECTRUM_BINS];
    private final float[] spectrumWindow = new float[SPECTRUM_BINS];

    /**
     * How fast the carrier-offset estimate follows, per block.
     *
     * <p>Over a single 13.6 ms block programme content has plenty of energy low enough to move the raw
     * mean by kilohertz. What is being measured — the dongle's crystal error plus the transmitter's own
     * — does not move at all, so about seven tenths of a second of averaging costs nothing and turns a
     * useless number into a stable one.
     */
    private static final double OFFSET_SMOOTHING = 0.02;

    /** Blocks before the smoothed estimate is worth reporting; below this it is still settling. */
    private static final int OFFSET_SETTLE_BLOCKS = 40;

    private int lastPairs;
    private double offsetEstimate;
    private int offsetBlocks;
    private volatile double signalDbfs = PowerMeter.FLOOR_DBFS;
    private volatile double adcHeadroomDb = Double.NaN;
    private volatile double noiseDbfs = 0.0;
    private volatile double carrierOffsetHz = Double.NaN;
    private volatile boolean pilotLocked;
    private volatile boolean stereoEnabled = true;
    private volatile double stereoBlendFactor;

    public DemodChain(Region region) {
        this(region, BandPlan.fm(region), BLOCK_PAIRS);
    }

    public DemodChain(Region region, BandPlan band) {
        this(region, band, BLOCK_PAIRS);
    }

    public DemodChain(Region region, BandPlan band, int maxPairs) {
        this.modulation = band.modulation();
        if (maxPairs < 1) {
            throw new IllegalArgumentException("maxPairs must be >= 1, got " + maxPairs);
        }

        float[] channelTaps = FirDesign.lowPass(
                FirDesign.tapsForTransition(CHANNEL_TRANSITION_HZ / INPUT_RATE), CHANNEL_CUTOFF_HZ / INPUT_RATE);
        float[] audioTaps = FirDesign.lowPass(
                FirDesign.tapsForTransition(AUDIO_TRANSITION_HZ / IF_RATE), AUDIO_CUTOFF_HZ / IF_RATE);

        this.channelI = new FirFilter(channelTaps, IF_DECIMATION);
        this.channelQ = new FirFilter(channelTaps, IF_DECIMATION);
        this.sumLowPass = new FirFilter(audioTaps, AUDIO_DECIMATION);
        this.noiseHighPass = new FirFilter(
                FirDesign.highPass(
                        FirDesign.tapsForTransition(NOISE_TRANSITION_HZ / IF_RATE), NOISE_CUTOFF_HZ / IF_RATE),
                1);
        this.discriminator = new FmDiscriminator(IF_RATE, FmDiscriminator.BROADCAST_DEVIATION_HZ);
        this.deemphasisLeft = Deemphasis.forMicros(region.deemphasisMicros(), AUDIO_RATE);
        this.deemphasisRight = Deemphasis.forMicros(region.deemphasisMicros(), AUDIO_RATE);

        int ifCapacity = channelI.outputCapacity(maxPairs);
        int audioCapacity = sumLowPass.outputCapacity(ifCapacity);

        this.pilotTracker = new PilotTracker(IF_RATE, ifCapacity);
        this.stereoBlend = new StereoBlend((double) INPUT_RATE / maxPairs);
        this.stereoDecoder = new StereoDecoder(IF_RATE, AUDIO_RATE, AUDIO_CUTOFF_HZ, ifCapacity);
        this.rdsReceiver = new RdsReceiver(IF_RATE, ifCapacity);

        // The pilot tracker delays the multiplex to meet its own reference; the sum path must be
        // delayed identically or the L/R matrix combines two different instants.
        this.sumAlignment = new Delay(pilotTracker.groupDelaySamples());

        Fft.hann(spectrumWindow);

        // AM reuses the first decimation and then narrows hard: 240 kHz down to 48 kHz with a
        // 5 kHz cutoff, which is the channel filter and the audio filter at once.
        this.amChannelI = new FirFilter(
                FirDesign.lowPass(FirDesign.tapsForTransition(AM_TRANSITION_HZ / IF_RATE), AM_CUTOFF_HZ / IF_RATE),
                AUDIO_DECIMATION);
        this.amChannelQ = new FirFilter(
                FirDesign.lowPass(FirDesign.tapsForTransition(AM_TRANSITION_HZ / IF_RATE), AM_CUTOFF_HZ / IF_RATE),
                AUDIO_DECIMATION);
        this.amI = new float[audioCapacity];
        this.amQ = new float[audioCapacity];
        this.amDemodulator = new AmDemodulator(AUDIO_RATE);

        this.rawI = new float[maxPairs];
        this.rawQ = new float[maxPairs];
        this.ifI = new float[ifCapacity];
        this.ifQ = new float[ifCapacity];
        this.mpx = new float[ifCapacity];
        this.mpxAligned = new float[ifCapacity];
        this.noise = new float[ifCapacity];
        this.sum = new float[audioCapacity];
        this.difference = new float[Math.max(audioCapacity, stereoDecoder.outputCapacity(ifCapacity))];
        this.left = new float[audioCapacity];
        this.right = new float[audioCapacity];
    }

    /** Safe size for the {@code out} array handed to {@link #process}, counting both channels. */
    public int audioCapacity() {
        return sum.length * CHANNELS;
    }

    /**
     * Runs one block through the whole chain.
     *
     * @param raw interleaved u8 IQ straight off the source
     * @param byteCount valid bytes in {@code raw}
     * @param out receives interleaved stereo 16-bit PCM at {@link #AUDIO_RATE}; size it with
     *     {@link #audioCapacity}
     * @return the number of PCM <i>samples</i> written, i.e. twice the number of frames — always
     *     even, and varying block to block since the block size is not a multiple of the decimation
     *     factors. Never read {@code out.length} instead.
     */
    public int process(byte[] raw, int byteCount, short[] out) {
        int pairs = SampleFormat.u8ToFloat(raw, byteCount, rawI, rawQ);
        lastPairs = pairs;

        // Measured before the channel filter, which is the entire point — see adcHeadroomDb.
        adcHeadroomDb = -PowerMeter.rmsDbfs(rawI, rawQ, pairs);

        int ifCount = channelI.filter(rawI, pairs, ifI);
        channelQ.filter(rawQ, pairs, ifQ);

        signalDbfs = PowerMeter.rmsDbfs(ifI, ifQ, ifCount);

        if (modulation == Modulation.AM) {
            return processAm(ifCount, out);
        }

        discriminator.demodulate(ifI, ifQ, ifCount, mpx);

        noiseHighPass.filter(mpx, ifCount, noise);
        noiseDbfs = PowerMeter.rmsDbfs(noise, ifCount);
        measureCarrierOffset(ifCount);

        sumAlignment.process(mpx, ifCount, mpxAligned);
        int frames = sumLowPass.filter(mpxAligned, ifCount, sum);

        // Always track the pilot, even when the user has forced mono: the indicator should report
        // what the station is broadcasting, not what we chose to listen to, RDS needs the same
        // reference, and keeping the loop running means re-enabling stereo does not re-acquire.
        pilotTracker.process(mpx, ifCount);
        stereoDecoder.decodeDifference(pilotTracker, difference);
        rdsReceiver.process(pilotTracker);
        boolean locked = pilotTracker.isLocked();
        pilotLocked = locked;

        // Scaled rather than switched: see StereoBlend. A marginal station narrows its image and gets
        // the difference channel's noise attenuated with it, instead of being handed all of that noise
        // or none of the separation by a threshold decision.
        float blend = (float) stereoBlend.update(quietingDb(noiseDbfs), locked, stereoEnabled);
        stereoBlendFactor = blend;
        if (blend > 0.0f) {
            for (int n = 0; n < frames; n++) {
                float side = blend * difference[n];
                left[n] = sum[n] + side;
                right[n] = sum[n] - side;
            }
        } else {
            System.arraycopy(sum, 0, left, 0, frames);
            System.arraycopy(sum, 0, right, 0, frames);
        }

        deemphasisLeft.process(left, frames);
        deemphasisRight.process(right, frames);

        for (int n = 0, b = 0; n < frames; n++, b += CHANNELS) {
            out[b] = toPcm(left[n]);
            out[b + 1] = toPcm(right[n]);
        }
        return frames * CHANNELS;
    }

    /**
     * Estimates how far the carrier sits from where we tuned, from the multiplex's own DC level.
     *
     * <p>An FM discriminator reports instantaneous frequency, and this one is scaled so full deviation
     * is ±1.0 — so the mean of its output, times the peak deviation, <b>is</b> the offset in hertz.
     * Programme content carries no DC, which is what makes the measurement possible without a
     * reference: whatever is left when the audio averages out is the carrier in the wrong place.
     *
     * <p>Worth having because a frequency error damages <b>stereo and RDS before it damages mono</b>.
     * The offset slides the multiplex within the channel filter, and the 38 kHz difference channel and
     * 57 kHz RDS subcarrier sit at the top of that band, so they reach the edge first while the mono
     * sum below 15 kHz still sounds perfectly clean. That is a confusing symptom to diagnose by ear and
     * an obvious one to read off a number.
     *
     * <p>One pass over the block, which against the channel filter's 266 M multiply-accumulates a
     * second does not register.
     */
    private void measureCarrierOffset(int ifCount) {
        if (ifCount <= 0) {
            return;
        }
        double sum = 0.0;
        for (int n = 0; n < ifCount; n++) {
            sum += mpx[n];
        }
        double blockMean = sum / ifCount * FmDiscriminator.BROADCAST_DEVIATION_HZ;
        // Seed from the first block rather than from zero, or the estimate spends its whole settling
        // time climbing out of a value we know to be wrong.
        offsetEstimate =
                offsetBlocks == 0 ? blockMean : offsetEstimate + OFFSET_SMOOTHING * (blockMean - offsetEstimate);
        offsetBlocks++;
        carrierOffsetHz = offsetBlocks >= OFFSET_SETTLE_BLOCKS ? offsetEstimate : Double.NaN;
    }

    /**
     * How far the carrier is from the tuned frequency, in hertz, or {@code NaN} until measured.
     *
     * <p>Mostly the dongle's crystal error, which scales with frequency: divide by the tuned frequency
     * for the parts per million to blame the hardware rather than the station. AM does not report it —
     * envelope detection has no frequency discriminator to read it from.
     */
    public double carrierOffsetHz() {
        return carrierOffsetHz;
    }

    /**
     * The AM path: narrow to the channel, take the envelope, and put the same audio on both channels.
     *
     * <p>Deliberately short. AM has no pilot, no difference channel, no RDS and no de-emphasis, so
     * everything the FM path does after the discriminator simply does not apply — and pretending
     * otherwise would light indicators for things this band cannot carry.
     */
    private int processAm(int ifCount, short[] out) {
        int frames = amChannelI.filter(ifI, ifCount, amI);
        amChannelQ.filter(ifQ, ifCount, amQ);
        amDemodulator.demodulate(amI, amQ, frames, left);

        pilotLocked = false;
        noiseDbfs = 0.0;
        stereoBlendFactor = 0.0; // AM is mono by construction, not by a blend decision
        carrierOffsetHz = Double.NaN; // envelope detection has no discriminator to read an offset from

        for (int n = 0, b = 0; n < frames; n++, b += CHANNELS) {
            short pcm = toPcm(left[n]);
            out[b] = pcm;
            out[b + 1] = pcm;
        }
        return frames * CHANNELS;
    }

    /** Which modulation this chain was built for; it is fixed for the chain's lifetime. */
    public Modulation modulation() {
        return modulation;
    }

    /** Signal strength of the selected channel, in dBFS. Updated once per processed block. */
    public double signalDbfs() {
        return signalDbfs;
    }

    /**
     * How much room is left before the ADC saturates, in dB — <b>higher is safer</b>.
     *
     * <p>Taken from the raw samples, <b>before</b> the channel filter, and that is the whole reason it
     * exists. The ADC digitises the entire {@link #INPUT_RATE} window, so its headroom is consumed by the
     * strongest signal anywhere in that window — not by the station being listened to. A weak station
     * flanked by strong neighbours a few hundred kilohertz away therefore cannot be improved by raising
     * the front-end gain: the neighbours reach saturation first, and an 8-bit ADC in compression sprays
     * intermodulation products straight across the multiplex. {@link #signalDbfs} is measured after the
     * filter and is blind to all of it, which made "will more gain help?" a question this receiver could
     * not answer about itself.
     *
     * <p>Read from RMS rather than from peaks. Broadcast FM is constant-envelope, so for a handful of
     * comparable carriers the crest factor is modest and RMS tracks saturation closely enough to decide a
     * gain step; counting railed samples would be the stricter measure if this ever needs to be exact.
     *
     * <p>Costs one pass over the block — about 1% of the channel filter beside it — and reuses
     * {@code PowerMeter} rather than hand-rolling the arithmetic.
     */
    public double adcHeadroomDb() {
        return adcHeadroomDb;
    }

    /**
     * A snapshot of the RF spectrum across the full sample rate, most negative frequency first.
     *
     * <p>Taken from the raw IQ of the block just processed, so it shows the neighbours as well as the
     * tuned station. <b>Call it at status cadence, not per block</b> — it is the one deliberately
     * allocating method on this class, and the whole point of computing it here rather than in the
     * loop is that nine transforms a second cost nothing while seventy-three would be waste.
     *
     * @return {@link #SPECTRUM_BINS} values in dBFS, or null if no block has been processed
     */
    public float[] captureSpectrum() {
        if (lastPairs < SPECTRUM_BINS) {
            return null;
        }
        for (int n = 0; n < SPECTRUM_BINS; n++) {
            spectrumRe[n] = rawI[n] * spectrumWindow[n];
            spectrumIm[n] = rawQ[n] * spectrumWindow[n];
        }
        float[] out = new float[SPECTRUM_BINS];
        Fft.magnitudesDb(spectrumRe, spectrumIm, out, SPECTRUM_FLOOR_DBFS);
        return out;
    }

    /**
     * Noise level above the multiplex, in dBFS — <b>low means a strong station</b>.
     *
     * <p>This, not {@link #signalDbfs}, is what seek thresholds on. RF power is useless for the job
     * because the dongle's AGC gains an empty channel up until it reads much like an occupied one;
     * an FM discriminator fed noise, on the other hand, produces loud broadband hiss, and a strong
     * carrier quiets it. This is the same "noise squelch" measurement analogue radios have always
     * used, and it is immune to whatever the AGC is doing.
     */
    public double noiseDbfs() {
        return noiseDbfs;
    }

    /** Whether the station is broadcasting a stereo pilot, regardless of {@link #setStereoEnabled}. */
    public boolean isPilotLocked() {
        return pilotLocked;
    }

    /**
     * How wide the stereo image currently is: 1.0 full, 0.0 mono, in between blended.
     *
     * <p>Distinct from {@link #isPilotLocked}, which reports what the <i>station</i> is transmitting.
     * This reports what the listener is being given, which on a marginal signal is deliberately less.
     */
    public double stereoBlend() {
        return stereoBlendFactor;
    }

    /**
     * How far the carrier has quieted the discriminator, in dB — <b>higher is stronger</b>.
     *
     * <p>The same measurement as {@link #noiseDbfs()} with its sense turned round and its zero point
     * moved to {@link #EMPTY_CHANNEL_NOISE_DBFS}, because everything that consumes it — the stereo
     * blend, the status line, the weak-signal decision — wants "how good is this" rather than "how much
     * noise is left", and a quantity whose better direction is downward gets misread.
     */
    public static double quietingDb(double noiseDbfs) {
        return EMPTY_CHANNEL_NOISE_DBFS - noiseDbfs;
    }

    /** What RDS has revealed about this station: name, radio text, programme type. */
    public StationInfo stationInfo() {
        return rdsReceiver.stationInfo();
    }

    /** Whether RDS group sync has been achieved — i.e. whether this station carries RDS at all. */
    public boolean isRdsSynced() {
        return rdsReceiver.isSynced();
    }

    /**
     * A one-line account of what the RDS path is doing, for the status bar.
     *
     * <p>Worth carrying because the three ways RDS can fail need different fixes and look identical
     * from the outside: no pilot means the carrier reference is missing, pilot-but-no-sync means the
     * bits are not being recovered, and sync-but-no-text means group decoding.
     */
    public String rdsDiagnostic() {
        if (modulation == Modulation.AM) {
            return "AM";
        }
        if (!pilotLocked) {
            return "no pilot";
        }
        // Report what this station has yielded, not whether the synchroniser happens to be locked at
        // this instant. Block sync drops and re-acquires routinely on a burst of interference, and a
        // status that flickers to "no RDS" while the name is on screen reads as a fault.
        long groups = rdsReceiver.groupsDecoded();
        if (groups == 0) {
            return "pilot, no RDS";
        }
        return "RDS %d groups @ %.1f bps%s"
                .formatted(groups, rdsReceiver.symbolRateHz(), rdsReceiver.isUsingQuadrature() ? " (Q)" : "");
    }

    /**
     * Forces mono when false. A real radio has this button: stereo raises the noise floor by roughly
     * 20 dB, so a weak station is often more listenable in mono.
     */
    public void setStereoEnabled(boolean enabled) {
        this.stereoEnabled = enabled;
    }

    public boolean isStereoEnabled() {
        return stereoEnabled;
    }

    /** Clears all filter state. Call on retune, so the previous station's tail is not smeared in. */
    public void reset() {
        channelI.reset();
        channelQ.reset();
        sumLowPass.reset();
        noiseHighPass.reset();
        sumAlignment.reset();
        discriminator.reset();
        amChannelI.reset();
        amChannelQ.reset();
        amDemodulator.reset();
        pilotTracker.reset();
        stereoBlend.reset();
        stereoBlendFactor = 0.0;
        stereoDecoder.reset();
        rdsReceiver.reset();
        deemphasisLeft.reset();
        deemphasisRight.reset();
        signalDbfs = PowerMeter.FLOOR_DBFS;
        adcHeadroomDb = Double.NaN;
        noiseDbfs = 0.0;
        pilotLocked = false;
        // The offset belongs to the station we were on, so it has to be re-measured for the next one.
        carrierOffsetHz = Double.NaN;
        offsetEstimate = 0.0;
        offsetBlocks = 0;
    }

    private static short toPcm(float sample) {
        return (short) Math.clamp((int) (sample * Short.MAX_VALUE), Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
