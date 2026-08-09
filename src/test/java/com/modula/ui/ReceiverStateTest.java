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

    /** Dropped samples mean the audio device is not keeping up, which the listener can act on. */
    @Test
    void droppedSamplesAreAFault() {
        assertEquals(ReceiverState.FAULT, ReceiverState.of(status(-16, true, false, 44, true), false));
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

    private static RadioEngine.Status status(
            double signalDbfs, boolean pilot, boolean seeking, long dropped, boolean running) {
        return new RadioEngine.Status(
                98_900_000L, signalDbfs, pilot, seeking, StationInfo.NONE, "", null, dropped, running);
    }
}
