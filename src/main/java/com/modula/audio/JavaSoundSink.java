package com.modula.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays mono 16-bit PCM through {@code javax.sound.sampled}.
 *
 * <p>Runs its own daemon thread pulling from a {@link ShortRing}. That thread's blocking
 * {@code line.write} is the playback clock and is <b>meant</b> to block — it is what paces
 * consumption. The DSP thread only ever touches the ring.
 *
 * <p>Known limitation: the dongle's crystal and the sound card's crystal are independent, so over a
 * long listening session the ring drifts toward full or empty and will eventually glitch. The fix is
 * a fractional resampler tracking the ring's fill level; it belongs here, not in the DSP chain.
 */
public final class JavaSoundSink implements AudioSink {

    /** Ring depth in samples — about half a second of stereo at 48 kHz, enough to ride out a hiccup. */
    private static final int RING_SAMPLES = 48_000;

    /** Samples handed to the line per write; must be a whole number of frames. ~21 ms at 48 kHz. */
    private static final int CHUNK_SAMPLES = 2048;

    private final int sampleRate;
    private final int channels;
    private final ShortRing ring;

    private SourceDataLine line;
    private Thread thread;
    private volatile boolean running;
    private volatile float volume = 0.7f;

    public JavaSoundSink(int sampleRate, int channels) {
        if (CHUNK_SAMPLES % channels != 0) {
            throw new IllegalArgumentException("chunk size must be a whole number of frames");
        }
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.ring = new ShortRing(RING_SAMPLES, channels);
    }

    @Override
    public void open() throws LineUnavailableException {
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false); // signed, little-endian
        line = AudioSystem.getSourceDataLine(format);
        line.open(format, CHUNK_SAMPLES * 2 * 4);
        line.start();

        running = true;
        thread = new Thread(this::pump, "modula-audio");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void write(short[] pcm, int count) {
        ring.write(pcm, count);
    }

    @Override
    public void setVolume(double v) {
        volume = (float) Math.clamp(v, 0.0, 1.0);
    }

    @Override
    public long droppedSamples() {
        return ring.droppedSamples();
    }

    @Override
    public long underrunSamples() {
        return ring.underrunSamples();
    }

    @Override
    public void close() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        SourceDataLine l = line;
        line = null;
        if (l != null) {
            l.stop();
            l.close();
        }
        ring.clear();
    }

    private void pump() {
        short[] samples = new short[CHUNK_SAMPLES];
        byte[] bytes = new byte[CHUNK_SAMPLES * 2];
        while (running) {
            ring.read(samples, CHUNK_SAMPLES);
            float g = volume;
            for (int n = 0, b = 0; n < CHUNK_SAMPLES; n++, b += 2) {
                int v = (int) (samples[n] * g);
                v = Math.clamp(v, Short.MIN_VALUE, Short.MAX_VALUE);
                bytes[b] = (byte) v;
                bytes[b + 1] = (byte) (v >> 8);
            }
            SourceDataLine l = line;
            if (l == null) {
                return;
            }
            l.write(bytes, 0, bytes.length);
        }
    }
}
