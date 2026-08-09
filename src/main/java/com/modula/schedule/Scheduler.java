package com.modula.schedule;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Which scheduled recording, if any, should be running.
 *
 * <p>Pure, so the awkward cases — an occurrence that crosses midnight, two schedules overlapping, a
 * disabled entry — are decided by something a test can drive at any instant rather than by a timer
 * a test would have to wait for.
 */
public final class Scheduler {

    private Scheduler() {}

    /**
     * The recording that should be running at {@code now}.
     *
     * <p>When several overlap, the one that started most recently wins. Any rule would be arbitrary;
     * this one at least means adding a schedule takes effect immediately rather than being ignored
     * until something older finishes.
     *
     * @return the active recording, or null when nothing should be running
     */
    public static Recording activeAt(List<Recording> schedules, LocalDateTime now) {
        if (schedules == null) {
            return null;
        }
        Recording winner = null;
        LocalDateTime latestStart = null;
        for (Recording candidate : schedules) {
            if (candidate == null || !candidate.isActiveAt(now)) {
                continue;
            }
            LocalDateTime began = startOfOccurrence(candidate, now);
            if (latestStart == null || began.isAfter(latestStart)) {
                latestStart = began;
                winner = candidate;
            }
        }
        return winner;
    }

    /** When the occurrence covering {@code now} began — today's, or yesterday's if it crossed midnight. */
    static LocalDateTime startOfOccurrence(Recording recording, LocalDateTime now) {
        LocalDateTime today = LocalDateTime.of(now.toLocalDate(), recording.start());
        return today.isAfter(now) ? today.minusDays(1) : today;
    }

    /** The soonest upcoming start across every schedule, for telling the user what is queued. */
    public static LocalDateTime nextStart(List<Recording> schedules, LocalDateTime from) {
        LocalDateTime soonest = null;
        if (schedules == null) {
            return null;
        }
        for (Recording recording : schedules) {
            if (recording == null) {
                continue;
            }
            LocalDateTime next = recording.nextStart(from);
            if (next != null && (soonest == null || next.isBefore(soonest))) {
                soonest = next;
            }
        }
        return soonest;
    }
}
