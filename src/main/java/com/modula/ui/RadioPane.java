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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.modula.audio.JavaSoundSink;
import com.modula.audio.RecordingSink;
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
public final class RadioPane extends StackPane {

    private static final double MHZ = 1_000_000.0;

    /** Style class selecting the daylight token block; the sheet carries both grounds. */
    private static final String DAYLIGHT = "daylight";

    private final BorderPane shell = new BorderPane();
    private final CommandPalette palette = new CommandPalette(this::commands);
    private final GlassPane glass;
    private final PresetBar presetBar;
    private final Button powerButton = new Button("Listen");
    private final Button seekDown = transportButton(Glyphs.seekDown(), "Seek down to the next station");
    private final Button tuneDown = transportButton(Glyphs.tuneDown(), "Down one channel");
    private final Button tuneUp = transportButton(Glyphs.tuneUp(), "Up one channel");
    private final Button seekUp = transportButton(Glyphs.seekUp(), "Seek up to the next station");
    private final TextField tuneEntry = new TextField();
    private final ComboBox<com.modula.band.Region> regionCombo = new ComboBox<>();
    private final ComboBox<String> bandCombo = new ComboBox<>();
    private final Slider volumeSlider = new Slider(0, 1, 0.7);
    private final CheckBox stereoCheck = new CheckBox("Stereo");
    private final Label statusLine = new Label();

    // Status arrives on the receive thread; coalesce to at most one FX repaint pending at a time.
    private final AtomicReference<RadioEngine.Status> latestStatus = new AtomicReference<>();
    private final AtomicBoolean statusPending = new AtomicBoolean();

    private final ConfigStore config;
    private List<Preset> presets;

    private java.util.function.BiConsumer<com.modula.tray.TrayDisplay, String> traySink;
    private com.modula.update.ReleaseInfo update;
    private com.modula.band.Region region;
    private BandPlan band;
    private long frequencyHz;
    private boolean faulted;

    private RadioEngine engine;
    private JavaSoundSink sink;
    private RecordingSink recorder;
    private Settings settings;

    public RadioPane(ConfigStore config) {
        this.config = config;

        this.settings = config.loadSettings();
        this.region = settings.region();
        this.band = bandNamed(settings.band());
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

        shell.setCenter(body);
        shell.setBottom(new VBox(buildFooter(), statusLine));
        getChildren().addAll(shell, palette);
        palette.setOnHidden(this::requestFocus);

        statusLine.getStyleClass().add("status-line");
        statusLine.setMaxWidth(Double.MAX_VALUE);

        volumeSlider.setValue(settings.volume());
        stereoCheck.setSelected(settings.stereo());
        regionCombo.setValue(region);
        setDaylight(settings.daylight());

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
        stopRecording();
        config.saveSettings(currentSettings());
    }

    /** The live settings, so every save carries the whole record rather than four of its fields. */
    private Settings currentSettings() {
        return new Settings(
                frequencyHz,
                region,
                band.name(),
                volumeSlider.getValue(),
                stereoCheck.isSelected(),
                getStyleClass().contains(DAYLIGHT),
                settings.tray(),
                settings.closeToTray(),
                settings.updateCheck(),
                settings.lastUpdateCheck(),
                settings.recordingDirectory());
    }

    private static BandPlan bandNamed(String name) {
        return switch (name) {
            case "AM" -> BandPlan.mediumWave(com.modula.band.Region.AMERICAS);
            case "AIR" -> BandPlan.airband();
            default -> BandPlan.fm(com.modula.band.Region.AMERICAS);
        };
    }

    // --- commands -------------------------------------------------------------------------------

