package com.modula.audio;

/**
 * A fixed-capacity ring buffer of PCM samples, decoupling the DSP thread from the audio thread.
 *
 * <p>This exists so the DSP thread <b>never blocks on the sound card</b>. If it did, a stalled audio
 * line would back-pressure into the socket read and the dongle's own buffers would overflow, turning
 * a momentary audio hiccup into dropped RF. Instead an overrun drops a block here and is counted.
 *
 * <p>Underrun fills with silence rather than starving the line, since the sound card must be handed
 * samples on time whatever the DSP side is doing.
 *
 * <p>Single producer, single consumer. {@code synchronized} rather than lock-free on purpose: at a
 * 13.6 ms block period this is called ~70 times a second and contention is immeasurable, so the
 * simplest obviously-correct implementation is the right one.
 */
public final class ShortRing {

    private final short[] buf;
    private final int frameSize;
    private int head;
    private int tail;
    private int size;
    private long droppedSamples;
    private long underrunSamples;

    public ShortRing(int capacity) {
        this(capacity, 1);
    }

    /**
     * @param frameSize samples per frame — 2 for interleaved stereo. Writes are truncated to whole
     *     frames, which matters more than it looks: dropping an <i>odd</i> number of samples on
     *     overrun would shift every later sample by one and swap the channels permanently, turning a
     *     momentary glitch into a stereo image that stays inverted until restart.
     */
    public ShortRing(int capacity, int frameSize) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        if (frameSize < 1) {
            throw new IllegalArgumentException("frameSize must be >= 1, got " + frameSize);
        }
        this.buf = new short[capacity - capacity % frameSize];
        this.frameSize = frameSize;
    }

    public int capacity() {
        return buf.length;
    }

    public synchronized int available() {
        return size;
    }

    /**
     * Writes up to {@code count} samples, dropping any that do not fit.
     *
     * @return the number actually written
     */
    public synchronized int write(short[] src, int count) {
        int room = buf.length - size;
        int n = Math.min(count, room);
        n -= n % frameSize; // never split a frame — see the constructor
        if (n < count) {
            droppedSamples += count - n;
        }
        for (int k = 0; k < n; k++) {
            buf[tail] = src[k];
            tail = tail + 1 == buf.length ? 0 : tail + 1;
        }
        size += n;
        return n;
    }

    /** Reads exactly {@code count} samples into {@code dst}, zero-filling any shortfall. */
    public synchronized void read(short[] dst, int count) {
        int n = Math.min(count, size);
        for (int k = 0; k < n; k++) {
            dst[k] = buf[head];
            head = head + 1 == buf.length ? 0 : head + 1;
        }
        size -= n;
        if (n < count) {
            java.util.Arrays.fill(dst, n, count, (short) 0);
            underrunSamples += count - n;
        }
    }

    public synchronized long droppedSamples() {
        return droppedSamples;
    }

    public synchronized long underrunSamples() {
        return underrunSamples;
    }

    public synchronized void clear() {
        head = 0;
        tail = 0;
        size = 0;
    }
}
