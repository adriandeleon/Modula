package com.modula.radio;

import com.modula.band.Region;
import com.modula.demod.FmDiscriminator;
import com.modula.demod.PilotTracker;
import com.modula.demod.StereoDecoder;
import com.modula.dsp.Deemphasis;
import com.modula.dsp.Delay;
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
    private final FmDiscriminator discriminator;
    private final PilotTracker pilotTracker;
    private final StereoDecoder stereoDecoder;
    private final RdsReceiver rdsReceiver;
    private final Deemphasis deemphasisLeft;
    private final Deemphasis deemphasisRight;

    private volatile double signalDbfs = PowerMeter.FLOOR_DBFS;
    private volatile double noiseDbfs = 0.0;
    private volatile boolean pilotLocked;
    private volatile boolean stereoEnabled = true;

    public DemodChain(Region region) {
        this(region, BLOCK_PAIRS);
    }

    public DemodChain(Region region, int maxPairs) {
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
        this.stereoDecoder = new StereoDecoder(IF_RATE, AUDIO_RATE, AUDIO_CUTOFF_HZ, ifCapacity);
        this.rdsReceiver = new RdsReceiver(IF_RATE, ifCapacity);

        // The pilot tracker delays the multiplex to meet its own reference; the sum path must be
        // delayed identically or the L/R matrix combines two different instants.
        this.sumAlignment = new Delay(pilotTracker.groupDelaySamples());

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

        int ifCount = channelI.filter(rawI, pairs, ifI);
        channelQ.filter(rawQ, pairs, ifQ);

        signalDbfs = PowerMeter.rmsDbfs(ifI, ifQ, ifCount);

        discriminator.demodulate(ifI, ifQ, ifCount, mpx);

        noiseHighPass.filter(mpx, ifCount, noise);
        noiseDbfs = PowerMeter.rmsDbfs(noise, ifCount);

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

        if (locked && stereoEnabled) {
            for (int n = 0; n < frames; n++) {
                left[n] = sum[n] + difference[n];
                right[n] = sum[n] - difference[n];
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

    /** Signal strength of the selected channel, in dBFS. Updated once per processed block. */
    public double signalDbfs() {
        return signalDbfs;
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
        if (!pilotLocked) {
            return "no pilot";
        }
        if (!rdsReceiver.isSynced()) {
            return "pilot, no RDS";
        }
        return "RDS %d groups @ %.1f bps%s"
                .formatted(
                        rdsReceiver.groupsDecoded(),
                        rdsReceiver.symbolRateHz(),
                        rdsReceiver.isUsingQuadrature() ? " (Q)" : "");
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
        pilotTracker.reset();
        stereoDecoder.reset();
        rdsReceiver.reset();
        deemphasisLeft.reset();
        deemphasisRight.reset();
        signalDbfs = PowerMeter.FLOOR_DBFS;
        noiseDbfs = 0.0;
        pilotLocked = false;
    }

    private static short toPcm(float sample) {
        return (short) Math.clamp((int) (sample * Short.MAX_VALUE), Short.MIN_VALUE, Short.MAX_VALUE);
    }
}
