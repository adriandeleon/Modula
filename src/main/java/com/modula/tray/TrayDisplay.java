package com.modula.tray;

/**
 * Pure description of what the tray icon should show.
 *
 * <p>Value-equal snapshots let the caller skip redundant repaints, which matters because status
 * arrives nine times a second and a tray repaint crosses a process boundary on Linux.
 *
 * @param text the tuned frequency, or null when not listening
 * @param listening whether audio is playing
 * @param attention whether something has gone wrong and needs saying
 */
public record TrayDisplay(String text, boolean listening, boolean attention, boolean recording) {

    /** Back-compatible: not recording. */
    public TrayDisplay(String text, boolean listening, boolean attention) {
        this(text, listening, attention, false);
    }

    public static TrayDisplay stopped() {
        return new TrayDisplay(null, false, false);
    }

    public static TrayDisplay listening(String frequency) {
        return listening(frequency, false);
    }

    public static TrayDisplay listening(String frequency, boolean recording) {
        return new TrayDisplay(frequency, true, false, recording);
    }

    public static TrayDisplay fault(String frequency) {
        return new TrayDisplay(frequency, false, true);
    }
}
