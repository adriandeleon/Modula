package com.modula;

import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import atlantafx.base.theme.PrimerDark;
import com.modula.config.ConfigStore;
import com.modula.config.Settings;
import com.modula.tray.ModulaTray;
import com.modula.tray.TrayDisplay;
import com.modula.tray.Trays;
import com.modula.ui.RadioPane;
import com.modula.update.UpdateCheck;
import com.modula.update.UpdateService;

/** Entry point. */
public final class ModulaApp extends Application {

    private static final Logger LOG = Logger.getLogger(ModulaApp.class.getName());

    /** Up from 440: the glass needs 64 characters of radio text on two lines without reflowing. */
    private static final int WINDOW_WIDTH = 520;

    private static final int WINDOW_HEIGHT = 560;

    private RadioPane pane;
    private ModulaTray tray;
    private UpdateService updates;

    @Override
    public void start(Stage stage) {
        loadFonts();
        // AtlantaFX themes the five standard controls in use; modula.css does the rest.
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        ConfigStore config = ConfigStore.userDefault();
        Settings settings = config.loadSettings();
        pane = new RadioPane(config);
        pane.setHostServices(getHostServices());
        Scene scene = new Scene(pane, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(ModulaApp.class.getResource("modula.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("Modula");
        stage.setMinWidth(WINDOW_WIDTH);
        stage.setMinHeight(WINDOW_HEIGHT);
        stage.getIcons().addAll(com.modula.ui.AppIcons.all());
        stage.show();
        pane.requestFocus(); // so the arrow keys tune straight away

        installTray(stage, settings);
        installCloseBehaviour(stage);
        checkForUpdates(config, settings);
    }

    /**
     * Brings up the tray off the FX thread, because both backends can block on D-Bus or AWT
     * initialisation and neither is worth a stalled first frame.
     */
    private void installTray(Stage stage, Settings settings) {
        if (!settings.tray()) {
            return;
        }
        Thread.ofPlatform().daemon().name("modula-tray-init").start(() -> {
            Trays.create(
                            () -> javafx.application.Platform.runLater(() -> {
                                stage.show();
                                stage.setIconified(false);
                                stage.toFront();
                            }),
                            () -> javafx.application.Platform.runLater(() -> {
                                pane.dispose();
                                quit();
                            }))
                    .ifPresentOrElse(
                            created -> javafx.application.Platform.runLater(() -> {
                                tray = created;
                                // Without this, hiding the last window ends the JavaFX runtime, which calls
                                // stop(), which halts the process — so closing to the tray quit the app no
                                // matter what the close handler did. It is set only once a tray really
                                // exists: with no icon to click, a hidden window would be unreachable and
                                // the process a zombie.
                                javafx.application.Platform.setImplicitExit(false);
                                tray.setOnListen(() -> javafx.application.Platform.runLater(pane::togglePower));
                                tray.setOnRecord(() -> javafx.application.Platform.runLater(pane::toggleRecording));
                                pane.setTraySink(this::updateTray);
                            }),
                            // Silence here is the bug the user actually hits: the setting is on, the
                            // checkbox says so, and closing still quits — because no tray ever
                            // appeared. A desktop without a StatusNotifierWatcher (GNOME without the
                            // AppIndicator extension) has nowhere to put the icon, and the
                            // application is the only thing that knows.
                            () -> javafx.application.Platform.runLater(pane::reportNoTray));
        });
    }

    private void updateTray(TrayDisplay display, String tooltip) {
        ModulaTray current = tray;
        if (current != null) {
            current.update(display, tooltip);
        }
    }

    /**
     * With a tray, closing hides and the radio keeps playing; without one, closing quits.
     *
     * <p>Hiding a window that leaves no icon behind is how an application becomes unreachable, so the
     * behaviour is conditional on a tray actually having appeared.
     */
    private void installCloseBehaviour(Stage stage) {
        stage.setOnCloseRequest(e -> {
            // The live setting, not the one captured at startup: this is a checkbox a user can
            // change while the window is open, and it would otherwise need a restart to take effect.
            if (tray != null && pane.closeToTray()) {
                e.consume();
                stage.hide();
                return;
            }
            // Teardown belongs in stop(), which JavaFX calls next. Doing it here as well meant
            // disposing everything twice.
            quit();
        });
    }

    private void checkForUpdates(ConfigStore config, Settings settings) {
        if (!settings.updateCheck() || !UpdateCheck.isDue(settings.lastUpdateCheck(), System.currentTimeMillis())) {
            return;
        }
        updates = new UpdateService(AppInfo.RELEASES_API);
        if (!updates.isConfigured()) {
            return;
        }
        // Stamp the attempt before making it, so a failing endpoint is retried tomorrow rather than
        // on every launch.
        config.saveSettings(settings.withLastUpdateCheck(System.currentTimeMillis()));
        updates.check(release -> pane.setAvailableUpdate(release));
    }

    /**
     * Bundles IBM Plex Mono rather than trusting the platform.
     *
     * <p>It is the only face allowed to show a number here — drawn for IBM's technical products, so
     * its digits read as a readout rather than as code — and every digit has to be tabular, because
     * 101.5 → 107.9 must not shift the layout and −41 → −9 must not make the meter row jump while
     * you are listening. A system fallback guarantees neither.
     */
    private static void loadFonts() {
        for (String name : new String[] {"IBMPlexMono-Regular.ttf", "IBMPlexMono-Bold.ttf"}) {
            try (InputStream in = ModulaApp.class.getResourceAsStream("fonts/" + name)) {
                if (in == null) {
                    LOG.log(Level.WARNING, "bundled font missing: {0}", name);
                    continue;
                }
                Font.loadFont(in, 12);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "could not load " + name, e);
            }
        }
    }

    /**
     * Ends the application, promptly.
     *
     * <p>The watchdog is armed <em>here</em> rather than inside {@code stop()}. Measured: closing the
     * window took 15–30 seconds to end the process even with a two-second watchdog in stop(), so
     * whatever holds it up happens before or around that callback. This runs from the FX thread at
     * the moment the decision is made, which is a context proven to execute, and it makes the delay
     * a bounded property of quitting rather than of the teardown's slowest step.
     */
    private void quit() {
        Thread.ofPlatform().daemon().name("modula-quit-watchdog").start(() -> {
            try {
                Thread.sleep(SHUTDOWN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(0);
        });
        javafx.application.Platform.exit();
    }

    /**
     * Quitting must be prompt.
     *
     * <p>Measured: closing the window took roughly 25 seconds to end the process, because tearing a
     * D-Bus connection down can block for as long as it likes. Teardown is best-effort — releasing
     * the dongle and the tray icon tidily is worth a moment, but not worth a process that appears to
     * hang — so a watchdog ends it regardless.
     */
    private static final long SHUTDOWN_GRACE_MILLIS = 2_000;

    @Override
    public void stop() {
        Thread.ofPlatform().daemon().name("modula-shutdown-watchdog").start(() -> {
            try {
                Thread.sleep(SHUTDOWN_GRACE_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Runtime.getRuntime().halt(0);
        });
        shutdown();
    }

    private void shutdown() {
        if (pane != null) {
            pane.dispose();
        }
        if (tray != null) {
            tray.dispose();
        }
        if (updates != null) {
            updates.shutdown();
        }
        // No Platform.exit() here: stop() runs *during* the toolkit's own shutdown, so calling it
        // again from inside is re-entrant. Both tray backends also leave non-daemon threads behind
        // (the AWT event thread, D-Bus workers), so returning normally would not end the process
        // either — halt is what actually ends it.
        Runtime.getRuntime().halt(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
