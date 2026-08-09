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
import com.modula.ui.RadioPane;

/** Entry point. */
public final class ModulaApp extends Application {

    private static final Logger LOG = Logger.getLogger(ModulaApp.class.getName());

    /** Up from 440: the glass needs 64 characters of radio text on two lines without reflowing. */
    private static final int WINDOW_WIDTH = 520;

    private static final int WINDOW_HEIGHT = 560;

    private RadioPane pane;

    @Override
    public void start(Stage stage) {
        loadFonts();
        // AtlantaFX themes the five standard controls in use; modula.css does the rest.
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        pane = new RadioPane(ConfigStore.userDefault());
        Scene scene = new Scene(pane, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(ModulaApp.class.getResource("modula.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("Modula");
        stage.setMinWidth(WINDOW_WIDTH);
        stage.setMinHeight(WINDOW_HEIGHT);
        stage.setOnCloseRequest(e -> pane.dispose());
        stage.show();
        pane.requestFocus(); // so the arrow keys tune straight away
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
    }

    public static void main(String[] args) {
        launch(args);
    }
}
