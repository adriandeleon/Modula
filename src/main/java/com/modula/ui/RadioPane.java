package com.modula.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.modula.audio.JavaSoundSink;
import com.modula.band.BandPlan;
import com.modula.band.Region;
import com.modula.config.ConfigStore;
import com.modula.config.Preset;
import com.modula.config.Presets;
import com.modula.config.Settings;
import com.modula.radio.DemodChain;
import com.modula.radio.RadioEngine;
import com.modula.radio.Scanner;
import com.modula.rds.ProgramType;
import com.modula.rds.StationInfo;
import com.modula.source.IqSource;
import com.modula.source.RtlTcpSource;

/**
 * The whole user interface: a frequency, tune and seek buttons, a preset bank, volume, a signal bar.
 *
 * <p>Deliberately not an SDR panel. No demodulator picker, no filter-bandwidth slider, no gain or AGC
 * controls, no FFT settings — those are what make general SDR software unapproachable, and none of
 * them have a right answer a listener should have to supply. Gain is left to the dongle's AGC and the
 * bandwidth is fixed by {@link DemodChain}.
 *
 * <p>Built in code rather than FXML because it is still small enough to read in one screen; move it
 * to FXML once the spectrum strip lands.
 */
public final class RadioPane extends VBox {

    private static final double MHZ = 1_000_000.0;

    private final Label frequencyLabel = new Label();
    private final Label statusLabel = new Label();
    private final Label stereoLabel = new Label("STEREO");
    private final Label stationLabel = new Label();
    private final Label radioTextLabel = new Label();
    private final ProgressBar signalBar = new ProgressBar(0);
    private final Button powerButton = new Button("Listen");
    private final Button saveButton = new Button("★ Save");
    private final Button removeButton = new Button("Remove");
    private final ComboBox<Region> regionCombo = new ComboBox<>();
    private final TextField frequencyField = new TextField();
    private final TextField nameField = new TextField();
    private final Slider volumeSlider = new Slider(0, 1, 0.7);
    private final CheckBox stereoCheck = new CheckBox("Stereo");
    private final ListView<Preset> presetList = new ListView<>();

    // Status arrives on the receive thread; coalesce to at most one FX repaint pending at a time.
    private final AtomicReference<RadioEngine.Status> latestStatus = new AtomicReference<>();
    private final AtomicBoolean statusPending = new AtomicBoolean();

    private final ConfigStore config;
    private List<Preset> presets;

    private Region region;
    private BandPlan band;
    private long frequencyHz;

    private RadioEngine engine;
    private JavaSoundSink sink;

    public RadioPane(ConfigStore config) {
        this.config = config;

        Settings settings = config.loadSettings();
        this.region = settings.region();
        this.band = BandPlan.fm(region);
        this.frequencyHz = band.snap(settings.frequencyHz());
        this.presets = new ArrayList<>(config.loadPresets());

        setSpacing(12);
        setPadding(new Insets(18));
        setAlignment(Pos.TOP_CENTER);
        setPrefWidth(440);

        frequencyLabel.setStyle("-fx-font-size: 44px; -fx-font-weight: bold;");
        statusLabel.setStyle("-fx-opacity: 0.7;");
        signalBar.setPrefWidth(280);
        setPilotLocked(false);

        getChildren()
                .addAll(
                        buildTuning(),
                        buildStationInfo(),
                        buildDirectEntry(),
                        buildMeter(),
                        powerButton,
                        buildPresets(),
                        buildVolume(),
                        buildSettings(),
                        statusLabel);

        powerButton.setOnAction(e -> togglePower());
        volumeSlider.setValue(settings.volume());
        stereoCheck.setSelected(settings.stereo());
        regionCombo.setValue(region);

        refreshFrequency();
        refreshPresets();
        installKeyboardTuning();
        setStatusText("Start `rtl_tcp -a 127.0.0.1`, then press Listen.");
    }

