package com.modula.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialFollowerTest {

    private static final long A = 101_500_000L;
    private static final long B = 101_700_000L;
    private static final long C = 101_900_000L;

    /** The bug: a status published before the reader applied the retune snapped the dial back. */
    @Test
    void aStatusFromBeforeTheRetuneDoesNotUndoTheTune() {
        DialFollower dial = new DialFollower();
        long shown = A;

        dial.tuned(B);
        shown = B;
        shown = dial.follow(A, shown);

        assertEquals(B, shown, "the dial snapped back to the old frequency");
    }

    /** And so a quick second press steps from where the first one went. */
    @Test
    void aQuickSecondStepStepsFromTheFirst() {
        DialFollower dial = new DialFollower();
        long shown = A;

        dial.tuned(B);
        shown = B;
        shown = dial.follow(A, shown); // stale, arriving between the two presses
        long next = shown + 200_000L;
        dial.tuned(next);
        shown = next;
        shown = dial.follow(B, shown); // the first retune landing late is stale now too

        assertEquals(C, shown);
        assertEquals(C, dial.follow(C, shown));
    }

    /** Once the tune lands the dial follows the engine again, which is what a seek needs. */
    @Test
    void theDialFollowsAgainOnceTheTuneLands() {
        DialFollower dial = new DialFollower();
        dial.tuned(B);

        assertEquals(B, dial.follow(B, B));
        assertEquals(C, dial.follow(C, B), "after landing, a different frequency is the engine's to report");
    }

    /** A seek moves the dial from status alone, including one started while a tune was still pending. */
    @Test
    void aSeekMovesTheDial() {
        DialFollower dial = new DialFollower();
        dial.tuned(B); // not yet landed

        dial.released(); // the listener pressed seek
        long shown = B;
        for (long hz : new long[] {B, C, C + 200_000L, C + 400_000L}) {
            shown = dial.follow(hz, shown);
            assertEquals(hz, shown, "seek must move the dial");
        }
    }

    /** A status with no frequency in it — a stopping engine — never moves the dial. */
    @Test
    void ignoresAStatusWithNoFrequency() {
        DialFollower dial = new DialFollower();
        assertEquals(A, dial.follow(0, A));
        assertEquals(A, dial.follow(-1, A));
    }
}
