package com.modula.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleFormatTest {

    @Test
    void mapsTheFullByteRangeOntoPlusMinusOne() {
        byte[] raw = {0, (byte) 255, (byte) 128, (byte) 127};
        float[] i = new float[2];
        float[] q = new float[2];

        assertEquals(2, SampleFormat.u8ToFloat(raw, raw.length, i, q));
        assertEquals(-1.0f, i[0], 1e-6f, "byte 0 is negative full scale");
        assertEquals(1.0f, q[0], 1e-6f, "byte 255 is positive full scale");
    }

    /**
     * The two codes either side of centre must straddle zero symmetrically. Centring on 128 instead
     * of 127.5 leaves a DC offset that the FM discriminator turns into an audible tone at the tuned
     * frequency — the "centre spike" of a cheap dongle.
     */
    @Test
    void centresBetweenTheTwoMiddleCodes() {
        byte[] raw = {(byte) 127, (byte) 128};
        float[] i = new float[1];
        float[] q = new float[1];
        SampleFormat.u8ToFloat(raw, raw.length, i, q);

        assertEquals(-q[0], i[0], 1e-6f, "codes 127 and 128 must be equal and opposite");
        assertTrue(Math.abs(i[0]) < 0.01f, "and both must be close to zero");
    }

    @Test
    void deinterleavesIAndQ() {
        byte[] raw = {10, 20, 30, 40, 50, 60};
        float[] i = new float[3];
        float[] q = new float[3];
        SampleFormat.u8ToFloat(raw, raw.length, i, q);

        assertTrue(i[0] < i[1] && i[1] < i[2], "I should carry bytes 10, 30, 50");
        assertTrue(q[0] < q[1] && q[1] < q[2], "Q should carry bytes 20, 40, 60");
        assertTrue(i[0] < q[0], "the first I byte precedes the first Q byte");
    }

    @Test
    void honoursByteCountRatherThanArrayLength() {
        byte[] raw = new byte[100];
        float[] i = new float[50];
        float[] q = new float[50];
        assertEquals(10, SampleFormat.u8ToFloat(raw, 20, i, q));
    }

    @Test
    void rejectsUndersizedOutputArrays() {
        byte[] raw = new byte[100];
        assertThrows(
                IllegalArgumentException.class,
                () -> SampleFormat.u8ToFloat(raw, raw.length, new float[10], new float[10]));
    }
}
