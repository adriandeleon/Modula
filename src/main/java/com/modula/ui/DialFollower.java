package com.modula.ui;

/**
 * Decides whether the dial follows the frequency a status reports.
 *
 * <p>The dial has two masters. A tune is the listener's: the dial moves the moment they press, and
 * the engine catches up when its reader next applies the retune. A seek is the engine's: it chooses
 * each channel, and the status is the only way the dial hears of it. Following every status served
 * the seek and broke the tune — a status published before the reader had applied a retune still
 * carried the old frequency, so it snapped the dial back, and a quick second arrow press stepped from
 * there instead of from where the first one went.
 *
 * <p>So a tune the UI asked for <b>holds the dial until a status confirms it</b>, and a seek releases
 * it. Until then a status for any other frequency is stale by definition: the engine applies a retune
 * the listener asked for over anything a seek had parked, and only a seek moves it without asking.
 *
 * <p>Pure, and FX-thread only, like the rest of the pane's state.
 */
final class DialFollower {

    private static final long NONE = -1;

    private long pendingHz = NONE;

    /** The listener asked for {@code hz}; statuses for anything else are stale until it lands. */
    void tuned(long hz) {
        pendingHz = hz;
    }

    /** A seek has started: the engine chooses the frequency now, so the dial follows it. */
    void released() {
        pendingHz = NONE;
    }

    /**
     * The frequency the dial should show after a status reporting {@code statusHz}.
     *
     * @param shownHz what the dial shows now, returned unchanged when the status is to be ignored
     */
    long follow(long statusHz, long shownHz) {
        if (statusHz <= 0) {
            return shownHz;
        }
        if (pendingHz != NONE) {
            if (statusHz != pendingHz) {
                return shownHz; // published before the retune landed
            }
            pendingHz = NONE;
        }
        return statusHz;
    }
}
