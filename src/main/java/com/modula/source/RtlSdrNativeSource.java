package com.modula.source;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An {@link IqSource} talking to the dongle directly through {@code librtlsdr}, with no
 * {@code rtl_tcp} in between.
 *
 * <p>Same capability as {@link RtlTcpSource}, one less moving part: no server to start, no socket,
 * and the dongle's errors arrive as errors rather than as a connection that will not open.
 *
 * <p><b>librtlsdr is not thread-safe, and this class does nothing to make it so.</b> That is fine
 * because {@link com.modula.radio.RadioEngine} already confines every call to its receive thread —
 * reads, retunes and gain changes all happen there, by construction. The one exception is
 * {@link #close}, which the engine calls only after joining that thread.
 *
 * <p><b>Reads are synchronous, which only works because the caller does nothing else between them.</b>
 * {@code rtlsdr_read_sync} keeps exactly one USB transfer outstanding and submits the next only when
 * called again, so any work the caller does in between is a window with nothing in flight — and the
 * dongle keeps producing regardless. {@link com.modula.radio.RadioEngine} therefore reads on a thread
 * whose entire job is reading, handing blocks to the DSP through a {@link ByteRing}; putting the chain
 * back in this loop would silently start dropping samples again. librtlsdr's asynchronous API keeps
 * about fifteen transfers queued and would remove the window altogether, at the cost of an FFM upcall.
 */
public final class RtlSdrNativeSource implements IqSource {

    private static final Logger LOG = Logger.getLogger(RtlSdrNativeSource.class.getName());

    /** Tuner range of the common R820T2/R828D. Medium wave sits far below it — see {@link #tunableRange}. */
    private static final Range R820T_RANGE = new Range(24_000_000L, 1_766_000_000L);

    /** Direct sampling via the Q branch: the RTL-SDR Blog V3's route to HF and medium wave. */
    private static final int DIRECT_SAMPLING_Q = 2;

    /** Bypassing the tuner samples the ADC directly, up to half its 28.8 MHz clock. */
    private static final Range DIRECT_RANGE = new Range(100_000L, 14_400_000L);

    private final int deviceIndex;
    private final int bufferBytes;

    private MemorySegment device;
    private MemorySegment buffer;
    private MemorySegment readSlot;
    private volatile boolean open;
    private volatile boolean directSampling;
    private volatile int gainTenths;

    public RtlSdrNativeSource(int deviceIndex, int bufferBytes) {
        this.deviceIndex = deviceIndex;
        this.bufferBytes = bufferBytes;
    }

    /** Whether the library loaded and at least one dongle is attached. */
    public static boolean isAvailable() {
        return RtlSdr.isAvailable() && RtlSdr.deviceCount() > 0;
    }

    /** Why direct access is unavailable, phrased for a status line. Empty when it is available. */
    public static String unavailableReason() {
        NativeDiagnosis.Diagnosis diagnosis = RtlSdr.diagnose();
        return diagnosis.ok() ? "" : diagnosis.message();
    }

    /** The full diagnosis, for a surface with room to show the advice separately. */
    public static NativeDiagnosis.Diagnosis diagnose() {
        return RtlSdr.diagnose();
    }

    /** The first attached device's name, for the status line. */
    public static String describeDevice() {
        return RtlSdr.deviceCount() > 0 ? RtlSdr.deviceName(0) : "";
    }

    @Override
    public void open() throws IOException {
        if (open) {
            return;
        }
        device = RtlSdr.open(deviceIndex);
        buffer = RtlSdr.allocate(bufferBytes);
        readSlot = RtlSdr.allocateInt();
        open = true;

        // No AGC at all: not the tuner's, and not the RTL2832U's digital one. Two loops used to be
        // enabled at once, hunting the same signal; see TunerGain for why even one is the wrong answer
        // for broadcast FM. The gain itself is set by the engine's own applyDefaultGain call, once the
        // sample rate is settled — doing it here as well only logged the decision twice.
        RtlSdr.setAgcMode(device, false);
    }

    @Override
    public void setFrequency(long hz) throws IOException {
        checkOpen();
        if (RtlSdr.setCenterFrequency(device, (int) hz) != 0) {
            throw new IOException("could not tune to " + hz + " Hz");
        }
    }

    @Override
    public void setSampleRate(int samplesPerSecond) throws IOException {
        checkOpen();
        if (RtlSdr.setSampleRate(device, samplesPerSecond) != 0) {
            throw new IOException("device rejected a sample rate of " + samplesPerSecond);
        }
        // The first buffer after a rate change is stale; discard it rather than demodulate it.
        RtlSdr.resetBuffer(device);
    }

    /**
     * Sets a fixed front-end gain, chosen from what this tuner actually offers.
     *
     * <p>Falls back to the tuner's AGC when the gain list cannot be read. That is a real possibility on
     * an unusual tuner, and a receiver with the gain stuck at whatever the chip booted with would be a
     * worse outcome than one with an AGC we would rather not use.
     */
    @Override
    public void applyDefaultGain() throws IOException {
        checkOpen();
        int[] supported = RtlSdr.tunerGains(device);
        if (supported.length == 0) {
            LOG.log(Level.INFO, "tuner gains unavailable; leaving the front end on its own AGC");
            RtlSdr.setTunerGainMode(device, false);
            gainTenths = 0;
            return;
        }
        int chosen = TunerGain.choose(supported);
        RtlSdr.setTunerGainMode(device, true);
        if (RtlSdr.setTunerGain(device, chosen) != 0) {
            LOG.log(Level.WARNING, "tuner rejected a gain of {0}; falling back to its AGC", TunerGain.describe(chosen));
            RtlSdr.setTunerGainMode(device, false);
            gainTenths = 0;
            return;
        }
        gainTenths = chosen;
        LOG.log(Level.INFO, "front-end gain fixed at {0}", TunerGain.describe(chosen));
    }

    /** The fixed gain in tenths of a dB, or 0 when the front end was left on its AGC. */
    public int gainTenths() {
        return gainTenths;
    }

    /**
     * Enables the direct-sampling Q branch, the only way an RTL-SDR reaches HF and medium wave.
     *
     * <p>Needs hardware that wires it — an RTL-SDR Blog V3 does, a generic dongle usually does not,
     * and there is no way to ask. The call succeeds either way; what tells you is whether anything
     * is received.
     */
    @Override
    public boolean setDirectSampling(boolean enabled) throws IOException {
        checkOpen();
        RtlSdr.setDirectSampling(device, enabled ? DIRECT_SAMPLING_Q : 0);
        directSampling = enabled;
        return enabled;
    }

    @Override
    public int read(byte[] into) throws IOException {
        if (!open) {
            throw new IOException("source is not open");
        }
        int wanted = Math.min(into.length, bufferBytes);
        int result = RtlSdr.readSync(device, buffer, wanted, readSlot);
        if (result < 0) {
            throw new IOException("read failed (librtlsdr returned " + result + ")");
        }
        MemorySegment.copy(buffer, ValueLayout.JAVA_BYTE, 0, into, 0, result);
        return result;
    }

    @Override
    public Range tunableRange() {
        return directSampling ? DIRECT_RANGE : R820T_RANGE;
    }

    @Override
    public void close() {
        if (!open) {
            return;
        }
        open = false;
        RtlSdr.close(device);
        device = null;
        // The buffers belong to an automatic arena, so they are simply dropped; nothing to free.
        buffer = null;
        readSlot = null;
    }

    private void checkOpen() throws IOException {
        if (!open) {
            throw new IOException("source is not open");
        }
    }
}
