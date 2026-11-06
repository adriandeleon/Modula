package com.modula.radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.modula.TestSignals;
import com.modula.TestSignals.Tone;
import com.modula.audio.AudioSink;
import com.modula.band.BandPlan;
import com.modula.band.Region;
import com.modula.source.IqSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seek driven through the real {@link RadioEngine}, against a fake dongle that only carries a station
 * on one frequency.
 *
 * <p>{@link ScannerTest} pins the decision logic; this pins the wiring around it — that the engine
 * actually consults the scanner, actually retunes the source, and actually stops on the station.
 * Those are separate failure modes, and the pure test cannot see either.
 */
class RadioEngineSeekTest {

    private static final long START_HZ = 101_500_000L;
    private static final long STATION_HZ = 102_100_000L; // three channels up

    @Test
    @Timeout(30)
    void seekingUpwardsLandsOnTheOnlyStation() throws Exception {
        FakeDongle dongle = new FakeDongle(STATION_HZ);
        CountingSink sink = new CountingSink();
        RadioEngine engine = new RadioEngine(dongle, sink, Region.AMERICAS, BandPlan.fm(Region.AMERICAS));

        try {
            engine.start(START_HZ);
            assertTrue(engine.isRunning());

            engine.seek(Scanner.Direction.UP);
            awaitSeekFinished(engine);

            assertEquals(STATION_HZ, engine.frequencyHz(), "seek should stop on the station");
            assertFalse(engine.isSeeking());
            assertTrue(dongle.tunedTo().contains(STATION_HZ), "the source must actually have been retuned");
            assertTrue(dongle.tunedTo().size() >= 3, "and stepped through the channels in between");
        } finally {
            engine.stop();
        }
    }

    @Test
    @Timeout(30)
    void seekingDownwardsWrapsAroundTheBandAndStillFindsIt() throws Exception {
        FakeDongle dongle = new FakeDongle(STATION_HZ);
        RadioEngine engine = new RadioEngine(dongle, new CountingSink(), Region.AMERICAS, BandPlan.fm(Region.AMERICAS));

        try {
            engine.start(START_HZ);
            engine.seek(Scanner.Direction.DOWN);
            awaitSeekFinished(engine);

            assertEquals(STATION_HZ, engine.frequencyHz(), "seeking down should wrap the band and find it");
        } finally {
            engine.stop();
        }
    }

    @Test
    @Timeout(30)
    void anEmptyBandReturnsToWhereTheSeekBegan() throws Exception {
        FakeDongle dongle = new FakeDongle(-1L); // no station anywhere
        RadioEngine engine = new RadioEngine(dongle, new CountingSink(), Region.AMERICAS, BandPlan.fm(Region.AMERICAS));

        try {
            engine.start(START_HZ);
            engine.seek(Scanner.Direction.UP);
            awaitSeekFinished(engine);

            assertEquals(START_HZ, engine.frequencyHz(), "an exhausted seek must put the listener back");
        } finally {
            engine.stop();
        }
    }

    /**
     * AM had no seek measure at all: it reported zero multiplex noise, which "below −20" never
     * meets, so a seek walked the whole band muted and gave up where it began. It now keys on the
     * envelope's ripple, which a carrier holds down and noise cannot.
     */
    @Test
    @Timeout(30)
    void seekingTheAirbandStopsOnACarrier() throws Exception {
        long start = 118_000_000L;
        long station = 118_075_000L; // three 25 kHz channels up
        FakeDongle dongle = new FakeDongle(station, amStation());
        RadioEngine engine = new RadioEngine(dongle, new CountingSink(), Region.AMERICAS, BandPlan.airband());

        try {
            engine.start(start);
            engine.seek(Scanner.Direction.UP);
            awaitSeekFinished(engine);

            assertEquals(station, engine.frequencyHz(), "seek should stop on the carrier");
        } finally {
            engine.stop();
        }
    }

    /** An AM carrier at the tuned frequency, 50% modulated by a 1 kHz tone, as the dongle's bytes. */
    private static byte[] amStation() {
        int pairs = DemodChain.BLOCK_PAIRS;
        byte[] raw = new byte[pairs * 2];
        for (int n = 0, b = 0; n < pairs; n++, b += 2) {
            double envelope = 0.4 * (1.0 + 0.5 * Math.sin(2.0 * Math.PI * 1_000.0 * n / DemodChain.INPUT_RATE));
            raw[b] = (byte) Math.round(envelope * 127.5 + 127.5);
            raw[b + 1] = (byte) Math.round(127.5);
        }
        return raw;
    }

