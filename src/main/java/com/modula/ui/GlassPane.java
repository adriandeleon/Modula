package com.modula.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

import com.modula.band.BandPlan;
import com.modula.radio.RadioEngine;
import com.modula.rds.ProgramType;
import com.modula.rds.StationInfo;

/**
 * The inset panel carrying everything the receiver knows, in a fixed reading order: where you are,
 * who it is, what they are playing, how well it is coming in.
 *
 * <p><b>Nothing here is interactive.</b> That separation is the whole layout idea — the glass
 * reports, the body below it acts — and it is what lets the frequency be the brightest thing on
 * screen without competing with a row of sibling controls.
 *
 * <p>Two things never move. The badges are always present and merely unlit, and two lines are
 * reserved for radio text whether or not a station sends any. A station that sends none leaves a
 * gap, which is the right trade against controls that jump every time a song changes.
 */
public final class GlassPane extends VBox {

    private static final double MHZ = 1_000_000.0;

    /** The eight-character station name, held at width while it fills in. */
    private static final int PS_WIDTH = 8;

    private final Label bandLabel = new Label();
    private final Label stereoBadge = badge("STEREO", "stereo");
    private final Label trafficBadge = badge("TP", "traffic-programme");
    private final Label announcementBadge = badge("TA", "traffic-announcement");

    private final Label dial = new Label("—");
    private final Label stationLabel = new Label();
    private final Label radioTextLabel = new Label();

    private final SpectrumStrip spectrum = new SpectrumStrip(452, 46);
    private final Label scaleLow = new Label();
    private final Label scaleHigh = new Label();
    private final Region meterFill = new Region();
    private final Label readout = new Label();

    private BandPlan band;
    private com.modula.band.Region region;
    private ReceiverState state = ReceiverState.NOT_LISTENING;

    public GlassPane(BandPlan band, com.modula.band.Region region) {
        this.band = band;
        this.region = region;

        getStyleClass().addAll("glass", state.styleClass());
        setSpacing(2);

        getChildren().addAll(buildHeader(), buildDial(), stationLabel, radioTextLabel, spectrum, buildScale());

        stationLabel.getStyleClass().add("station-name");
        radioTextLabel.getStyleClass().add("radio-text");
        radioTextLabel.setWrapText(true);
        radioTextLabel.setTextAlignment(TextAlignment.LEFT);
        // Two lines, always — so arriving text never pushes the controls down.
        radioTextLabel.setMinHeight(30);
        radioTextLabel.setPrefHeight(30);
        stationLabel.setMinHeight(19);

        // The strip takes whatever vertical slack the glass has; it answers the layout queries
        // itself rather than being bound to its own parent's size.
        VBox.setVgrow(spectrum, Priority.ALWAYS);

        VBox.setMargin(spectrum, new javafx.geometry.Insets(9, 0, 7, 0));
        refreshBandLabels();
    }

    // --- layout --------------------------------------------------------------------------------

