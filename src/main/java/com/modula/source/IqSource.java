package com.modula.source;

import java.io.IOException;

/**
 * A stream of raw interleaved unsigned-8-bit IQ samples, which is the native format of every
 * RTL-SDR delivery path (USB, {@code rtl_tcp}, and a captured {@code .iq} file alike).
 *
 * <p>This interface is the whole hardware boundary. {@link com.modula.source.RtlTcpSource} needs no
 * native code at all and is the development source; a Panama binding to {@code librtlsdr} and a
 * file replay satisfy the same contract, so nothing downstream ever learns which is in use.
 */
public interface IqSource extends AutoCloseable {

    /** Opens the device or connection. Must be called before any other method. */
    void open() throws IOException;

    void setFrequency(long hz) throws IOException;

    void setSampleRate(int samplesPerSecond) throws IOException;

    /** Hands gain control to the device's own AGC. Modula deliberately does not expose manual gain. */
    void setGainAuto() throws IOException;

    /**
     * Fills {@code into} with raw interleaved u8 IQ, blocking until it is full.
     *
     * @return bytes read, or a short count at end of stream (0 when fully exhausted)
     */
    int read(byte[] into) throws IOException;

    /**
     * The frequency range this source can actually tune.
     *
     * <p>Load-bearing, not decoration: a stock RTL-SDR tuner floors out around 24 MHz, so medium-wave
     * AM is unreachable without a direct-sampling-capable dongle or an upconverter. The UI asks the
     * source before offering a band, rather than tuning into silence and leaving the user to guess.
     */
    Range tunableRange();

    @Override
    void close();

    /** An inclusive frequency range, in Hz. */
    record Range(long minHz, long maxHz) {
        public Range {
            if (minHz > maxHz) {
                throw new IllegalArgumentException("minHz " + minHz + " > maxHz " + maxHz);
            }
        }

        public boolean contains(long hz) {
            return hz >= minHz && hz <= maxHz;
        }
    }
}
