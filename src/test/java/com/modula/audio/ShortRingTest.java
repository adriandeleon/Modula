package com.modula.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShortRingTest {

    @Test
    void roundTripsSamplesInOrder() {
        ShortRing ring = new ShortRing(16);
        ring.write(new short[] {1, 2, 3, 4}, 4);

        short[] out = new short[4];
        ring.read(out, 4);
        assertEquals(1, out[0]);
        assertEquals(4, out[3]);
        assertEquals(0, ring.available());
    }

    @Test
    void wrapsAroundTheEndOfTheBuffer() {
        ShortRing ring = new ShortRing(8);
        short[] scratch = new short[6];

        ring.write(new short[] {1, 2, 3, 4, 5, 6}, 6);
        ring.read(scratch, 6);
        ring.write(new short[] {7, 8, 9, 10, 11, 12}, 6);
        ring.read(scratch, 6);

        assertEquals(7, scratch[0], "reads must survive a wrap");
        assertEquals(12, scratch[5]);
    }

    /**
     * An overrun must drop samples rather than block. Blocking here would back-pressure into the
     * socket read and turn an audio hiccup into dropped RF.
     */
    @Test
    void dropsOnOverrunAndCountsIt() {
        ShortRing ring = new ShortRing(4);
        assertEquals(4, ring.write(new short[] {1, 2, 3, 4, 5, 6}, 6));
        assertEquals(2, ring.droppedSamples());
    }

    /** An underrun must fill with silence: the sound card needs samples on time regardless. */
    @Test
    void zeroFillsOnUnderrunAndCountsIt() {
        ShortRing ring = new ShortRing(8);
        ring.write(new short[] {5, 5}, 2);

        short[] out = new short[6];
        java.util.Arrays.fill(out, (short) 99);
        ring.read(out, 6);

        assertEquals(5, out[0]);
        assertEquals(0, out[2], "shortfall must be silence, not stale samples");
        assertEquals(0, out[5]);
        assertEquals(4, ring.underrunSamples());
    }

    @Test
    void clearDiscardsPendingAudio() {
        ShortRing ring = new ShortRing(8);
        ring.write(new short[] {1, 2, 3}, 3);
        ring.clear();
        assertEquals(0, ring.available());
    }
}