    /** Stops the receiver, releases the device and persists the session. */
    public void dispose() {
        RadioEngine e = engine;
        engine = null;
        sink = null;
        if (e != null) {
            e.stop();
        }
        config.saveSettings(new Settings(frequencyHz, region, volumeSlider.getValue(), stereoCheck.isSelected()));
    }

    // --- layout --------------------------------------------------------------------------------

    private HBox buildTuning() {
        Button seekDown = new Button("◀◀");
        Button down = new Button("◀");
        Button up = new Button("▶");
        Button seekUp = new Button("▶▶");

        seekDown.setTooltip(new javafx.scene.control.Tooltip("Seek down to the next station"));
        seekUp.setTooltip(new javafx.scene.control.Tooltip("Seek up to the next station"));

        down.setOnAction(e -> tuneTo(band.previous(frequencyHz)));
        up.setOnAction(e -> tuneTo(band.next(frequencyHz)));
        seekDown.setOnAction(e -> seek(Scanner.Direction.DOWN));
        seekUp.setOnAction(e -> seek(Scanner.Direction.UP));

        HBox box = new HBox(10, seekDown, down, frequencyLabel, up, seekUp);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /**
     * The RDS display. Both labels stay in the layout when empty rather than being hidden, so the
     * controls below them do not jump every time a station's radio text arrives or changes length.
     */
    private VBox buildStationInfo() {
        stationLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        stationLabel.setMinHeight(20);

        radioTextLabel.setStyle("-fx-font-size: 12px; -fx-opacity: 0.75;");
        radioTextLabel.setWrapText(true);
        radioTextLabel.setMinHeight(32);
        radioTextLabel.setMaxWidth(380);
        radioTextLabel.setAlignment(Pos.CENTER);
        radioTextLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);

        VBox box = new VBox(2, stationLabel, radioTextLabel);
        box.setAlignment(Pos.CENTER);
        box.setMinHeight(56);
        return box;
    }

