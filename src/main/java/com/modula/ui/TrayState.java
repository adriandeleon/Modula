package com.modula.ui;

/**
 * Whether this session has a tray icon, and if not, why not.
 *
 * <p>Two answers used to share one {@code false}: the listener turned the tray off, and the desktop
 * has nowhere to put one. Settings could only say the second, so everyone who had simply unticked
 * "Show a tray icon" was told their desktop had no system tray and that GNOME needs an extension —
 * advice for a problem they did not have, about a setting they had chosen.
 *
 * <p>Fixed at launch, like the tray itself: a change in Settings takes effect next launch, and that
 * is what the note says.
 */
public enum TrayState {
    /** Turned off in Settings, so none was asked for. */
    OFF,
    /** Asked for; the desktop has not answered yet. */
    STARTING,
    /** An icon appeared. */
    SHOWN,
    /** Asked for, and the desktop had nowhere to put it. */
    UNAVAILABLE;

    /** The state at launch, before the desktop has answered. */
    static TrayState atLaunch(boolean enabled) {
        return enabled ? STARTING : OFF;
    }

    /**
     * Whether "keep playing in the tray" can mean anything. Only a desktop with no tray rules it out;
     * with the tray merely off, it follows the tray box, so both can be set together for next launch.
     */
    boolean allowsCloseToTray() {
        return this != UNAVAILABLE;
    }

    /** Whether the note is a problem the listener should be told about, rather than information. */
    boolean isProblem() {
        return this == UNAVAILABLE;
    }

    /** What Settings says under the tray options. */
    String note() {
        return switch (this) {
            case UNAVAILABLE ->
                "No system tray on this desktop, so closing the window will quit Modula. "
                        + "GNOME needs the AppIndicator extension; KDE has one built in.";
            case OFF -> "The tray icon is off. A tray change takes effect next launch.";
            case STARTING, SHOWN -> "A tray change takes effect next launch.";
        };
    }
}