    /**
     * Everything the radio can do, for the palette.
     *
     * <p>Rebuilt per invocation rather than cached, because half of these have a label that depends
     * on state — Listen becomes Stop, Record becomes Stop recording — and a cached list would show
     * the wrong verb.
     */
    private List<Command> commands() {
        boolean running = engine != null && engine.isRunning();
        List<Command> list = new ArrayList<>();
        list.add(Command.of("listen", running ? "Stop" : "Listen", "Space", this::togglePower));
        list.add(Command.of("seek.up", "Seek up", "shift+\u2192", () -> seek(Scanner.Direction.UP)));
        list.add(Command.of("seek.down", "Seek down", "shift+\u2190", () -> seek(Scanner.Direction.DOWN)));
        list.add(Command.of("tune.up", "Next channel", "\u2192", () -> tuneTo(band.next(frequencyHz))));
        list.add(Command.of("tune.down", "Previous channel", "\u2190", () -> tuneTo(band.previous(frequencyHz))));
        list.add(Command.of("preset.save", "Save this station", "+", this::savePreset));
        list.add(
                Command.of("record", isRecording() ? "Stop recording" : "Record to a file", "", this::toggleRecording));
        for (String name : new String[] {"FM", "AM", "AIR"}) {
            list.add(Command.of("band." + name.toLowerCase(java.util.Locale.ROOT), "Band: " + name, "", () -> {
                bandCombo.setValue(name);
            }));
        }
        list.add(Command.of(
                "theme",
                "Toggle daylight",
                "",
                () -> setDaylight(!getStyleClass().contains(DAYLIGHT))));
        list.add(Command.of("settings", "Settings\u2026", "", this::showSettings));
        list.add(Command.of("about", "About Modula", "", this::showAbout));
        return list;
    }

    /** Receives what the tray should show. Set once the tray exists; null until then. */
    public void setTraySink(java.util.function.BiConsumer<com.modula.tray.TrayDisplay, String> sink) {
        this.traySink = sink;
        publishTray();
    }

    private void publishTray() {
        var sink = traySink;
        if (sink == null) {
            return;
        }
        boolean running = engine != null && engine.isRunning();
        String frequency = Readouts.megahertz(frequencyHz);
        com.modula.tray.TrayDisplay display = faulted
                ? com.modula.tray.TrayDisplay.fault(frequency)
                : (running ? com.modula.tray.TrayDisplay.listening(frequency) : com.modula.tray.TrayDisplay.stopped());
        RadioEngine.Status status = latestStatus.get();
        String station = status == null ? "" : status.station().programService();
        String tooltip = running
                ? "Modula \u00b7 %s MHz%s".formatted(frequency, station.isBlank() ? "" : " \u00b7 " + station)
                : "Modula \u00b7 not listening";
        sink.accept(display, tooltip);
    }

    public void showPalette() {
        palette.show();
    }

    private void showSettings() {
        SettingsWindow.show(getScene() == null ? null : getScene().getWindow(), currentSettings(), applied -> {
            settings = applied;
            setDaylight(applied.daylight());
            config.saveSettings(applied);
        });
    }

    private void showAbout() {
        AboutWindow.show(getScene() == null ? null : getScene().getWindow(), config, update);
    }

    /** Set by the app once an update check has finished; null until then and when up to date. */
    public void setAvailableUpdate(com.modula.update.ReleaseInfo release) {
        this.update = release;
        if (release != null) {
            setStatusText("Modula %s is available.".formatted(release.version()), false);
        }
    }

    // --- recording ------------------------------------------------------------------------------

    public boolean isRecording() {
        RecordingSink r = recorder;
        return r != null && r.isRecording();
    }

    /** Starts or stops recording what is playing. Needs the receiver running, since it tees the audio. */
    public void toggleRecording() {
        RecordingSink r = recorder;
        if (r == null) {
            setStatusText("Press Listen before recording.", false);
            return;
        }
        if (r.isRecording()) {
            java.nio.file.Path file = r.stop();
            setStatusText("Recorded " + file.getFileName(), false);
            return;
        }
        try {
            java.nio.file.Path file = r.start(settings.resolveRecordingDirectory(), recordingLabel());
            setStatusText("Recording to " + file.getFileName(), false);
        } catch (java.io.IOException e) {
            setStatusText("Could not record: " + describe(e), true);
        }
    }

    private void stopRecording() {
        RecordingSink r = recorder;
        if (r != null) {
            r.stop();
        }
    }