    private HBox buildDirectEntry() {
        frequencyField.setPromptText("e.g. 101.5");
        frequencyField.setPrefColumnCount(7);
        frequencyField.setOnAction(e -> tuneFromField());
        Button go = new Button("Tune");
        go.setOnAction(e -> tuneFromField());

        HBox box = new HBox(8, new Label("MHz"), frequencyField, go);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private HBox buildMeter() {
        HBox box = new HBox(10, signalBar, stereoLabel);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private VBox buildPresets() {
        presetList.setPrefHeight(150);
        presetList.setPlaceholder(new Label("No saved stations yet — tune one in and press Save."));
        presetList.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Preset preset, boolean empty) {
                super.updateItem(preset, empty);
                setText(empty || preset == null ? null : preset.label());
            }
        });
        presetList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                tuneToSelectedPreset();
            }
        });
        presetList.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                tuneToSelectedPreset();
            } else if (e.getCode() == KeyCode.DELETE) {
                removeSelectedPreset();
            }
        });

        nameField.setPromptText("Station name (optional)");
        HBox.setHgrow(nameField, Priority.ALWAYS);
        saveButton.setOnAction(e -> saveCurrentAsPreset());
        removeButton.setOnAction(e -> removeSelectedPreset());

        HBox controls = new HBox(8, nameField, saveButton, removeButton);
        controls.setAlignment(Pos.CENTER);

        VBox box = new VBox(6, presetList, controls);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private HBox buildVolume() {
        volumeSlider.setPrefWidth(200);
        volumeSlider.valueProperty().addListener((obs, old, value) -> {
            JavaSoundSink s = sink;
            if (s != null) {
                s.setVolume(value.doubleValue());
            }
        });
        HBox box = new HBox(8, new Label("Volume"), volumeSlider);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private HBox buildSettings() {
        regionCombo.getItems().setAll(Region.values());
        regionCombo.valueProperty().addListener((obs, old, value) -> setRegion(value));

        // Forcing mono is a real radio's button, not a debug switch: stereo raises the noise floor by
        // roughly 20 dB, so a weak station is often more listenable without it.
        stereoCheck.selectedProperty().addListener((obs, old, on) -> {
            RadioEngine e = engine;
            if (e != null) {
                e.setStereoEnabled(on);
            }
        });

        HBox box = new HBox(8, new Label("Region"), regionCombo, stereoCheck);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** Left/right step a channel, shift+left/right seek — the gestures a radio's rocker switch has. */
    private void installKeyboardTuning() {
        setOnKeyPressed(e -> {
            if (e.getTarget() instanceof TextField) {
                return; // typing a frequency or a name
            }
            switch (e.getCode()) {
                case LEFT -> {
                    if (e.isShiftDown()) {
                        seek(Scanner.Direction.DOWN);
                    } else {
                        tuneTo(band.previous(frequencyHz));
                    }
                    e.consume();
                }
                case RIGHT -> {
                    if (e.isShiftDown()) {
                        seek(Scanner.Direction.UP);
                    } else {
                        tuneTo(band.next(frequencyHz));
                    }
                    e.consume();
                }
                default -> {}
            }
        });
    }

    // --- actions -------------------------------------------------------------------------------

    private void togglePower() {
        if (engine != null && engine.isRunning()) {
            dispose();
            powerButton.setText("Listen");
            signalBar.setProgress(0);
            setPilotLocked(false);
            setStatusText("Stopped.");
            return;
        }
        start();
    }

    private void start() {
        IqSource source = RtlTcpSource.localhost();
        if (!source.tunableRange().contains(frequencyHz)) {
            setStatusText(describeOutOfRange());
            return;
        }
        JavaSoundSink audio = new JavaSoundSink(DemodChain.AUDIO_RATE, DemodChain.CHANNELS);
        audio.setVolume(volumeSlider.getValue());
        RadioEngine e = new RadioEngine(source, audio, region, band);
        e.setStereoEnabled(stereoCheck.isSelected());
        e.setListener(this::onStatus);
        e.setErrorListener(error -> Platform.runLater(() -> {
            setStatusText("Stopped: " + describe(error));
            powerButton.setText("Listen");
            signalBar.setProgress(0);
            setPilotLocked(false);
        }));
        try {
            e.start(frequencyHz);
        } catch (Exception ex) {
            setStatusText("Could not start: " + describe(ex));
            return;
        }
        engine = e;
        sink = audio;
        powerButton.setText("Stop");
        setStatusText("Listening.");
    }

    private void seek(Scanner.Direction direction) {
        RadioEngine e = engine;
        if (e == null || !e.isRunning()) {
            setStatusText("Press Listen first.");
            return;
        }
        if (e.isSeeking()) {
            e.cancelSeek();
            setStatusText("Seek cancelled.");
            return;
        }
        e.seek(direction);
        setStatusText("Seeking…");
    }

    private void tuneFromField() {
        String text = frequencyField.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            long hz = Math.round(Double.parseDouble(text.trim()) * MHZ);
            if (!band.contains(hz)) {
                setStatusText("Outside the %s band (%.1f–%.1f MHz)."
                        .formatted(band.name(), band.minHz() / MHZ, band.maxHz() / MHZ));
                return;
            }
            tuneTo(band.snap(hz));
            frequencyField.clear();
            requestFocus();
        } catch (NumberFormatException ex) {
            setStatusText("Not a frequency: " + text);
        }
    }

    private void tuneTo(long hz) {
        frequencyHz = hz;
        refreshFrequency();
        refreshPresetButtons();
        RadioEngine e = engine;
        if (e != null) {
            e.setFrequency(hz);
        }
    }

    private void tuneToSelectedPreset() {
        Preset selected = presetList.getSelectionModel().getSelectedItem();
        if (selected != null) {
            tuneTo(band.snap(selected.frequencyHz()));
        }
    }

    private void saveCurrentAsPreset() {
        String name = nameField.getText() == null ? "" : nameField.getText().strip();
        presets = Presets.withPreset(presets, new Preset(frequencyHz, name));
        config.savePresets(presets);
        nameField.clear();
        refreshPresets();
        setStatusText("Saved %.1f MHz.".formatted(frequencyHz / MHZ));
    }

    private void removeSelectedPreset() {
        Preset selected = presetList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        presets = Presets.withoutFrequency(presets, selected.frequencyHz());
        config.savePresets(presets);
        refreshPresets();
        setStatusText("Removed %.1f MHz.".formatted(selected.frequencyHz() / MHZ));
    }

    private void setRegion(Region value) {
        region = value;
        band = BandPlan.fm(value);
        tuneTo(band.snap(frequencyHz));
        if (engine != null && engine.isRunning()) {
            // De-emphasis and the band plan are fixed when the chain is built; restart to pick them up.
            RadioEngine e = engine;
            engine = null;
            sink = null;
            e.stop();
            start();
        }
    }

    // --- view ----------------------------------------------------------------------------------

    private void refreshFrequency() {
        frequencyLabel.setText("%.1f".formatted(frequencyHz / MHZ));
    }

    private void refreshPresets() {
        presetList.getItems().setAll(presets);
        refreshPresetButtons();
    }

    private void refreshPresetButtons() {
        saveButton.setDisable(Presets.contains(presets, frequencyHz));
        removeButton.setDisable(presetList.getSelectionModel().getSelectedItem() == null);
    }

    /** Called on the receive thread. */
    private void onStatus(RadioEngine.Status status) {
        latestStatus.set(status);
        if (statusPending.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                statusPending.set(false);
                applyStatus(latestStatus.get());
            });
        }
    }

    private void applyStatus(RadioEngine.Status status) {
        if (status == null) {
            return;
        }
        // Seek retunes the engine, so the displayed frequency must follow it rather than the buttons.
        if (status.frequencyHz() > 0 && status.frequencyHz() != frequencyHz) {
            frequencyHz = status.frequencyHz();
            refreshFrequency();
            refreshPresetButtons();
        }

        // Map roughly -60..0 dBFS onto the bar.
        double normalised = Math.clamp((status.signalDbfs() + 60.0) / 60.0, 0.0, 1.0);
        signalBar.setProgress(normalised);
        setPilotLocked(status.running() && status.pilotLocked());
        applyStationInfo(status.running() ? status.station() : StationInfo.NONE);

        if (!status.running()) {
            return;
        }
        if (status.seeking()) {
            setStatusText("Seeking…");
        } else {
            String dropped = status.droppedSamples() > 0 ? "  ·  dropped %d".formatted(status.droppedSamples()) : "";
            setStatusText("%.0f dBFS  ·  %s%s".formatted(status.signalDbfs(), status.rdsDiagnostic(), dropped));
        }
    }

    private void applyStationInfo(StationInfo station) {
        String name = station.programService();
        String type = ProgramType.name(station.programType(), region);
        stationLabel.setText(name.isBlank() ? "" : (type.isBlank() ? name : name + "  ·  " + type));
        radioTextLabel.setText(station.radioText());
    }

    /** Reports what the station is broadcasting, not what we chose to listen to. */
    private void setPilotLocked(boolean locked) {
        stereoLabel.setStyle(
                locked
                        ? "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: -fx-accent;"
                        : "-fx-font-size: 11px; -fx-font-weight: bold; -fx-opacity: 0.25;");
    }

    private String describeOutOfRange() {
        IqSource.Range range = RtlTcpSource.localhost().tunableRange();
        return "%.1f MHz is outside this dongle's tuner range (%.1f–%.0f MHz)."
                .formatted(frequencyHz / MHZ, range.minHz() / MHZ, range.maxHz() / MHZ);
    }

    private void setStatusText(String text) {
        statusLabel.setText(text);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
