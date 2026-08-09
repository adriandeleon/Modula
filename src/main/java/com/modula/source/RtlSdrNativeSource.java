package com.modula.source;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

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
 * <p>Reads are synchronous. librtlsdr also offers an asynchronous API, but it wants a callback on its
 * own thread, and the engine's loop is already a thread that wants to block on a read — the
 * synchronous call fits it exactly, and at 13.6 ms a block it returns long before any timeout.
 */
public final class RtlSdrNativeSource implements IqSource {

    /** Tuner range of the common R820T2/R828D. Medium wave sits far below it — see {@link #tunableRange}. */
    private static final Range R820T_RANGE = new Range(24_000_000L, 1_766_000_000L);

    /** Direct sampling via the Q branch: the RTL-SDR Blog V3's route to HF and medium wave. */
    private static final int DIRECT_SAMPLING_Q = 2;

    private final int deviceIndex;
    private final int bufferBytes;

    private MemorySegment device;
    private MemorySegment buffer;
    private MemorySegment readSlot;
    private volatile boolean open;

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
        if (!RtlSdr.isAvailable()) {
            return RtlSdr.unavailableReason();
        }
        return RtlSdr.deviceCount() == 0 ? "no RTL-SDR dongle attached" : "";
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

        // Let the dongle manage its own gain; Modula has no gain UI by design.
        RtlSdr.setTunerGainMode(device, false);
        RtlSdr.setAgcMode(device, true);
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

    @Override
    public void setGainAuto() throws IOException {
        checkOpen();
        RtlSdr.setTunerGainMode(device, false);
    }

    /** Enables the RTL-SDR Blog V3 direct-sampling Q branch, the only way to reach medium wave. */
    public void setDirectSampling(boolean enabled) throws IOException {
        checkOpen();
        RtlSdr.setDirectSampling(device, enabled ? DIRECT_SAMPLING_Q : 0);
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
        return R820T_RANGE;
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
