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

    /** A dongle that carries a modulated station on exactly one frequency, and noise everywhere else. */
    private static final class FakeDongle implements IqSource {

        private final byte[] station;
        private final byte[] noise;
        private final long stationHz;
        private final List<Long> tuned = Collections.synchronizedList(new ArrayList<>());
        private volatile long frequency;

        FakeDongle(long stationHz) {
            this.stationHz = stationHz;
            int pairs = DemodChain.BLOCK_PAIRS;
            this.station = TestSignals.fmModulate(
                    TestSignals.stereoMpx(pairs, DemodChain.INPUT_RATE, new Tone(1_000.0, 0.5), Tone.SILENCE),
                    DemodChain.INPUT_RATE,
                    TestSignals.DEVIATION_HZ);

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
        public void setGainAuto() {}

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
