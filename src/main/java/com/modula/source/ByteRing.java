package com.modula.source;

/**
 * A fixed-capacity ring buffer of raw u8 IQ bytes, decoupling the USB reader from the DSP thread.
 *
 * <p>The mirror image of {@link com.modula.audio.ShortRing}, one stage upstream, and it exists for
 * the same reason turned around. {@code ShortRing} keeps the DSP thread from blocking on the sound
 * card; this keeps the <b>reader</b> from waiting on the DSP. Without it the receive loop is
 * read → demodulate → write PCM → read again, and for the whole demodulate-and-write part there is
 * no USB transfer in flight: {@code rtlsdr_read_sync} submits one bulk transfer and does not submit
 * the next until it is called again. The dongle does not pause to wait, so everything it produces in
 * that window is lost, and at 1.2 MSPS of interleaved bytes <b>every millisecond of gap is 1200 I/Q
 * samples gone</b>. A gap in the middle of a block is a phase discontinuity, which is what knocks the
 * 19 kHz pilot PLL out of lock — so the symptom is not a click but a stereo indicator that flickers.
 *
 * <p>The gap is worse on macOS than on Linux for the same dongle and the same code: resubmitting a
 * bulk transfer through IOKit costs more than through Linux usbfs, and an external hub adds another
 * tier of scheduling on top. That difference is invisible until the reader is doing something other
 * than reading.
 *
 * <p><b>An overrun must leave an even gap in the stream.</b> This is the {@code ShortRing} frame
 * argument one level trickier, and getting it wrong is just as permanent. The ring holds a byte
 * stream in which even offsets are I and odd offsets are Q; dropping an <i>odd</i> number of bytes
 * from the middle shifts that parity for everything after it, so I and Q swap. A swapped pair is the
 * complex conjugate, which mirrors the spectrum about the centre frequency and makes the
 * discriminator recover the inverse of the modulation — and it survives until restart.
 *
 * <p>Note the invariant is about the gap between two <i>retained</i> bytes, not about any single
 * call: a completely full ring has to discard the whole offer, and cannot round that to even. So an
 * odd forced drop is carried as a debt and paid by the next write that keeps anything. Getting this
 * subtlety wrong is invisible in a per-call test and shows up only as a stream whose pairs have
 * silently transposed.
 *
 * <p>Reads, by contrast, hand out whole pairs and leave any odd trailing byte in the ring to be
 * paired with the next one that arrives. That is why an odd write is accepted rather than trimmed:
 * the stream's alignment is the thing being preserved, not the alignment of any single write.
 *
 * <p><b>Retuning clears the ring and bumps a generation.</b> After the local oscillator moves,
 * whatever is still buffered was captured at the old frequency, and the invariant that the previous
 * station's tail is never smeared into the new one has to hold across the hand-off. The reader clears
 * and stamps; {@link #lastReadGeneration()} tells the consumer which frequency the bytes it just took
 * belong to, so it knows to reset the filter chain. Both happen under this object's lock, so the
 * stamp cannot be read apart from the data it describes.
 *
 * <p>Single producer, single consumer. {@code synchronized} rather than lock-free for the same reason
 * {@code ShortRing} is: at a 13.6 ms block period this is touched ~70 times a second per side and
 * contention is immeasurable, so the obviously-correct implementation is the right one. Reads may
 * block — the DSP thread waiting for samples is the correct back-pressure direction. Writes never
 * block; {@link #awaitSpace} is offered separately for the caller that would rather pause than drop,
 * and a live dongle should not use it.
 *
 * <p>Zero allocation after construction, like everything else in the receive path.
 */
public final class ByteRing {

    private final byte[] buf;

    private int head;
    private int tail;
    private int size;

    private long droppedBytes;
    private long generation;
    private long lastReadGeneration;
    private boolean finished;

    /** An odd byte was discarded with no room to compensate; the next write that keeps anything pays it. */
    private boolean oddDrop;

    public ByteRing(int capacity) {
        if (capacity < 2) {
            throw new IllegalArgumentException("capacity must be >= 2, got " + capacity);
        }
        this.buf = new byte[capacity - (capacity & 1)];
    }

    public int capacity() {
        return buf.length;
    }

    public synchronized int available() {
        return size;
    }

