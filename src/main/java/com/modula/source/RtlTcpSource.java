package com.modula.source;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * An {@link IqSource} speaking the {@code rtl_tcp} protocol over a socket.
 *
 * <p>Pure Java — no native code, no USB permissions, no driver install. Start the server with
 * {@code rtl_tcp -a 127.0.0.1} and point this at it. Besides being the fastest way to a working
 * receiver, it stays useful forever: the dongle can live on another machine, and development needs
 * no hardware attached to the dev box.
 *
 * <p>Protocol: the server opens with a 12-byte header ({@code "RTL0"}, tuner type, gain count),
 * then streams raw interleaved u8 IQ until closed. Control is a 5-byte message — one command byte
 * then a big-endian 32-bit parameter.
 */
public final class RtlTcpSource implements IqSource {

    private static final int CMD_SET_FREQUENCY = 0x01;
    private static final int CMD_SET_SAMPLE_RATE = 0x02;
    private static final int CMD_SET_GAIN_MODE = 0x03;
    private static final int CMD_SET_GAIN = 0x04;
    private static final int CMD_SET_AGC_MODE = 0x08;
    private static final int CMD_SET_DIRECT_SAMPLING = 0x09;

    private static final int HEADER_BYTES = 12;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int SOCKET_RECEIVE_BUFFER = 1 << 20;

    /** Tuner range of the common R820T2/R828D. Medium wave is far below this — see {@link #tunableRange}. */
    private static final Range R820T_RANGE = new Range(24_000_000L, 1_766_000_000L);

    /** Bypassing the tuner samples the ADC directly, up to half its 28.8 MHz clock. */
    private static final Range DIRECT_RANGE = new Range(100_000L, 14_400_000L);

    private final String host;
    private final int port;

    private Socket socket;
    private InputStream in;
    private DataOutputStream out;
    private int tunerType;
    private volatile boolean directSampling;

    public RtlTcpSource(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public static RtlTcpSource localhost() {
        return new RtlTcpSource("127.0.0.1", 1234);
    }

    @Override
    public void open() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.setReceiveBufferSize(SOCKET_RECEIVE_BUFFER);

        InputStream raw = new BufferedInputStream(s.getInputStream(), SOCKET_RECEIVE_BUFFER);
        byte[] header = raw.readNBytes(HEADER_BYTES);
        if (header.length < HEADER_BYTES) {
            s.close();
            throw new IOException("rtl_tcp closed before sending its header");
        }
        if (header[0] != 'R' || header[1] != 'T' || header[2] != 'L' || header[3] != '0') {
            s.close();
            throw new IOException("not an rtl_tcp server (bad magic)");
        }
        this.tunerType = beInt(header, 4);

        this.socket = s;
        this.in = raw;
        this.out = new DataOutputStream(s.getOutputStream());

        // No digital AGC, matching the native source — the two delivery paths behaving differently is
        // precisely what made a front-end problem look like an operating-system one.
        command(CMD_SET_AGC_MODE, 0);
    }

    @Override
    public void setFrequency(long hz) throws IOException {
        command(CMD_SET_FREQUENCY, (int) hz);
    }

    @Override
    public void setSampleRate(int samplesPerSecond) throws IOException {
        command(CMD_SET_SAMPLE_RATE, samplesPerSecond);
    }

    /**
     * Sets the same fixed gain the native source uses.
     *
     * <p>The protocol carries a gain <i>count</i> in its header but not the values, so there is nothing
     * to enumerate here — the target goes over as-is and the server's own librtlsdr maps it onto a
     * supported step. That is why {@link TunerGain#nearest} returns the target unchanged for an empty
     * list rather than refusing.
     */
    @Override
    public void applyDefaultGain() throws IOException {
        command(CMD_SET_GAIN_MODE, 1); // manual
        command(CMD_SET_GAIN, TunerGain.choose(new int[0]));
    }

    /** Enables the direct-sampling Q branch, the only way to reach HF and medium wave. */
    @Override
    public boolean setDirectSampling(boolean enabled) throws IOException {
        command(CMD_SET_DIRECT_SAMPLING, enabled ? 2 : 0);
        directSampling = enabled;
        return enabled;
    }

    @Override
    public int read(byte[] into) throws IOException {
        if (in == null) {
            throw new IOException("source is not open");
        }
        return in.readNBytes(into, 0, into.length);
    }

    @Override
    public Range tunableRange() {
        return directSampling ? DIRECT_RANGE : R820T_RANGE;
    }

    /** The tuner type reported in the server header; 0 when unknown. */
    public int tunerType() {
        return tunerType;
    }

    @Override
    public void close() {
        Socket s = socket;
        socket = null;
        in = null;
        out = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing a socket we are done with; nothing useful to do.
            }
        }
    }

    private void command(int cmd, int param) throws IOException {
        DataOutputStream o = out;
        if (o == null) {
            throw new IOException("source is not open");
        }
        byte[] msg =
                new byte[] {(byte) cmd, (byte) (param >>> 24), (byte) (param >>> 16), (byte) (param >>> 8), (byte) param
                };
        synchronized (this) {
            o.write(msg);
            o.flush();
        }
    }

    private static int beInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24)
                | ((b[offset + 1] & 0xFF) << 16)
                | ((b[offset + 2] & 0xFF) << 8)
                | (b[offset + 3] & 0xFF);
    }
}
