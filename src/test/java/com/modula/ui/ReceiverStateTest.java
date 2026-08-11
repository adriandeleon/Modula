package com.modula.ui;

import com.modula.radio.RadioEngine;
import com.modula.rds.StationInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One case per state, and one per trap.
 *
 * <p>The states used to be implied by the order of a dozen statements in the interface rather than
 * named anywhere, which made each of the decisions below an afternoon of confusion if it regressed.
 */
class ReceiverStateTest {

    @Test
    void noStatusYetIsNotListening() {
        assertEquals(ReceiverState.NOT_LISTENING, ReceiverState.of(null, false));
    }

    @Test
    void aStoppedReceiverIsNotListening() {
        assertEquals(ReceiverState.NOT_LISTENING, ReceiverState.of(status(-20, false, false, 0, false), false));
    }

    @Test
    void runningWithNothingMeasuredYetIsStarting() {
        assertEquals(
                ReceiverState.STARTING,
                ReceiverState.of(status(com.modula.dsp.PowerMeter.FLOOR_DBFS, false, false, 0, true), false));
    }

    @Test
    void aLockedPilotIsStereo() {
        assertEquals(ReceiverState.STEREO, ReceiverState.of(status(-16, true, false, 0, true), false));
    }

    @Test
    void noPilotIsMono() {
        assertEquals(ReceiverState.MONO, ReceiverState.of(status(-16, false, false, 0, true), false));
    }

    @Test
    void aQuietSignalIsWeak() {
        assertEquals(ReceiverState.WEAK, ReceiverState.of(status(-52, false, false, 0, true), false));
    }

    @Test
    void seekingOutranksSignalQuality() {
        assertEquals(ReceiverState.SEEKING, ReceiverState.of(status(-16, true, true, 0, true), false));
    }

    // --- the traps -----------------------------------------------------------------------------

    /**
     * A weak signal that happens to hold a pilot is still weak — RDS will not arrive at that level,
     * so promising stereo would overstate what the listener is going to get.
     */
    @Test
    void weaknessIsJudgedBeforeStereo() {
        assertEquals(ReceiverState.WEAK, ReceiverState.of(status(-52, true, false, 0, true), false));
    }

    /** Dropped samples mean something in the path is not keeping up, which the listener can act on. */
    @Test
    void droppedSamplesAreAFault() {
        assertEquals(ReceiverState.FAULT, ReceiverState.of(status(-16, true, false, 44, true), false));
    }

    /**
     * The counters are cumulative, so a total that has stopped growing describes the past.
     *
     * <p>Testing them for "greater than zero" latched the receiver into a fault for the rest of the
     * session after one momentary overrun — a state that could never clear, on a signal that had since
     * been perfect for an hour.
     */
    @Test
    void aLossThatHasStoppedHappeningIsNoLongerAFault() {
        RadioEngine.Losses settled = new RadioEngine.Losses(0L, 0L, 44L, 0L, false);
        RadioEngine.Status status = new RadioEngine.Status(
                98_900_000L, -16, true, false, StationInfo.NONE, "", null, 0.0, 0.0, Double.NaN, settled, true);

        assertEquals(ReceiverState.STEREO, ReceiverState.of(status, false));
    }

    /**
     * The case that could not previously happen. Weakness was judged on channel power, which under an AGC
     * reports the AGC's target rather than the station — measured, −9.85 dBFS on an empty channel against
     * −9.75 for a weak one — so nothing ever approached the −45 dBFS threshold and WEAK was unreachable.
     * Quieting measures the station, so plenty of power with poor quieting now reads weak.
     */
    @Test
    void plentyOfPowerWithLittleQuietingIsWeak() {
        assertEquals(ReceiverState.WEAK, ReceiverState.of(withQuieting(-9, 10.0, true), false));
    }

    @Test
    void goodQuietingIsNotWeakAndStillReportsTheStationsPilot() {
        assertEquals(ReceiverState.STEREO, ReceiverState.of(withQuieting(-9, 24.0, true), false));
        assertEquals(ReceiverState.MONO, ReceiverState.of(withQuieting(-9, 24.0, false), false));
    }

    /**
     * AM has no multiplex noise to have suppressed, so quieting cannot judge it and power has to. It must
     * not read as permanently weak merely because the measurement it lacks is absent.
     */
    @Test
    void amFallsBackToPowerRatherThanReadingAsWeakForever() {
        RadioEngine.Status am = new RadioEngine.Status(
                1_000_000L,
                -16,
                false,
                false,
                StationInfo.NONE,
                "AM",
                null,
                0.0,
                0.0,
                Double.NaN,
                new RadioEngine.Losses(0L, 0L, 0L, 0L, false),
                true);

        assertEquals(ReceiverState.MONO, ReceiverState.of(am, false));
    }

    @Test
    void anEngineErrorIsAFaultEvenWithAGoodSignal() {
        assertEquals(ReceiverState.FAULT, ReceiverState.of(status(-16, true, false, 0, true), true));
    }

    /** Losing the reading is worse than showing one that has stopped updating. */
    @Test
    void aFaultStillCountsAsNotReceivingSoTheDialHoldsItsValue() {
        assertFalse(ReceiverState.FAULT.isReceiving());
        assertFalse(ReceiverState.FAULT.showsStation());
    }

    /** A seek makes the previous station's identity stale the moment it starts. */
    @Test
    void seekingHidesTheStationButKeepsReceiving() {
        assertTrue(ReceiverState.SEEKING.isReceiving());
        assertFalse(ReceiverState.SEEKING.showsStation());
    }

    @Test
    void onlyStereoAndMonoShowAStation() {
        for (ReceiverState state : ReceiverState.values()) {
            boolean expected = state == ReceiverState.STEREO || state == ReceiverState.MONO;
            assertEquals(expected, state.showsStation(), state.name());
        }
    }

    /** The sheet expresses the state, so each one needs a class the sheet can select on. */
    @Test
    void everyStateHasItsOwnStyleClass() {
        java.util.Set<String> classes = new java.util.HashSet<>();
        for (ReceiverState state : ReceiverState.values()) {
            String css = state.styleClass();
            assertTrue(css.startsWith("state-"), css);
            assertFalse(css.contains("_"), "style classes are hyphenated, not underscored: " + css);
            assertTrue(classes.add(css), "duplicate style class " + css);
        }
    }

    /** A status carrying a real quieting measurement, which is what weakness is now judged on. */
    private static RadioEngine.Status withQuieting(double signalDbfs, double quietingDb, boolean pilot) {
        double noiseDbfs = com.modula.radio.DemodChain.EMPTY_CHANNEL_NOISE_DBFS - quietingDb;
        return new RadioEngine.Status(
                98_900_000L,
                signalDbfs,
                pilot,
                false,
                StationInfo.NONE,
                "",
                null,
                noiseDbfs,
                pilot ? 1.0 : 0.0,
                Double.NaN,
                new RadioEngine.Losses(0L, 0L, 0L, 0L, false),
                true);
    }

    private static RadioEngine.Status status(
            double signalDbfs, boolean pilot, boolean seeking, long dropped, boolean running) {
        // Loss counters are cumulative; what makes it a fault is that it happened since the last
        // status, so a non-zero count here is presented as recent.
        RadioEngine.Losses losses = new RadioEngine.Losses(0L, 0L, dropped, 0L, dropped > 0L);
        return new RadioEngine.Status(
                98_900_000L,
                signalDbfs,
                pilot,
                seeking,
                StationInfo.NONE,
                "",
                null,
                0.0,
                0.0,
                Double.NaN,
                losses,
                running);
    }
}
