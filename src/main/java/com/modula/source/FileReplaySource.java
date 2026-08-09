package com.modula.source;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Replays raw interleaved u8 IQ from a file, as written by {@code rtl_sdr -f … -s … capture.iq}.
 *
 * <p><b>The testing keystone.</b> Capture ten seconds of a real station once, and from then on every
 * change to the DSP chain is verifiable deterministically with no hardware attached — which is the
 * only practical way to catch the block-boundary state bugs described on {@code dsp.FirFilter}.
 *
 * <p>{@code paced} throttles delivery to the nominal sample rate so replay sounds like live radio;
 * tests leave it off and consume the file as fast as the chain will run.
 */
public final class FileReplaySource implements IqSource {

    private final Path file;
    private final int sampleRate;
    private final boolean paced;
    private final boolean loop;

    private InputStream in;
    private long samplesDelivered;
    private long startNanos;

    public FileReplaySource(Path file, int sampleRate, boolean paced, boolean loop) {
        this.file = file;
        this.sampleRate = sampleRate;
        this.paced = paced;
        this.loop = loop;
    }

    /** A source for tests: unpaced, single pass. */
    public static FileReplaySource forTest(Path file, int sampleRate) {
        return new FileReplaySource(file, sampleRate, false, false);
    }

    @Override
    public void open() throws IOException {
        in = new BufferedInputStream(Files.newInputStream(file), 1 << 20);
        samplesDelivered = 0;
        startNanos = System.nanoTime();
    }

    @Override
    public void setFrequency(long hz) {
        // A capture is already at one frequency; retuning is meaningless. Ignored, not an error,
        // so the engine can drive a replay through the same code path as a live dongle.
    }

    @Override
    public void setSampleRate(int samplesPerSecond) {
        // Fixed at capture time.
    }

    @Override
    public void setGainAuto() {
        // Fixed at capture time.
    }

    @Override
    public int read(byte[] into) throws IOException {
        if (in == null) {
            throw new IOException("source is not open");
        }
        int n = in.readNBytes(into, 0, into.length);
        if (n < into.length && loop) {
            in.close();
            in = new BufferedInputStream(Files.newInputStream(file), 1 << 20);
            n += in.readNBytes(into, n, into.length - n);
        }
        if (paced && n > 0) {
            pace(n / 2);
        }
        return n;
    }

    @Override
    public Range tunableRange() {
        return new Range(0L, Long.MAX_VALUE);
    }

    @Override
    public void close() {
        InputStream s = in;
        in = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // Closing a stream we are done with.
            }
        }
    }

    private void pace(int newSamples) {
        samplesDelivered += newSamples;
        long dueNanos = startNanos + (long) (samplesDelivered * 1e9 / sampleRate);
        long waitNanos = dueNanos - System.nanoTime();
        if (waitNanos > 0) {
            try {
                Thread.sleep(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
