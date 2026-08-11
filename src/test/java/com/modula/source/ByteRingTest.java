package com.modula.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteRingTest {

    private static final long NO_WAIT = 0L;

    @Test
    void writingThenReadingReturnsTheSameBytes() throws Exception {
        ByteRing ring = new ByteRing(64);
        byte[] written = {1, 2, 3, 4, 5, 6};

        assertEquals(6, ring.write(written, written.length));

        byte[] read = new byte[6];
        assertEquals(6, ring.read(read, 6, NO_WAIT));
        assertArrayEquals(written, read);
        assertEquals(0, ring.available());
    }

    @Test
    void capacityIsRoundedDownToWholePairs() {
        assertEquals(64, new ByteRing(65).capacity());
        assertEquals(64, new ByteRing(64).capacity());
    }

    @Test
    void readsHandOutWholePairsAndKeepTheOddByte() throws Exception {
        ByteRing ring = new ByteRing(64);
        ring.write(new byte[] {1, 2, 3, 4, 5}, 5);

        byte[] read = new byte[8];
        // Five bytes are two whole pairs and a straggler; the straggler waits for its partner.
        assertEquals(4, ring.read(read, 8, NO_WAIT));
        assertEquals(1, ring.available(), "the odd byte must stay in the ring");

        ring.write(new byte[] {6}, 1);
        assertEquals(2, ring.read(read, 8, NO_WAIT));
        assertEquals(5, read[0], "the straggler must be paired with the byte that followed it");
        assertEquals(6, read[1]);
    }

    /**
     * The invariant that makes an overrun survivable, tested on the thing it protects rather than on
     * the arithmetic that implements it.
     *
     * <p>The stream carries 0 at every I position and 1 at every Q position, so a parity shift is
     * directly visible: a pair read out as (1, 0) is a transposed sample, and once the stream has
     * shifted every later pair is transposed too. Asserting on the drop count per call would miss the
     * case that actually breaks it — a full ring, which has to discard an odd offer whole.
     */
    @Test
    void anOverrunNeverSwapsIAndQ() throws Exception {
        int[] chunks = {5, 3, 9, 1, 7, 2, 11, 1, 4, 3};
        for (int capacity = 4; capacity <= 16; capacity += 2) {
            for (int readSize = 2; readSize <= 8; readSize += 2) {
                ByteRing ring = new ByteRing(capacity);
                byte[] chunk = new byte[16];
                byte[] read = new byte[16];
                int produced = 0;
                int consumed = 0;

                for (int round = 0; round < chunks.length; round++) {
                    int count = chunks[round];
                    for (int k = 0; k < count; k++) {
                        chunk[k] = (byte) ((produced + k) & 1); // 0 = I, 1 = Q
                    }
                    produced += count;
                    ring.write(chunk, count);

                    int n = ring.read(read, readSize, NO_WAIT);
                    for (int k = 0; k < n; k++) {
                        assertEquals(
                                (consumed + k) & 1,
                                read[k],
                                ("capacity %d, reads of %d, round %d: byte %d came back as %d — "
                                                + "I and Q have transposed, which conjugates the signal for good")
                                        .formatted(capacity, readSize, round, consumed + k, read[k]));
                    }
                    consumed += n;
                }
                assertEquals(0, consumed & 1, "only whole pairs should ever have been handed out");
            }
        }
    }

    @Test
    void aForcedOddDropIsPaidBackByTheNextWrite() throws Exception {
        ByteRing ring = new ByteRing(8);
        assertEquals(8, ring.write(new byte[8], 8));

        // Full, so all three bytes go — an odd gap that cannot be rounded off here.
        assertEquals(0, ring.write(new byte[3], 3));
        assertEquals(3, ring.droppedBytes());

        // Room again: one extra byte is given up so the total gap becomes even.
        byte[] scratch = new byte[8];
        assertEquals(8, ring.read(scratch, 8, NO_WAIT));
        assertEquals(3, ring.write(new byte[4], 4), "one of the four pays the debt");
        assertEquals(4, ring.droppedBytes());
        assertTrue(ring.droppedBytes() % 2 == 0, "the cumulative gap is what has to be even");
    }

    @Test
    void aFullRingAcceptsNothingAndCountsTheWholeOffer() {
        ByteRing ring = new ByteRing(8);
        assertEquals(8, ring.write(new byte[8], 8));

        assertEquals(0, ring.write(new byte[4], 4));
        assertEquals(4, ring.droppedBytes());
    }

    @Test
    void readsAndWritesWrapAroundTheEnd() throws Exception {
        ByteRing ring = new ByteRing(8);
        byte[] scratch = new byte[8];

        ring.write(new byte[] {1, 2, 3, 4, 5, 6}, 6);
        assertEquals(4, ring.read(scratch, 4, NO_WAIT));

        // Head is now at 4, so this write must run off the end and continue at zero.
        ring.write(new byte[] {7, 8, 9, 10}, 4);
        assertEquals(6, ring.read(scratch, 6, NO_WAIT));
        assertArrayEquals(new byte[] {5, 6, 7, 8, 9, 10}, java.util.Arrays.copyOf(scratch, 6));
    }

    @Test
    void retuningDiscardsTheOldStationAndBumpsTheGeneration() throws Exception {
        ByteRing ring = new ByteRing(64);
        ring.write(new byte[] {1, 2, 3, 4}, 4);

        byte[] read = new byte[4];
        ring.read(read, 4, NO_WAIT);
        long before = ring.lastReadGeneration();

        ring.retuned();
        assertEquals(0, ring.available(), "buffered samples from the old frequency must be discarded");

        ring.write(new byte[] {9, 9}, 2);
        assertEquals(2, ring.read(read, 4, NO_WAIT));
        assertNotEquals(before, ring.lastReadGeneration(), "the consumer must be able to see that it retuned");
    }

    @Test
    void anEmptyRingReportsNothingUntilItIsFinished() throws Exception {
        ByteRing ring = new ByteRing(64);
        byte[] read = new byte[4];

        assertEquals(0, ring.read(read, 4, NO_WAIT), "still running, just nothing buffered");

        ring.finish();
        assertEquals(-1, ring.read(read, 4, NO_WAIT), "finished and drained is the end of the stream");
    }

    @Test
    void aFinishedRingIsStillDrainedBeforeItEnds() throws Exception {
        ByteRing ring = new ByteRing(64);
        ring.write(new byte[] {1, 2}, 2);
        ring.finish();

        byte[] read = new byte[4];
        assertEquals(2, ring.read(read, 4, NO_WAIT), "what arrived before the end must still be delivered");
        assertEquals(-1, ring.read(read, 4, NO_WAIT));
    }

    @Test
    @Timeout(10)
    void aBlockedReadWakesWhenBytesArrive() throws Exception {
        ByteRing ring = new ByteRing(64);
        byte[] read = new byte[4];

        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(50);
                ring.write(new byte[] {1, 2, 3, 4}, 4);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        // Would return 0 immediately if the wait were not real.
        assertEquals(4, ring.read(read, 4, 5_000L));
        producer.join();
    }

    @Test
    @Timeout(10)
    void aBlockedReadWakesWhenTheSourceEnds() throws Exception {
        ByteRing ring = new ByteRing(64);

        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(50);
                ring.finish();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        producer.start();

        assertEquals(-1, ring.read(new byte[4], 4, 5_000L), "finishing must release a waiting consumer");
        producer.join();
    }

    @Test
    void clearingResetsTheStreamSoTheRingCanBeReused() throws Exception {
        ByteRing ring = new ByteRing(64);
        ring.write(new byte[] {1, 2}, 2);
        ring.finish();

        ring.clear();
        assertEquals(0, ring.available());
        assertEquals(0, ring.read(new byte[4], 4, NO_WAIT), "a cleared ring is running again, not finished");
    }
}
