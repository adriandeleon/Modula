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
        Scene scene = new Scene(pane, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(ModulaApp.class.getResource("modula.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("Modula");
        stage.setMinWidth(WINDOW_WIDTH);
        stage.setMinHeight(WINDOW_HEIGHT);
        stage.show();
        pane.requestFocus(); // so the arrow keys tune straight away

        installTray(stage, settings);
        installCloseBehaviour(stage, settings);
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
                                javafx.application.Platform.exit();
                            }))
                    .ifPresent(created -> javafx.application.Platform.runLater(() -> {
                        tray = created;
                        tray.setOnListen(() -> javafx.application.Platform.runLater(pane::togglePower));
                        pane.setTraySink(this::updateTray);
                    }));
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
    private void installCloseBehaviour(Stage stage, Settings settings) {
        stage.setOnCloseRequest(e -> {
            if (tray != null && settings.closeToTray()) {
                e.consume();
                stage.hide();
                return;
            }
            pane.dispose();
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

    @Override
    public void stop() {
        if (pane != null) {
            pane.dispose();
        }
        if (tray != null) {
            tray.dispose();
        }
        if (updates != null) {
            updates.shutdown();
        }
        // Both tray backends leave non-daemon threads behind (the AWT EDT, D-Bus workers), so a
        // clean FX shutdown is not enough on its own to end the process.
        javafx.application.Platform.exit();
        Runtime.getRuntime().halt(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
