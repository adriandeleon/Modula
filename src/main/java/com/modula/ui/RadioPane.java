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
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.modula.audio.JavaSoundSink;
import com.modula.band.BandPlan;
import com.modula.config.ConfigStore;
import com.modula.config.Preset;
import com.modula.config.Presets;
import com.modula.config.Settings;
import com.modula.radio.DemodChain;
import com.modula.radio.RadioEngine;
import com.modula.radio.Scanner;
import com.modula.source.IqSource;
import com.modula.source.RtlSdrNativeSource;
import com.modula.source.RtlTcpSource;

/**
 * The shell: three bands — {@link GlassPane} reports, the transport acts, the footer holds what is
 * set once a session — with the status line pinned to the bottom edge.
 *
 * <p>The previous layout was nine centred rows, so the eye had no column to follow and the frequency
 * competed with eight siblings. Everything the receiver reports now lives on one inset panel and
 * everything that acts sits below it.
 *
 * <p>Still deliberately not an SDR panel: no demodulator picker, no bandwidth slider, no gain or AGC
 * controls, no FFT settings, no squelch. Gain belongs to the dongle's AGC and the bandwidth is fixed
 * by {@link DemodChain}.
 */
public final class RadioPane extends BorderPane {

    private static final double MHZ = 1_000_000.0;

    /** Style class selecting the daylight token block; the sheet carries both grounds. */
    private static final String DAYLIGHT = "daylight";

    private final GlassPane glass;
    private final PresetBar presetBar;
    private final Button powerButton = new Button("Listen");
    private final Button seekDown = transportButton(Glyphs.seekDown(), "Seek down to the next station");
    private final Button tuneDown = transportButton(Glyphs.tuneDown(), "Down one channel");
    private final Button tuneUp = transportButton(Glyphs.tuneUp(), "Up one channel");
    private final Button seekUp = transportButton(Glyphs.seekUp(), "Seek up to the next station");
    private final TextField tuneEntry = new TextField();
    private final ComboBox<com.modula.band.Region> regionCombo = new ComboBox<>();
    private final Slider volumeSlider = new Slider(0, 1, 0.7);
    private final CheckBox stereoCheck = new CheckBox("Stereo");
    private final Label statusLine = new Label();

    // Status arrives on the receive thread; coalesce to at most one FX repaint pending at a time.
    private final AtomicReference<RadioEngine.Status> latestStatus = new AtomicReference<>();
    private final AtomicBoolean statusPending = new AtomicBoolean();

    private final ConfigStore config;
    private List<Preset> presets;

    private com.modula.band.Region region;
    private BandPlan band;
    private long frequencyHz;
    private boolean faulted;

    private RadioEngine engine;
    private JavaSoundSink sink;