    /**
     * Appends up to {@code count} bytes, dropping any that do not fit.
     *
     * <p>What must stay even is the number of bytes dropped <b>between two retained ones</b>, not the
     * number dropped by any single call — and those are not the same thing, because a completely full
     * ring has no choice but to discard the whole offer, odd or not. So when that happens the odd byte
     * becomes a debt ({@code oddDrop}) and the next call that accepts anything pays it by discarding
     * one extra leading byte. Trimming the accepted count is the cheaper route and is preferred
     * whenever there is room to trim. Never blocks.
     *
     * @return the number of bytes actually accepted
     */
    public synchronized int write(byte[] src, int count) {
        if (count <= 0) {
            return 0;
        }
        int room = buf.length - size;
        if (room <= 0) {
            // Nothing can be kept, so the parity of the whole offer carries forward as a debt.
            droppedBytes += count;
            oddDrop ^= (count & 1) != 0;
            return 0;
        }

        int from = 0;
        if (oddDrop) {
            from = 1;
            droppedBytes++;
            oddDrop = false;
        }
        int offered = count - from;
        if (offered <= 0) {
            return 0;
        }

        int n = Math.min(offered, room);
        int dropped = offered - n;
        if ((dropped & 1) != 0) {
            // Give up one more byte so the gap in the stream stays even.
            n--;
            dropped++;
        }
        droppedBytes += dropped;
        for (int k = 0; k < n; k++) {
            buf[tail] = src[from + k];
            tail = tail + 1 == buf.length ? 0 : tail + 1;
        }
        size += n;
        if (n > 0) {
            notifyAll();
        }
        return n;
    }

    /**
     * Takes whole I/Q pairs, waiting for {@code want} bytes to arrive.
     *
     * <p>Returns early when the ring is {@linkplain #finish finished} or the timeout elapses, so a
     * source that has run out and a caller that wants to notice it has been stopped both make
     * progress. Any odd trailing byte is left behind for the next pair.
     *
     * @param want bytes wanted; the caller's block size
     * @return bytes copied — always even, may be less than {@code want} on timeout, 0 if none
     *     arrived, or -1 once the ring is finished and empty
     */
    public synchronized int read(byte[] dst, int want, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (size < want && !finished) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            wait(remaining / 1_000_000L + 1, (int) (remaining % 1_000_000L));
        }
        if (size == 0) {
            return finished ? -1 : 0;
        }
        int n = Math.min(Math.min(want, size), dst.length);
        n -= n & 1; // whole pairs only; the odd byte stays for the next read
        for (int k = 0; k < n; k++) {
            dst[k] = buf[head];
            head = head + 1 == buf.length ? 0 : head + 1;
        }
        size -= n;
        lastReadGeneration = generation;
        notifyAll();
        return n;
    }

    /**
     * Waits up to {@code timeoutMillis} for room to appear.
     *
     * <p>For a source that can be back-pressured — a file replay, a test fake — where pausing beats
     * dropping. A live dongle must not call this: pausing the reader is the very thing this class
     * exists to prevent. It is only ever reached once the ring is already full, i.e. once samples are
     * being lost anyway.
     */
    public synchronized void awaitSpace(long timeoutMillis) throws InterruptedException {
        if (size < buf.length || finished) {
            return;
        }
        // Object.wait(0) waits forever, which for a reader that must never stop reading is a hang
        // rather than a pause. Clamped so a smaller block size can never turn this into one.
        wait(Math.max(1L, timeoutMillis));
    }

    /**
     * Discards everything buffered and stamps the new tuning generation.
     *
     * <p>Called by the reader after the local oscillator moves. The bytes being dropped here are not
     * a loss worth counting — they are the old station, and keeping them would smear its tail into
     * the new one.
     */
    public synchronized void retuned() {
        head = 0;
        tail = 0;
        size = 0;
        oddDrop = false; // a new stream starts on a pair boundary, so no debt carries across
        generation++;
        notifyAll();
    }

    /** Which tuning generation the bytes handed out by the last {@link #read} belonged to. */
    public synchronized long lastReadGeneration() {
        return lastReadGeneration;
    }

    /** Marks the source exhausted, so a blocked reader stops waiting for bytes that will not come. */
    public synchronized void finish() {
        finished = true;
        notifyAll();
    }

    /** Bytes the producer offered that did not fit — i.e. the DSP thread falling behind. */
    public synchronized long droppedBytes() {
        return droppedBytes;
    }

    public synchronized void clear() {
        head = 0;
        tail = 0;
        size = 0;
        oddDrop = false;
        finished = false;
        notifyAll();
    }
}