    private HBox buildHeader() {
        Label title = new Label("MODULA");
        title.getStyleClass().add("glass-title");
        bandLabel.getStyleClass().add("band-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox box = new HBox(6, title, spacer, bandLabel, stereoBadge, trafficBadge, announcementBadge);
        box.getStyleClass().add("glass-header");
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox buildDial() {
        dial.getStyleClass().add("dial");
        Label unit = new Label("MHz");
        unit.getStyleClass().add("dial-unit");

        HBox box = new HBox(0, dial, unit);
        box.setAlignment(Pos.BOTTOM_LEFT);
        return box;
    }

    /** Band position and signal: the analogue answer to "where am I", plus how well it is arriving. */
    private HBox buildScale() {
        scaleLow.getStyleClass().add("scale-label");
        scaleHigh.getStyleClass().add("scale-label");
        readout.getStyleClass().add("readout");

        meterFill.getStyleClass().add("meter-fill");
        StackPane meterTrack = new StackPane(meterFill);
        meterTrack.getStyleClass().add("meter-track");
        meterTrack.setAlignment(Pos.CENTER_LEFT);
        meterTrack.setMinHeight(4);
        meterTrack.setPrefHeight(4);
        HBox.setHgrow(meterTrack, Priority.ALWAYS);
        meterFill.setMaxWidth(Region.USE_PREF_SIZE);
        meterFill.setPrefWidth(0);

        HBox box = new HBox(8, scaleLow, meterTrack, scaleHigh, readout);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private static Label badge(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", styleClass);
        return label;
    }

    // --- updates -------------------------------------------------------------------------------

    public void setBand(BandPlan band, com.modula.band.Region region) {
        this.band = band;
        this.region = region;
        spectrum.setBand(band);
        refreshBandLabels();
    }

    private void refreshBandLabels() {
        bandLabel.setText("%s %s".formatted(band.name(), region.name()));
        scaleLow.setText(Readouts.megahertz(band.minHz()));
        scaleHigh.setText(Readouts.megahertz(band.maxHz()));
    }

    /** The frequency, shown whether or not the receiver is running. */
    public void setFrequency(long hz) {
        dial.setText(Readouts.megahertz(hz));
    }

    /** Applies one snapshot. The state drives the styling; this method only supplies values. */
    public void apply(ReceiverState newState, RadioEngine.Status status, long frequencyHz) {
        if (newState != state) {
            getStyleClass().remove(state.styleClass());
            state = newState;
            getStyleClass().add(state.styleClass());
        }
        setFrequency(frequencyHz);

        boolean pilot = status != null && status.pilotLocked();
        StationInfo station = status == null ? StationInfo.NONE : status.station();

        // STEREO reports the transmitter, never the listener's forced-mono setting.
        setLit(stereoBadge, pilot && newState.isReceiving());
        setLit(trafficBadge, station.trafficProgram());
        setLit(announcementBadge, station.trafficAnnouncement());

        applyStation(newState, station);
        applyMeter(newState, status);

        if (status != null && status.spectrum() != null) {
            spectrum.update(status.spectrum(), frequencyHz, newState == ReceiverState.SEEKING);
        } else if (!newState.isReceiving()) {
            spectrum.clear();
        }
    }

    private void applyStation(ReceiverState newState, StationInfo station) {
        if (!newState.showsStation()) {
            // A seek makes the previous station's identity stale the moment it starts.
            stationLabel.setText("");
            radioTextLabel.setText("");
            return;
        }
        String name = station.programService();
        String type = ProgramType.name(station.programType(), region);
        if (name.isBlank()) {
            // Hold the width with middots so the name grows in place rather than jumping.
            stationLabel.setText("·".repeat(PS_WIDTH));
            stationLabel.getStyleClass().add("station-pending");
        } else {
            stationLabel.getStyleClass().remove("station-pending");
            stationLabel.setText(type.isBlank() ? name : name + "  ·  " + type);
        }
        radioTextLabel.setText(station.radioText());
    }

    private void applyMeter(ReceiverState newState, RadioEngine.Status status) {
        if (status == null || !newState.isReceiving()) {
            meterFill.setPrefWidth(0);
            readout.setText("—");
            meterFill.getStyleClass().remove("locked");
            return;
        }
        double normalised = Math.clamp((status.signalDbfs() + 60.0) / 60.0, 0.0, 1.0);
        double track = Math.max(meterFill.getParent().getLayoutBounds().getWidth(), 1);
        meterFill.setPrefWidth(track * normalised);
        readout.setText(Readouts.dbfs(status.signalDbfs()));

        // The last third of the meter resolves toward lock teal only while the pilot holds, so
        // strength and stereo — two facts a listener conflates anyway — ride one mark.
        boolean locked = status.pilotLocked();
        if (locked && !meterFill.getStyleClass().contains("locked")) {
            meterFill.getStyleClass().add("locked");
        } else if (!locked) {
            meterFill.getStyleClass().remove("locked");
        }
    }

    private static void setLit(Label badge, boolean lit) {
        if (lit && !badge.getStyleClass().contains("lit")) {
            badge.getStyleClass().add("lit");
        } else if (!lit) {
            badge.getStyleClass().remove("lit");
        }
    }

    /** Lets the shell hand the strip its theme colours. */
    public SpectrumStrip spectrumStrip() {
        return spectrum;
    }
}
