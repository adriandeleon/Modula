package com.modula.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DelayTest {

    @Test
    void shiftsTheSignalByTheRequestedNumberOfSamples() {
        Delay delay = new Delay(3);
        float[] in = {1, 2, 3, 4, 5, 6};
        float[] out = new float[in.length];
        delay.process(in, in.length, out);

        assertEquals(0f, out[0], "the first samples are the line's initial silence");
        assertEquals(0f, out[2]);
        assertEquals(1f, out[3]);
        assertEquals(3f, out[5]);
    }

    @Test
    void aZeroLengthDelayPassesThrough() {
        Delay delay = new Delay(0);
        float[] in = {1, 2, 3};
        float[] out = new float[3];
        delay.process(in, 3, out);
        assertEquals(1f, out[0]);
        assertEquals(3f, out[2]);
    }

    @Test
    void isContinuousAcrossBlockBoundaries() {
        float[] in = new float[200];
        for (int n = 0; n < in.length; n++) {
            in[n] = n;
        }

        Delay whole = new Delay(17);
        float[] a = new float[in.length];
        whole.process(in, in.length, a);

        Delay chunked = new Delay(17);
        float[] b = new float[in.length];
        int pos = 0;
        for (int chunk : new int[] {3, 1, 46, 100, 50}) {
            float[] slice = new float[chunk];
            System.arraycopy(in, pos, slice, 0, chunk);
            float[] tmp = new float[chunk];
            chunked.process(slice, chunk, tmp);
            System.arraycopy(tmp, 0, b, pos, chunk);
            pos += chunk;
        }

        assertEquals(in.length, pos, "chunks must cover the input exactly");
        for (int n = 0; n < in.length; n++) {
            assertEquals(a[n], b[n], 1e-6f, "block boundary changed the output at sample " + n);
        }
    }

    @Test
    void worksInPlace() {
        Delay delay = new Delay(2);
        float[] buf = {1, 2, 3, 4, 5};
        delay.process(buf, buf.length, buf);
        assertEquals(0f, buf[0]);
        assertEquals(1f, buf[2]);
        assertEquals(3f, buf[4]);
    }

    @Test
    void resetClearsTheLine() {
        Delay delay = new Delay(2);
        delay.process(new float[] {9, 9}, 2, new float[2]);
        delay.reset();

        float[] out = new float[2];
        delay.process(new float[] {1, 2}, 2, out);
        assertEquals(0f, out[0], "stale samples must not survive a retune");
    }

    @Test
    void rejectsANegativeLength() {
        assertThrows(IllegalArgumentException.class, () -> new Delay(-1));
    }
}
