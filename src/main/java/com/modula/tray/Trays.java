package com.modula.tray;

import java.util.Locale;
import java.util.Optional;

/**
 * Backend chooser. Linux tries SNI (the modern D-Bus protocol) then falls back to the
 * legacy AWT tray; Windows/macOS use AWT. An unavailable tray is a normal outcome
 * (stock GNOME without the AppIndicator extension) — the app is fully usable without it.
 */
public final class Trays {

    private static final System.Logger LOG = System.getLogger(Trays.class.getName());

    private Trays() {}

    /** May block briefly (D-Bus/AWT init) — call from a background thread. */
    public static Optional<ModulaTray> create(Runnable onActivate, Runnable onQuit) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            try {
                return Optional.of(new SniTray(onActivate, onQuit));
            } catch (Exception e) {
                LOG.log(System.Logger.Level.INFO, "SNI tray unavailable: " + e.getMessage());
            }
        }
        try {
            return Optional.of(new AwtTray(onActivate, onQuit));
        } catch (Throwable t) {
            LOG.log(System.Logger.Level.INFO, "AWT tray unavailable: " + t.getMessage());
            return Optional.empty();
        }
    }
}
