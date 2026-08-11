package com.modula.radio;

import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.modula.audio.AudioSink;
import com.modula.band.BandPlan;
import com.modula.band.Region;
import com.modula.source.IqSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the reader is really decoupled from the DSP, and that losses are attributed to the right end.
 *
 * <p>{@link ByteRingTest} pins the buffer and {@link RadioEngineSeekTest} pins the seek wiring; this
 * pins the property the two threads exist for. It is deliberately <b>not</b> a timing assertion —
 * "the gap is under N milliseconds" both flakes on a loaded runner and fails to fail on a fast one,
 * because a machine that runs the chain at five times real time has a gap comfortably inside any
 * bound loose enough to be stable. Stalling the consumer outright and counting reads is decisive
 * instead: with one thread the answer is one, and with two it is however deep the ring is.
 */
class RadioEnginePipelineTest {

    private static final long START_HZ = 98_900_000L;

    /**
     * The point of the whole change. A blocked consumer must not stop the dongle being read, because
     * every moment the reader spends not reading is samples the hardware has already thrown away.
     */
    @Test
    @Timeout(30)
    void aStalledConsumerDoesNotStopTheDongleBeingRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingSink sink = new BlockingSink(release);
        CountingDongle dongle = new CountingDongle();
        RadioEngine engine = new RadioEngine(dongle, sink, Region.AMERICAS, BandPlan.fm(Region.AMERICAS));

        try {
            engine.start(START_HZ);

            // Wait for the DSP thread to be parked inside the sink, so the stall is real.
            assertTrue(sink.entered.await(20, TimeUnit.SECONDS), "the DSP thread never reached the sink");

            // While it is stuck there, the reader should fill the ring rather than stop at one block.
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (dongle.reads.get() < 4 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertTrue(
                    dongle.reads.get() >= 4,
                    "the dongle was read %d times while the DSP thread was blocked — the reader is still "
                            + "waiting on the consumer, so the USB transfer is idle whenever the chain is busy"
                                    .formatted(dongle.reads.get()));
        } finally {
            release.countDown();
            engine.stop();
        }
    }

    /** A full ring is the DSP falling behind real time, and must be reported as that and not as the card. */
    @Test
    @Timeout(30)
    void aStalledConsumerIsReportedAsLossBeforeDemodulationRatherThanAsAudio() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        BlockingSink sink = new BlockingSink(release);
        CountingDongle dongle = new CountingDongle();
        RadioEngine engine = new RadioEngine(dongle, sink, Region.AMERICAS, BandPlan.fm(Region.AMERICAS));
        // stop() publishes a last status once both threads have joined, which is where the totals land.
        engine.setListener(status -> sink.record(status.losses()));

        try {
            engine.start(START_HZ);
            assertTrue(sink.entered.await(20, TimeUnit.SECONDS));

            // Let the reader overrun the ring several times over.
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (dongle.reads.get() < 32 && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            release.countDown();
            engine.stop();

            RadioEngine.Losses losses = sink.lastLosses;
            assertTrue(losses != null, "no status was ever published");
            assertTrue(losses.iqDropped() > 0, "a full ring should be counted as loss before demodulation");
            assertEquals(0L, losses.audioDropped(), "the sound card was never the problem here");
        } finally {
            release.countDown();
            engine.stop();
        }
    }

    @Test
    void theDominantLossIsWhatGetsNamed() {
        assertTrue(new RadioEngine.Losses(500L, 1L, 1L, 1L, true).describe().contains("USB"));
        assertTrue(new RadioEngine.Losses(1L, 500L, 1L, 1L, true).describe().contains("before demodulation"));
        assertTrue(new RadioEngine.Losses(1L, 1L, 500L, 1L, true).describe().contains("audio device"));
        assertTrue(new RadioEngine.Losses(1L, 1L, 1L, 500L, true).describe().contains("silence"));
        assertEquals("", RadioEngine.Losses.NONE.describe(), "nothing lost is nothing to report");
    }

    /**
     * On a tie the most upstream cause wins: loss on the bus starves everything downstream, so any
     * other ordering sends the listener to fix a symptom.
     */
    @Test
    void aTiePrefersTheMostUpstreamCause() {
        assertTrue(new RadioEngine.Losses(9L, 9L, 9L, 9L, true).describe().contains("USB"));
        assertTrue(new RadioEngine.Losses(0L, 9L, 9L, 9L, true).describe().contains("before demodulation"));
        assertTrue(new RadioEngine.Losses(0L, 0L, 9L, 9L, true).describe().contains("audio device"));
    }

    /** A dongle that always has a block ready, and counts how often it is asked for one. */
    private static final class CountingDongle implements IqSource {

        final AtomicInteger reads = new AtomicInteger();
        private final byte[] block = new byte[DemodChain.BLOCK_PAIRS * 2];

        CountingDongle() {
            new Random(11).nextBytes(block);
        }

        @Override
        public void open() {}

        @Override
        public void setFrequency(long hz) {}

        @Override
        public void setSampleRate(int samplesPerSecond) {}

        @Override
        public void applyDefaultGain() {}

        @Override
        public int read(byte[] into) {
            reads.incrementAndGet();
            int n = Math.min(into.length, block.length);
            System.arraycopy(block, 0, into, 0, n);
            return n;
        }

        @Override
        public Range tunableRange() {
            return new Range(0L, Long.MAX_VALUE);
        }

        @Override
        public void close() {}
    }

    /** A sink that parks the DSP thread on its first write, standing in for any downstream stall. */
    private static final class BlockingSink implements AudioSink {

        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release;
        volatile RadioEngine.Losses lastLosses;

        BlockingSink(CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void open() {}

        @Override
        public void write(short[] pcm, int count) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void setVolume(double volume) {}

        @Override
        public void close() {}

        void record(RadioEngine.Losses losses) {
            lastLosses = losses;
        }
    }
}
