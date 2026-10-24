package com.modula.radio;

import com.modula.band.BandPlan;
import com.modula.band.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole seek behaviour, driven by handing the state machine numbers. No hardware, no threads, no
 * clock — which is the point of keeping {@link Scanner} pure.
 */
class ScannerTest {

    private static final double STATION = -35.0;
    private static final double EMPTY = -6.0;

    private static final SeekPolicy POLICY = new SeekPolicy(-20.0, 3);

    private final BandPlan band = BandPlan.fm(Region.AMERICAS);

    @Test
    void doesNothingUntilStarted() {
        Scanner scanner = newScanner();
        assertFalse(scanner.isScanning());
        assertEquals(Scanner.Action.WAIT, scanner.onBlock(101_500_000L, STATION).action());
    }

    /** You are already listening to the current channel, so seek means "find me the next one". */
    @Test
    void skipsThechannelItStartsOn() {
        Scanner scanner = newScanner();
        scanner.start(101_500_000L, Scanner.Direction.UP);

        Scanner.Step first = scanner.onBlock(101_500_000L, STATION);
        assertEquals(Scanner.Action.RETUNE, first.action(), "must move off the starting channel even if it is strong");
        assertEquals(101_700_000L, first.frequencyHz());
    }

    @Test
    void discardsTheRetuneTransientBeforeJudgingAChannel() {
        Scanner scanner = newScanner();
        scanner.start(101_500_000L, Scanner.Direction.UP);
        scanner.onBlock(101_500_000L, EMPTY); // the first move

        // A settling chain can report anything; the scanner must not act on it.
        assertEquals(Scanner.Action.WAIT, scanner.onBlock(101_700_000L, STATION).action());
        assertEquals(Scanner.Action.WAIT, scanner.onBlock(101_700_000L, STATION).action());
        assertEquals(
                Scanner.Action.FOUND,
                scanner.onBlock(101_700_000L, STATION).action(),
                "the third block is the first one that counts");
    }

    @Test
    void stopsOnTheFirstStationUpTheBand() {
        Scanner scanner = newScanner();
        scanner.start(101_500_000L, Scanner.Direction.UP);

        long tuned = 101_500_000L;
        Scanner.Step step = scanner.onBlock(tuned, EMPTY);
        tuned = step.frequencyHz();

        // Two empty channels, then a station.
        tuned = settleAnd(scanner, tuned, EMPTY).frequencyHz();
        tuned = settleAnd(scanner, tuned, EMPTY).frequencyHz();
        Scanner.Step found = settleAnd(scanner, tuned, STATION);

        assertEquals(Scanner.Action.FOUND, found.action());
        assertEquals(102_100_000L, found.frequencyHz(), "101.5 + three channels of 200 kHz");
        assertFalse(scanner.isScanning(), "a completed seek must end itself");
    }

    @Test
    void seeksDownwardsToo() {
        Scanner scanner = newScanner();
        scanner.start(101_500_000L, Scanner.Direction.DOWN);

        Scanner.Step first = scanner.onBlock(101_500_000L, EMPTY);
        assertEquals(101_300_000L, first.frequencyHz());

        Scanner.Step found = settleAnd(scanner, first.frequencyHz(), STATION);
        assertEquals(Scanner.Action.FOUND, found.action());
        assertEquals(101_300_000L, found.frequencyHz());
    }

    /** An empty band must terminate and put the listener back, not spin forever. */
    @Test
    void givesUpAfterAWholeBandAndReturnsToTheStart() {
        Scanner scanner = newScanner();
        long start = 101_500_000L;
        scanner.start(start, Scanner.Direction.UP);

        long tuned = scanner.onBlock(start, EMPTY).frequencyHz();
        Scanner.Step step = null;
        for (int i = 0; i < band.channelCount() + 5; i++) {
            step = settleAnd(scanner, tuned, EMPTY);
            if (step.action() == Scanner.Action.EXHAUSTED) {
                break;
            }
            tuned = step.frequencyHz();
        }

        assertEquals(Scanner.Action.EXHAUSTED, step.action(), "an empty band must terminate");
        assertEquals(start, step.frequencyHz(), "and return to where the scan began");
        assertFalse(scanner.isScanning());
    }

    @Test
    void wrapsAroundTheEndOfTheBand() {
        Scanner scanner = newScanner();
        scanner.start(band.maxHz(), Scanner.Direction.UP);
        assertEquals(band.minHz(), scanner.onBlock(band.maxHz(), EMPTY).frequencyHz());
    }

    @Test
    void cancelStopsTheScanWhereItIs() {
        Scanner scanner = newScanner();
        scanner.start(101_500_000L, Scanner.Direction.UP);
        scanner.onBlock(101_500_000L, EMPTY);
        assertTrue(scanner.isScanning());

        scanner.cancel();
        assertFalse(scanner.isScanning());
        assertEquals(Scanner.Action.WAIT, scanner.onBlock(101_700_000L, STATION).action());
    }

    @Test
    void policyThresholdsOnNoiseBeingLowNotHigh() {
        assertTrue(POLICY.isStation(-35.0), "a quiet multiplex means a strong carrier");
        assertFalse(POLICY.isStation(-6.0), "a noisy multiplex means an empty channel");
    }

    private Scanner newScanner() {
        return new Scanner(band, POLICY);
    }

    /** Feeds the settle blocks then the measurement block, returning the decision. */
    private static Scanner.Step settleAnd(Scanner scanner, long tuned, double noiseDbfs) {
        for (int i = 0; i < POLICY.settleBlocks() - 1; i++) {
            scanner.onBlock(tuned, noiseDbfs);
        }
        return scanner.onBlock(tuned, noiseDbfs);
    }

    /**
     * AM's measure is envelope ripple, where noise alone reads a fixed −5.6 dB. The AM threshold must
     * reject that and accept a carrier modulated as hard as a loud, processed station is — and each
     * band must get its own threshold, since FM's −20 is a different quantity.
     */
    @Test
    void amSeekTellsACarrierFromAnEmptyChannel() {
        double emptyChannel = 10.0 * Math.log10(4.0 / Math.PI - 1.0);
        assertFalse(SeekPolicy.AM_DEFAULT.isStation(emptyChannel), "an empty channel is not a station");
        assertTrue(SeekPolicy.AM_DEFAULT.isStation(20.0 * Math.log10(0.40)), "40% rms modulation is a station");

        assertEquals(SeekPolicy.AM_DEFAULT, SeekPolicy.defaultFor(com.modula.band.Modulation.AM));
        assertEquals(SeekPolicy.DEFAULT, SeekPolicy.defaultFor(com.modula.band.Modulation.FM));
    }
}
