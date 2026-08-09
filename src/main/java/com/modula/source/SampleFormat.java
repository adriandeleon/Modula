package com.modula.source;

/**
 * Conversion from the RTL-SDR wire format — interleaved unsigned 8-bit I,Q — into the deinterleaved
 * parallel {@code float[]} pair the DSP chain works in. Pure and allocation-free.
 *
 * <p>Parallel arrays rather than interleaved: a complex FIR reads I and Q as two independent
 * sequential streams, which both prefetches and vectorises better than a stride-2 walk over one.
 *
 * <p>The samples are centred on 127.5, not 128 — an unsigned byte's midpoint falls between two
 * codes. Using 128 leaves a small constant DC offset which the FM discriminator turns into a tone at
 * the tuned frequency: the classic "centre spike" of a cheap dongle.
 */
public final class SampleFormat {

    private static final float CENTER = 127.5f;
    private static final float SCALE = 1f / 127.5f;

    private SampleFormat() {}

    /**
     * Deinterleaves {@code byteCount} bytes of {@code raw} into {@code i} and {@code q}, scaled to
     * roughly [-1, 1].
     *
     * @return the number of complex samples written, i.e. {@code byteCount / 2}
     */
    public static int u8ToFloat(byte[] raw, int byteCount, float[] i, float[] q) {
        int pairs = byteCount / 2;
        if (i.length < pairs || q.length < pairs) {
            throw new IllegalArgumentException(
                    "output arrays too small: need " + pairs + ", got " + i.length + "/" + q.length);
        }
        for (int n = 0, b = 0; n < pairs; n++, b += 2) {
            i[n] = ((raw[b] & 0xFF) - CENTER) * SCALE;
            q[n] = ((raw[b + 1] & 0xFF) - CENTER) * SCALE;
        }
        return pairs;
    }
}