    public RadioPane(ConfigStore config) {
        this.config = config;

        Settings settings = config.loadSettings();
        this.region = settings.region();
        this.band = BandPlan.fm(region);
        this.frequencyHz = band.snap(settings.frequencyHz());
        this.presets = new ArrayList<>(config.loadPresets());

        this.glass = new GlassPane(band, region);
        this.presetBar = new PresetBar(this::tuneTo, this::savePreset, this::removePreset, this::renamePreset);

        VBox body = new VBox(glass, buildTransport(), presetBar);
        body.setPadding(new Insets(18, 18, 10, 18));
        // The glass takes the slack: it is the surface worth more room, and the transport and preset
        // row stay put underneath rather than drifting down a growing window.
        VBox.setVgrow(glass, Priority.ALWAYS);
        VBox.setVgrow(presetBar, Priority.NEVER);

        setCenter(body);
        setBottom(new VBox(buildFooter(), statusLine));

        statusLine.getStyleClass().add("status-line");
        statusLine.setMaxWidth(Double.MAX_VALUE);

        volumeSlider.setValue(settings.volume());
        stereoCheck.setSelected(settings.stereo());
        regionCombo.setValue(region);

        glass.setFrequency(frequencyHz);
        presetBar.setPresets(presets, frequencyHz);
        installKeyboard();
        applyTheme();
        setStatusText(describeAvailableSource(), false);
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

    private HBox buildTransport() {
        seekDown.setOnAction(e -> seek(Scanner.Direction.DOWN));
        tuneDown.setOnAction(e -> tuneTo(band.previous(frequencyHz)));
        tuneUp.setOnAction(e -> tuneTo(band.next(frequencyHz)));
        seekUp.setOnAction(e -> seek(Scanner.Direction.UP));

        powerButton.getStyleClass().add("power-button");
        powerButton.setOnAction(e -> togglePower());

        // Type-to-tune replaces an MHz label, a text field and a Tune button — three controls for a
        // gesture that is really just "type a number", and a field that sat focused eating arrow keys.
        tuneEntry.getStyleClass().add("tune-entry");
        tuneEntry.setPrefColumnCount(5);
        tuneEntry.setVisible(false);
        tuneEntry.setManaged(false);
        tuneEntry.setOnAction(e -> commitTuneEntry());
        tuneEntry.focusedProperty().addListener((o, was, has) -> {
            if (!has) {
                hideTuneEntry();
            }
        });
        tuneEntry.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                hideTuneEntry();
                e.consume();
            }
        });

        Region leftGap = new Region();
        Region rightGap = new Region();
        HBox.setHgrow(leftGap, Priority.ALWAYS);
        HBox.setHgrow(rightGap, Priority.ALWAYS);

        HBox box = new HBox(7, seekDown, tuneDown, leftGap, powerButton, tuneEntry, rightGap, tuneUp, seekUp);
        box.getStyleClass().add("transport");
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** Set once per session, so it does not sit at eye level. */
    private HBox buildFooter() {
        regionCombo.getItems().setAll(com.modula.band.Region.values());
        regionCombo.valueProperty().addListener((o, old, value) -> setRegion(value));

        volumeSlider.getStyleClass().add("volume-slider");
        volumeSlider.setPrefWidth(120);
        volumeSlider.valueProperty().addListener((o, old, value) -> {
            JavaSoundSink s = sink;
            if (s != null) {
                s.setVolume(value.doubleValue());
            }
        });

        // Forcing mono is a real radio's button: stereo raises the noise floor by roughly 20 dB, so a
        // weak station is often more listenable without it.
        stereoCheck.selectedProperty().addListener((o, old, on) -> {
            RadioEngine e = engine;
            if (e != null) {
                e.setStereoEnabled(on);
            }
        });

        Label volumeLabel = new Label("VOL");
        volumeLabel.getStyleClass().add("footer-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(9, volumeLabel, volumeSlider, spacer, regionCombo, stereoCheck);
        box.getStyleClass().add("footer");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Button transportButton(javafx.scene.Node glyph, String tooltip) {
        Button button = new Button();
        button.setGraphic(glyph);
        button.getStyleClass().add("tune-button");
        button.setTooltip(new javafx.scene.control.Tooltip(tooltip));
        return button;
    }

    /**
     * Switches ground. Night is the default; daylight inverts it and keeps every other rule.
     *
     * <p>Deliberately has no control in the footer — the budget goes on listening, not on preferences
     * — but it is a real code path rather than a dead token block, and it is what a system-appearance
     * listener would call.
     */
    public void setDaylight(boolean daylight) {
        if (daylight == getStyleClass().contains(DAYLIGHT)) {
            return;
        }
        if (daylight) {
            getStyleClass().add(DAYLIGHT);
        } else {
            getStyleClass().remove(DAYLIGHT);
        }
        applyTheme();
    }

    /**
     * Hands the strip its colours, because a Canvas cannot read a stylesheet.
     *
     * <p>This is the one place the palette leaves the sheet, so it is the one place that has to be
     * updated alongside it.
     */
    private void applyTheme() {
        boolean daylight = getStyleClass().contains(DAYLIGHT);
        glass.spectrumStrip()
                .setPalette(
                        Color.web(daylight ? "#97907F" : "#5C6371"),
                        Color.web(daylight ? "#5F5A50" : "#9AA0AC"),
                        Color.web(daylight ? "#B4560A" : "#FFB454"));
    }

    // --- keyboard ------------------------------------------------------------------------------

    private void installKeyboard() {
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getTarget() instanceof TextField) {
                return; // the inline tune entry or a preset rename owns the keys
            }
            switch (e.getCode()) {
                case LEFT -> step(e, Scanner.Direction.DOWN, band.previous(frequencyHz));
                case RIGHT -> step(e, Scanner.Direction.UP, band.next(frequencyHz));
                case UP -> nudgeVolume(e, +0.05);
                case DOWN -> nudgeVolume(e, -0.05);
                case SPACE -> {
                    togglePower();
                    e.consume();
                }
                default -> {
                    if (!handleDigit(e)) {
                        return;
                    }
                    e.consume();
                }
            }
        });
    }

    private void step(KeyEvent e, Scanner.Direction direction, long next) {
        if (e.isShiftDown()) {
            seek(direction);
        } else {
            tuneTo(next);
        }
        e.consume();
    }

    private void nudgeVolume(KeyEvent e, double delta) {
        volumeSlider.setValue(Math.clamp(volumeSlider.getValue() + delta, 0.0, 1.0));
        e.consume();
    }

    /** A digit recalls a preset; typing a number opens the inline entry, which is the same gesture. */
    private boolean handleDigit(KeyEvent e) {
        String typed = e.getText();
        if (typed == null || typed.length() != 1 || !Character.isDigit(typed.charAt(0))) {
            return false;
        }
        if (e.isShiftDown() || e.isControlDown() || e.isAltDown()) {
            return false;
        }
        int digit = typed.charAt(0) - '0';
        if (digit >= 1 && presetBar.recall(digit)) {
            return true;
        }
        showTuneEntry(typed);
        return true;
    }

    private void showTuneEntry(String seed) {
        tuneEntry.setText(seed);
        tuneEntry.setVisible(true);
        tuneEntry.setManaged(true);
        powerButton.setVisible(false);
        powerButton.setManaged(false);
        tuneEntry.requestFocus();
        tuneEntry.positionCaret(tuneEntry.getText().length());
    }

    private void hideTuneEntry() {
        tuneEntry.setVisible(false);
        tuneEntry.setManaged(false);
        powerButton.setVisible(true);
        powerButton.setManaged(true);
        requestFocus();
    }

    private void commitTuneEntry() {
        String text = tuneEntry.getText();
        hideTuneEntry();
        if (text == null || text.isBlank()) {
            return;
        }
        try {
            long hz = Math.round(Double.parseDouble(text.trim()) * MHZ);
            if (!band.contains(hz)) {
                setStatusText(
                        "%.1f is outside the %s band (%.1f–%.1f MHz)"
                                .formatted(hz / MHZ, band.name(), band.minHz() / MHZ, band.maxHz() / MHZ),
                        true);
                return;
            }
            tuneTo(band.snap(hz));
        } catch (NumberFormatException ex) {
            setStatusText("Not a frequency: " + text, true);
        }
    }

    // --- actions -------------------------------------------------------------------------------

    private void togglePower() {
        if (engine != null && engine.isRunning()) {
            dispose();
            powerButton.setText("Listen");
            powerButton.getStyleClass().remove("running");
            faulted = false;
            glass.apply(ReceiverState.NOT_LISTENING, null, frequencyHz);
            setStatusText("Stopped.", false);
            return;
        }
        start();
    }

    /**
     * Prefers the dongle directly, falling back to {@code rtl_tcp} — which is not a consolation
     * prize: it is how the dongle lives on another machine and how development works with nothing
     * attached.
     */
    private IqSource createSource() {
        if (RtlSdrNativeSource.isAvailable()) {
            return new RtlSdrNativeSource(0, DemodChain.BLOCK_PAIRS * DemodChain.CHANNELS);
        }
        return RtlTcpSource.localhost();
    }

    private void start() {
        IqSource source = createSource();
        if (!source.tunableRange().contains(frequencyHz)) {
            IqSource.Range range = source.tunableRange();
            setStatusText(
                    "%.1f MHz is outside the tuner's range (%.1f–%.0f MHz)"
                            .formatted(frequencyHz / MHZ, range.minHz() / MHZ, range.maxHz() / MHZ),
                    true);
            return;
        }
        JavaSoundSink audio = new JavaSoundSink(DemodChain.AUDIO_RATE, DemodChain.CHANNELS);
        audio.setVolume(volumeSlider.getValue());
        RadioEngine e = new RadioEngine(source, audio, region, band);
        e.setStereoEnabled(stereoCheck.isSelected());
        e.setListener(this::onStatus);
        e.setErrorListener(error -> Platform.runLater(() -> {
            faulted = true;
            powerButton.setText("Listen");
            powerButton.getStyleClass().remove("running");
            glass.apply(ReceiverState.FAULT, latestStatus.get(), frequencyHz);
            setStatusText("Stopped: " + describe(error), true);
        }));
        try {
            e.start(frequencyHz);
        } catch (Exception ex) {
            faulted = true;
            setStatusText("Could not start: " + describe(ex), true);
            return;
        }
        engine = e;
        sink = audio;
        faulted = false;
        powerButton.setText("Stop");
        if (!powerButton.getStyleClass().contains("running")) {
            powerButton.getStyleClass().add("running");
        }
        setStatusText(source instanceof RtlSdrNativeSource ? "Listening — dongle." : "Listening — rtl_tcp.", false);
    }

    private void seek(Scanner.Direction direction) {
        RadioEngine e = engine;
        if (e == null || !e.isRunning()) {
            setStatusText("Press Listen first.", false);
            return;
        }
        if (e.isSeeking()) {
            e.cancelSeek();
            setSeekLabels(false);
            setStatusText("Seek cancelled.", false);
            return;
        }
        e.seek(direction);
        setSeekLabels(true);
    }

    /** The seek buttons relabel while a sweep runs, because pressing them again cancels it. */
    private void setSeekLabels(boolean seeking) {
        seekDown.setTooltip(new javafx.scene.control.Tooltip(seeking ? "Cancel the seek" : "Seek down"));
        seekUp.setTooltip(new javafx.scene.control.Tooltip(seeking ? "Cancel the seek" : "Seek up"));
    }

    private void tuneTo(long hz) {
        frequencyHz = hz;
        glass.setFrequency(hz);
        presetBar.setTuned(hz);
        RadioEngine e = engine;
        if (e != null) {
            e.setFrequency(hz);
        }
    }

    /** Stores the frequency immediately, then offers the name — not the other way round. */
    private void savePreset() {
        if (Presets.contains(presets, frequencyHz)) {
            return;
        }
        Preset saved = Preset.of(frequencyHz);
        presets = Presets.withPreset(presets, saved);
        config.savePresets(presets);
        presetBar.setPresets(presets, frequencyHz);
        presetBar.promptNameFor(saved);
    }

    private void removePreset(Preset preset) {
        presets = Presets.withoutFrequency(presets, preset.frequencyHz());
        config.savePresets(presets);
        presetBar.setPresets(presets, frequencyHz);
    }

    private void renamePreset(Preset preset, String name) {
        presets = Presets.withPreset(presets, new Preset(preset.frequencyHz(), name));
        config.savePresets(presets);
        presetBar.setPresets(presets, frequencyHz);
    }

    private void setRegion(com.modula.band.Region value) {
        region = value;
        band = BandPlan.fm(value);
        glass.setBand(band, region);
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

    // --- status --------------------------------------------------------------------------------

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
        // Seek retunes the engine, so the display follows it rather than the buttons.
        if (status.frequencyHz() > 0 && status.frequencyHz() != frequencyHz) {
            frequencyHz = status.frequencyHz();
            presetBar.setTuned(frequencyHz);
        }

        ReceiverState state = ReceiverState.of(status, faulted);
        glass.apply(state, status, frequencyHz);

        if (!status.seeking()) {
            setSeekLabels(false);
        }
        if (!status.running()) {
            return;
        }
        if (state == ReceiverState.SEEKING) {
            setStatusText("Seeking…", false);
        } else if (state == ReceiverState.FAULT) {
            setStatusText(
                    "Dropped %d samples — the audio device is not keeping up".formatted(status.droppedSamples()), true);
        } else {
            setStatusText("%s  ·  %s".formatted(Readouts.dbfs(status.signalDbfs()), status.rdsDiagnostic()), false);
        }
    }

    private String describeAvailableSource() {
        if (RtlSdrNativeSource.isAvailable()) {
            String name = RtlSdrNativeSource.describeDevice();
            return name.isBlank() ? "Dongle found. Press Listen." : "Found %s. Press Listen.".formatted(name);
        }
        return "%s — start `rtl_tcp -a 127.0.0.1`, then press Listen."
                .formatted(RtlSdrNativeSource.unavailableReason());
    }

    private void setStatusText(String text, boolean fault) {
        statusLine.setText(text);
        if (fault && !statusLine.getStyleClass().contains("fault")) {
            statusLine.getStyleClass().add("fault");
        } else if (!fault) {
            statusLine.getStyleClass().remove("fault");
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }
}
