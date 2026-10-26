package com.modula.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.modula.AppInfo;
import com.modula.config.Settings;
import org.jspecify.annotations.Nullable;

/**
 * The preferences that are not worth a control in the footer.
 *
 * <p>Deliberately short, and deliberately not a home for anything the product decision rules out —
 * there is no gain control, no bandwidth, no squelch and no FFT size here either. What lives here is
 * what a listener sets once and forgets: where recordings go, whether the tray is used, whether to
 * look for updates.
 *
 * <p>Applies live, like Editora: every control writes its setting and calls back immediately. There
 * is no OK/Cancel, because a preferences dialog that can be half-applied is a second state to reason
 * about for no benefit.
 */
public final class SettingsWindow {

    private static @Nullable Stage stage;

    private SettingsWindow() {}

    /** Shows the window, reusing it if already open. {@code onApply} receives each change. */
    public static void show(@Nullable Window owner, Settings current, Consumer<Settings> onApply) {
        show(owner, current, onApply, true, null);
    }

    /**
     * @param trayAvailable whether a tray icon actually appeared; when it did not, the tray options
     *     are shown disabled with the reason, because ticking a box that cannot do anything and then
     *     watching the window close the application is the confusing case this page exists to avoid
     * @param onAbout opens the About window; null leaves the button out rather than showing one that
     *     does nothing
     */
    public static void show(
            @Nullable Window owner,
            Settings current,
            Consumer<Settings> onApply,
            boolean trayAvailable,
            @Nullable Runnable onAbout) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }
        // Built first and named locally, so the handlers below close over a window that exists.
        Stage window = new Stage();
        stage = window;
        Holder holder = new Holder(current, onApply);

        VBox body = new VBox(4);
        body.setPadding(new Insets(20, 24, 18, 24));

        body.getChildren().add(heading("Appearance"));
        CheckBox daylight = new CheckBox("Daylight — invert the ground for a lit room");
        daylight.setSelected(current.daylight());
        daylight.selectedProperty().addListener((o, was, now) -> holder.update(s -> s.withDaylight(now)));
        body.getChildren().addAll(daylight, gap());

        body.getChildren().add(heading("Recording"));
        TextField directory = new TextField(current.resolveRecordingDirectory().toString());
        directory.setPrefColumnCount(26);
        HBox.setHgrow(directory, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Where should recordings go?");
            java.io.File chosen = chooser.showDialog(window);
            if (chosen != null) {
                directory.setText(chosen.toString());
            }
        });
        Runnable commitDirectory = () -> holder.update(s -> s.withRecordingDirectory(directory.getText()));
        directory.setOnAction(e -> commitDirectory.run());
        directory.focusedProperty().addListener((o, was, has) -> {
            if (!has) {
                commitDirectory.run();
            }
        });
        body.getChildren().add(new HBox(8, directory, browse));

        // Only formats that can actually be written are offered. ffmpeg is detected once; without it
        // the two compressed options would be choices that quietly produce a WAV.
        javafx.scene.control.ComboBox<com.modula.audio.RecordingFormat> format = new javafx.scene.control.ComboBox<>();
        format.getItems().setAll(com.modula.audio.RecordingFormat.values());
        format.setValue(com.modula.audio.RecordingFormat.of(current.recordingFormat()));
        format.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(com.modula.audio.RecordingFormat f) {
                return f == null ? "" : f.name() + " \u2014 " + f.description();
            }

            @Override
            public com.modula.audio.RecordingFormat fromString(String s) {
                return com.modula.audio.RecordingFormat.of(s);
            }
        });
        format.valueProperty().addListener((o, was, now) -> {
            if (now != null) {
                holder.update(settings -> settings.withRecordingFormat(now.name()));
            }
        });

        TextField encoder = new TextField(current.encoderPath());
        encoder.setPromptText("ffmpeg (leave blank to find it on PATH)");
        HBox.setHgrow(encoder, Priority.ALWAYS);
        Label encoderStatus = new Label();
        encoderStatus.setWrapText(true);
        encoderStatus.setPrefWidth(360);
        encoderStatus.setMaxWidth(360);
        encoderStatus.setMinHeight(Region.USE_PREF_SIZE);
        Runnable refreshEncoder = () -> {
            boolean found = com.modula.audio.Encoders.isAvailable(encoder.getText());
            encoderStatus.setText(found ? "ffmpeg found" : "ffmpeg not found — only WAV can be written");
            encoderStatus.getStyleClass().setAll(found ? "settings-git-found" : "settings-git-missing");
        };
        Runnable commitEncoder = () -> {
            holder.update(settings -> settings.withEncoderPath(encoder.getText()));
            com.modula.audio.Encoders.forgetDetection();
            refreshEncoder.run();
        };
        encoder.setOnAction(e -> commitEncoder.run());
        encoder.focusedProperty().addListener((o, was, has) -> {
            if (!has) {
                commitEncoder.run();
            }
        });
        refreshEncoder.run();

        body.getChildren().addAll(format, new HBox(8, encoder), encoderStatus, gap());

        body.getChildren().add(heading("System tray"));
        CheckBox tray = new CheckBox("Show a tray icon");
        tray.setSelected(current.tray());
        CheckBox closeToTray = new CheckBox("Closing the window keeps playing in the tray");
        closeToTray.setSelected(current.closeToTray());
        // Bound, or set — never both. setDisable on a bound property throws, and doing that here
        // would have crashed Settings for exactly the people with no tray.
        if (trayAvailable) {
            closeToTray.disableProperty().bind(tray.selectedProperty().not());
        } else {
            closeToTray.setDisable(true);
        }
        tray.selectedProperty().addListener((o, was, now) -> holder.update(s -> s.withTray(now)));
        closeToTray.selectedProperty().addListener((o, was, now) -> holder.update(s -> s.withCloseToTray(now)));
        Label trayNote = note(
                trayAvailable
                        ? "A tray change takes effect next launch."
                        : "No system tray on this desktop, so closing the window will quit Modula. "
                                + "GNOME needs the AppIndicator extension; KDE has one built in.");
        if (!trayAvailable) {
            trayNote.getStyleClass().setAll("settings-git-missing");
        }
        body.getChildren().addAll(tray, closeToTray, trayNote, gap());

        body.getChildren().add(heading("Updates"));
        CheckBox updates = new CheckBox("Check for new releases");
        updates.setSelected(current.updateCheck());
        updates.setDisable(!AppInfo.RELEASES_API.startsWith("https://"));
        updates.selectedProperty().addListener((o, was, now) -> holder.update(s -> s.withUpdateCheck(now)));
        body.getChildren()
                .addAll(
                        updates,
                        note(
                                updates.isDisabled()
                                        ? "No release endpoint is configured for this build."
                                        : "Contacts the releases page over HTTPS once a day. Sends nothing."));

        // The version where someone filing a report will look for it, and a way to the rest of the
        // identity. The spacer is the only elastic child, so the buttons can never be squeezed.
        Label version = new Label(AppInfo.NAME + " " + AppInfo.VERSION);
        version.getStyleClass().add("settings-version");
        version.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, version, spacer);
        if (onAbout != null) {
            Button about = new Button("About " + AppInfo.NAME + "…");
            about.setMinWidth(Region.USE_PREF_SIZE);
            about.setOnAction(e -> onAbout.run());
            buttons.getChildren().add(about);
        }
        Button close = new Button("Close");
        close.setMinWidth(Region.USE_PREF_SIZE);
        close.setOnAction(e -> window.close());
        buttons.getChildren().add(close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(18, 0, 0, 0));
        body.getChildren().add(buttons);

        window.initOwner(owner);
        window.setTitle("Modula Settings");
        window.setResizable(false);
        Scene scene = new Scene(body);
        Windows.styleLike(owner, scene);
        window.setScene(scene);
        window.show();
        window.sizeToScene();
    }

    /** Threads the current settings through each control so they compose rather than clobber. */
    private static final class Holder {
        private Settings settings;
        private final Consumer<Settings> onApply;

        Holder(Settings settings, Consumer<Settings> onApply) {
            this.settings = settings;
            this.onApply = onApply;
        }

        void update(java.util.function.UnaryOperator<Settings> change) {
            settings = change.apply(settings);
            onApply.accept(settings);
        }
    }

    private static Label heading(String text) {
        Label label = new Label(text.toUpperCase(java.util.Locale.ROOT));
        label.getStyleClass().add("settings-heading");
        return label;
    }

    /**
     * A wrapped explanatory line.
     *
     * <p>The minimum height must follow the preferred one. A VBox hands a wrapping label the height
     * it reports for a single line, so a note that wraps to three lines draws over whatever sits
     * beneath it — which is what the no-tray warning did to the checkbox above it.
     */
    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-note");
        label.setWrapText(true);
        // A DEFINITE width, not just a maximum. A wrapping label cannot report the height it needs
        // until it knows how wide it is, so with only a maxWidth it reports one line's worth, the
        // scene sizes itself to that, and the wrapped text draws over whatever follows.
        label.setPrefWidth(360);
        label.setMaxWidth(360);
        label.setMinHeight(Region.USE_PREF_SIZE);
        return label;
    }

    private static Region gap() {
        Region r = new Region();
        r.setMinHeight(12);
        return r;
    }

    /** Where recordings would go, for the palette's status message. */
    public static Path recordingDirectory(Settings settings) {
        return settings.resolveRecordingDirectory();
    }
}