    /** Names the file after the station when RDS has told us one, else after the frequency. */
    private String recordingLabel() {
        RadioEngine.Status status = latestStatus.get();
        String name = status == null ? "" : status.station().programService();
        return name.isBlank() ? Readouts.megahertz(frequencyHz) + "MHz" : name;
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

        bandCombo.getItems().setAll("FM", "AM", "AIR");
        bandCombo.setValue(band.name());
        bandCombo.valueProperty().addListener((o, old, value) -> setBand(value));

        HBox box = new HBox(9, volumeLabel, volumeSlider, spacer, bandCombo, regionCombo, stereoCheck);
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
            if (e.getCode() == KeyCode.P && e.isControlDown() && e.isShiftDown()) {
                palette.show();
                e.consume();
                return;
            }
            if (palette.isShowing() || e.getTarget() instanceof TextField) {
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

    public void togglePower() {
        if (engine != null && engine.isRunning()) {
            dispose();
            powerButton.setText("Listen");
            powerButton.getStyleClass().remove("running");
            faulted = false;
            glass.apply(ReceiverState.NOT_LISTENING, null, frequencyHz);
            setStatusText("Stopped.", false);
            publishTray();
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
        // Only refuse what nothing can reach. A frequency *below* the tuner may still be reachable by
        // direct sampling, which the engine arranges after the device is open — so refusing here on
        // the closed source's range would rule out medium wave on hardware that can do it.
        IqSource.Range range = source.tunableRange();
        if (frequencyHz > range.maxHz()) {
            setStatusText(
                    "%.1f MHz is above the tuner's range (up to %.0f MHz)"
                            .formatted(frequencyHz / MHZ, range.maxHz() / MHZ),
                    true);
            return;
        }
        JavaSoundSink audio = new JavaSoundSink(DemodChain.AUDIO_RATE, DemodChain.CHANNELS);
        audio.setVolume(volumeSlider.getValue());
        RecordingSink recording = new RecordingSink(audio, DemodChain.AUDIO_RATE, DemodChain.CHANNELS);
        RadioEngine e = new RadioEngine(source, recording, region, band);
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
        recorder = recording;
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

    /**
     * Switches band, which switches modulation with it.
     *
     * <p>The chain is built for one modulation and keeps it for its lifetime — the filters and the
     * demodulator are different objects, not a runtime branch — so this restarts the engine exactly
     * as a region change does.
     */
    private void setBand(String name) {
        BandPlan chosen =
                switch (name) {
                    case "AM" -> BandPlan.mediumWave(region);
                    case "AIR" -> BandPlan.airband();
                    default -> BandPlan.fm(region);
                };
        applyBand(chosen);
        // Stereo is meaningless outside FM, so the control says so rather than sitting there inert.
        stereoCheck.setDisable(!chosen.modulation().carriesStereo());
        if (chosen.modulation() == com.modula.band.Modulation.AM && chosen.minHz() < 24_000_000L) {
            setStatusText(
                    "Medium wave needs a direct-sampling dongle (an RTL-SDR Blog V3); AIR is AM in tuner range.",
                    false);
        }
    }

    private void applyBand(BandPlan chosen) {
        band = chosen;
        glass.setBand(band, region);
        tuneTo(band.snap(frequencyHz));
        restartIfRunning();
    }

    private void restartIfRunning() {
        if (engine != null && engine.isRunning()) {
            RadioEngine e = engine;
            engine = null;
            sink = null;
            e.stop();
            start();
        }
    }

    private void setRegion(com.modula.band.Region value) {
        region = value;
        band = band.modulation() == com.modula.band.Modulation.FM
                ? BandPlan.fm(value)
                : (band.name().equals("AM") ? BandPlan.mediumWave(value) : band);
        glass.setBand(band, region);
        tuneTo(band.snap(frequencyHz));
        // De-emphasis and the band plan are fixed when the chain is built; restart to pick them up.
        restartIfRunning();
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
        publishTray();

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
