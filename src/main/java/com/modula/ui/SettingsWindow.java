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

    private static Stage stage;

    private SettingsWindow() {}

    /** Shows the window, reusing it if already open. {@code onApply} receives each change. */
    public static void show(Window owner, Settings current, Consumer<Settings> onApply) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }
        Holder holder = new Holder(current, onApply);

        VBox body = new VBox(4);
        body.setPadding(new Insets(20, 24, 18, 24));

        body.getChildren().add(heading("Appearance"));
        CheckBox daylight = new CheckBox("Daylight — invert the ground for a lit room");
        daylight.setSelected(current.daylight());
        daylight.selectedProperty()
                .addListener((o, was, now) -> holder.update(s -> new Settings(
                        s.frequencyHz(),
                        s.region(),
                        s.band(),
                        s.volume(),
                        s.stereo(),
                        now,
                        s.tray(),
                        s.closeToTray(),
                        s.updateCheck(),
                        s.lastUpdateCheck(),
                        s.recordingDirectory())));
        body.getChildren().addAll(daylight, gap());

        body.getChildren().add(heading("Recording"));
        TextField directory = new TextField(current.resolveRecordingDirectory().toString());
        directory.setPrefColumnCount(26);
        HBox.setHgrow(directory, Priority.ALWAYS);
        Button browse = new Button("Browse…");
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("Where should recordings go?");
            java.io.File chosen = chooser.showDialog(stage);
            if (chosen != null) {
                directory.setText(chosen.toString());
            }
        });
        Runnable commitDirectory = () -> holder.update(s -> new Settings(
                s.frequencyHz(),
                s.region(),
                s.band(),
                s.volume(),
                s.stereo(),
                s.daylight(),
                s.tray(),
                s.closeToTray(),
                s.updateCheck(),
                s.lastUpdateCheck(),
                directory.getText()));
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
        closeToTray.disableProperty().bind(tray.selectedProperty().not());
        tray.selectedProperty()
                .addListener((o, was, now) -> holder.update(s -> new Settings(
                        s.frequencyHz(),
                        s.region(),
                        s.band(),
                        s.volume(),
                        s.stereo(),
                        s.daylight(),
                        now,
                        s.closeToTray(),
                        s.updateCheck(),
                        s.lastUpdateCheck(),
                        s.recordingDirectory())));
        closeToTray
                .selectedProperty()
                .addListener((o, was, now) -> holder.update(s -> new Settings(
                        s.frequencyHz(),
                        s.region(),
                        s.band(),
                        s.volume(),
                        s.stereo(),
                        s.daylight(),
                        s.tray(),
                        now,
                        s.updateCheck(),
                        s.lastUpdateCheck(),
                        s.recordingDirectory())));
        Label trayNote = note("A tray change takes effect next launch.");
        body.getChildren().addAll(tray, closeToTray, trayNote, gap());

        body.getChildren().add(heading("Updates"));
        CheckBox updates = new CheckBox("Check for new releases");
        updates.setSelected(current.updateCheck());
        updates.setDisable(!AppInfo.RELEASES_API.startsWith("https://"));
        updates.selectedProperty()
                .addListener((o, was, now) -> holder.update(s -> new Settings(
                        s.frequencyHz(),
                        s.region(),
                        s.band(),
                        s.volume(),
                        s.stereo(),
                        s.daylight(),
                        s.tray(),
                        s.closeToTray(),
                        now,
                        s.lastUpdateCheck(),
                        s.recordingDirectory())));
        body.getChildren()
                .addAll(
                        updates,
                        note(
                                updates.isDisabled()
                                        ? "No release endpoint is configured for this build."
                                        : "Contacts the releases page over HTTPS once a day. Sends nothing."));

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(18, 0, 0, 0));
        body.getChildren().add(buttons);

        stage = new Stage();
        stage.initOwner(owner);
        stage.setTitle("Modula Settings");
        stage.setResizable(false);
        Scene scene = new Scene(body);
        Windows.styleLike(owner, scene);
        stage.setScene(scene);
        stage.show();
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

    private static Label note(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("settings-note");
        label.setWrapText(true);
        label.setMaxWidth(360);
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