    private static void awaitSeekFinished(RadioEngine engine) throws InterruptedException {
        long deadline = System.nanoTime() + 25_000_000_000L;
        // The seek has to start before it can finish, so wait for it to be observed running first.
        while (!engine.isSeeking() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        while (engine.isSeeking() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertFalse(engine.isSeeking(), "seek did not finish within the deadline");
    }

    /**
     * rtl_tcp keeps delivering the frequency it just left: measured, three 32 KB blocks of the old
     * station after a retune, then one mixed. Seek judged the third block after each step, so stepping
     * off a station it heard that station's tail and stopped one channel up. The source now declares
     * the bytes to discard; this dongle lags exactly as measured and declares as much.
     */
    @Test
    @Timeout(30)
    void seekingOffAStationOverALaggingSourceDoesNotStopOnItsTail() throws Exception {
        LaggingDongle dongle = new LaggingDongle(START_HZ, STATION_HZ, 3);
        RadioEngine engine = new RadioEngine(dongle, new CountingSink(), Region.AMERICAS, BandPlan.fm(Region.AMERICAS));

        try {
            engine.start(START_HZ);
            engine.seek(Scanner.Direction.UP);
            awaitSeekFinished(engine);

            assertEquals(STATION_HZ, engine.frequencyHz(), "seek should pass the station it left and find the next");
        } finally {
            engine.stop();
        }
    }

    /**
     * A seek's parked step overwritten by a tune to where the receiver already is — as the UI does when
     * its displayed frequency lags the engine by a step. Nothing retunes, so the signal the DSP thread
     * waits on after parking a step never comes; it used to wait for ever, and every later seek sat
     * muted without moving. The dongle here hands out one block per permit, which is what lets the
     * test put the tune between the step being parked and the reader thread applying it.
     */
    @Test
    @Timeout(30)
    void aSeekStepOverwrittenByATuneHomeDoesNotStallTheNextSeek() throws Exception {
        GatedDongle dongle = new GatedDongle(STATION_HZ);
        RadioEngine engine = new RadioEngine(dongle, new CountingSink(), Region.AMERICAS, BandPlan.fm(Region.AMERICAS));
        try {
            engine.start(START_HZ);
            dongle.release(5);
            Thread.sleep(300); // the DSP drains those and waits for more

            engine.seek(Scanner.Direction.UP);
            awaitSeeking(engine);
            dongle.release(1); // the reader hands it over and blocks; the DSP parks the first step
            Thread.sleep(300);

            engine.setFrequency(engine.frequencyHz()); // overwrite the parked step with "stay here"
            dongle.release(10);
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (engine.isSeeking() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertFalse(engine.isSeeking(), "the cancel should have landed");

            engine.seek(Scanner.Direction.UP);
            dongle.releaseFreely();
            awaitSeekFinished(engine);

            assertEquals(STATION_HZ, engine.frequencyHz(), "the next seek must still move and find the station");
        } finally {
            dongle.releaseFreely();
            engine.stop();
        }
    }

    private static void awaitSeeking(RadioEngine engine) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!engine.isSeeking() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(engine.isSeeking(), "the seek never started");
    }

    /** A station on one frequency, noise elsewhere, and one block per permit the test releases. */
    private static final class GatedDongle implements IqSource {

        private final java.util.concurrent.Semaphore permits = new java.util.concurrent.Semaphore(0);
        private final long stationHz;
        private final byte[] station;
        private final byte[] noise;
        private volatile long frequency;
        private volatile boolean free;

        GatedDongle(long stationHz) {
            this.stationHz = stationHz;
            this.station = TestSignals.fmModulate(
                    TestSignals.stereoMpx(
                            DemodChain.BLOCK_PAIRS, DemodChain.INPUT_RATE, new Tone(1_000.0, 0.5), Tone.SILENCE),
                    DemodChain.INPUT_RATE,
                    TestSignals.DEVIATION_HZ);
            this.noise = new byte[DemodChain.BLOCK_PAIRS * 2];
            new Random(7).nextBytes(noise);
        }

        void release(int blocks) {
            permits.release(blocks);
        }

        void releaseFreely() {
            free = true;
            permits.release(1_000_000);
        }

        @Override
        public void open() {}

        @Override
        public void setFrequency(long hz) {
            frequency = hz;
        }

        @Override
        public void setSampleRate(int samplesPerSecond) {}

        @Override
        public void applyDefaultGain() {}

        @Override
        public int read(byte[] into) throws java.io.IOException {
            try {
                if (!free) {
                    permits.acquire();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
            byte[] source = frequency == stationHz ? station : noise;
            int n = Math.min(into.length, source.length);
            System.arraycopy(source, 0, into, 0, n);
            return n;
        }

        @Override
        public Range tunableRange() {
            return new Range(0L, Long.MAX_VALUE);
        }

        @Override
        public void close() {}
    }

    /**
     * Stations on two frequencies, noise elsewhere — and after every retune, {@code lagBlocks} more
     * blocks of whatever it was tuned to before, as rtl_tcp does.
     */
    private static final class LaggingDongle implements IqSource {

        private final long first;
        private final long second;
        private final int lagBlocks;
        private final byte[] station;
        private final byte[] noise;
        private volatile long frequency;
        private volatile long previous;
        private volatile int stale;

        LaggingDongle(long first, long second, int lagBlocks) {
            this.first = first;
            this.second = second;
            this.lagBlocks = lagBlocks;
            this.station = TestSignals.fmModulate(
                    TestSignals.stereoMpx(
                            DemodChain.BLOCK_PAIRS, DemodChain.INPUT_RATE, new Tone(1_000.0, 0.5), Tone.SILENCE),
                    DemodChain.INPUT_RATE,
                    TestSignals.DEVIATION_HZ);
            this.noise = new byte[DemodChain.BLOCK_PAIRS * 2];
            new Random(7).nextBytes(noise);
        }

        @Override
        public void open() {}

        @Override
        public void setFrequency(long hz) {
            previous = frequency;
            frequency = hz;
            stale = previous == 0L ? 0 : lagBlocks;
        }

        @Override
        public void setSampleRate(int samplesPerSecond) {}

        @Override
        public void applyDefaultGain() {}

        @Override
        public int read(byte[] into) {
            long at = frequency;
            if (stale > 0) {
                stale--;
                at = previous;
            }
            byte[] source = at == first || at == second ? station : noise;
            int n = Math.min(into.length, source.length);
            System.arraycopy(source, 0, into, 0, n);
            return n;
        }

        @Override
        public int retuneFlushBytes() {
            return lagBlocks * DemodChain.BLOCK_PAIRS * 2;
        }

        @Override
        public Range tunableRange() {
            return new Range(0L, Long.MAX_VALUE);
        }

        @Override
        public void close() {}
    }

    /** A dongle that carries a modulated station on exactly one frequency, and noise everywhere else. */
    private static final class FakeDongle implements IqSource {

        private final byte[] station;
        private final byte[] noise;
        private final long stationHz;
        private final List<Long> tuned = Collections.synchronizedList(new ArrayList<>());
        private volatile long frequency;

        FakeDongle(long stationHz) {
            this(
                    stationHz,
                    TestSignals.fmModulate(
                            TestSignals.stereoMpx(
                                    DemodChain.BLOCK_PAIRS,
                                    DemodChain.INPUT_RATE,
                                    new Tone(1_000.0, 0.5),
                                    Tone.SILENCE),
                            DemodChain.INPUT_RATE,
                            TestSignals.DEVIATION_HZ));
        }

        FakeDongle(long stationHz, byte[] station) {
            this.stationHz = stationHz;
            this.station = station;
            int pairs = DemodChain.BLOCK_PAIRS;

            Random random = new Random(7);
            this.noise = new byte[pairs * 2];
            random.nextBytes(noise);
        }

        List<Long> tunedTo() {
            return List.copyOf(tuned);
        }

        @Override
        public void open() {}

        @Override
        public void setFrequency(long hz) {
            frequency = hz;
            tuned.add(hz);
        }

        @Override
        public void setSampleRate(int samplesPerSecond) {}

        @Override
        public void applyDefaultGain() {}

        @Override
        public int read(byte[] into) {
            byte[] source = frequency == stationHz ? station : noise;
            int n = Math.min(into.length, source.length);
            System.arraycopy(source, 0, into, 0, n);
            return n;
        }

        @Override
        public Range tunableRange() {
            return new Range(0L, Long.MAX_VALUE);
        }

        @Override
        public void close() {}
    }

    private static final class CountingSink implements AudioSink {
        private final AtomicInteger samples = new AtomicInteger();

        @Override
        public void open() {}

        @Override
        public void write(short[] pcm, int count) {
            samples.addAndGet(count);
        }

        @Override
        public void setVolume(double volume) {}

        @Override
        public void close() {}
    }
}
