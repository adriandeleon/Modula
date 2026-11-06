package com.modula.schedule;

import org.jspecify.annotations.Nullable;

/**
 * Which scheduled recording is under way, and what it changed that its end should undo.
 *
 * <p>A schedule switches the receiver on when it finds it off, because recording tees what is
 * playing. Its end used to stop only the recording, so a radio nobody had switched on was left
 * playing to an empty room until someone noticed. The end now hands back what the start took: if the
 * schedule switched the receiver on, the schedule switches it off.
 *
 * <p>The listener's own hand wins. Pressing Listen or Stop during the slot makes the receiver theirs,
 * and the slot's end leaves it as they set it. Back-to-back schedules pass ownership along, since the
 * second found the receiver on only because the first had switched it on.
 *
 * <p>Pure, with the due recording passed in, so it is checked without a clock or a toolkit — the
 * same split as {@link Scheduler}.
 */
public final class ScheduleRun {

    /** What a tick of the schedule watch has to do. */
    public enum Change {
        NONE,
        /** A recording has become due, or a different one has taken over. */
        BEGIN,
        /** The window closed; stop the recording and leave the receiver as it was found. */
        END,
        /** The window closed, and the schedule had switched the receiver on: switch it off too. */
        END_AND_POWER_OFF
    }

    private @Nullable String activeId;
    private boolean ownsReceiver;

    /** Advances to the recording due now, or null when none is. */
    public Change tick(@Nullable Recording due) {
        if (due != null && !due.id().equals(activeId)) {
            activeId = due.id();
            return Change.BEGIN;
        }
        if (due == null && activeId != null) {
            activeId = null;
            boolean powerOff = ownsReceiver;
            ownsReceiver = false;
            return powerOff ? Change.END_AND_POWER_OFF : Change.END;
        }
        return Change.NONE;
    }

    /** The schedule found the receiver off and switched it on. */
    public void poweredReceiverOn() {
        ownsReceiver = true;
    }

    /** The listener pressed Listen or Stop: whatever the receiver is doing now is their choice. */
    public void listenerTookOver() {
        ownsReceiver = false;
    }

    /** Whether a scheduled recording is under way. */
    public boolean isActive() {
        return activeId != null;
    }
}
