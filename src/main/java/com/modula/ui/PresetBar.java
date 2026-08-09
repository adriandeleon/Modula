package com.modula.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

import com.modula.config.Preset;

/**
 * The saved stations, as a row of chips: one tap tunes, the tuned one reads as selected, and the
 * trailing + saves.
 *
 * <p>Replaces a list view with a name field, a Save button and a Remove button — four controls and
 * 150 px of height for something a car radio does with one row. Three decisions carry that:
 *
 * <ul>
 *   <li><b>Selected means tuned.</b> The chip matching the current frequency is outlined, which is
 *       also what makes a Save button redundant.
 *   <li><b>Naming happens after saving.</b> + stores the frequency immediately and offers the name
 *       inline; a name field standing empty beside a Save button asks a question before you have
 *       decided to keep the station.
 *   <li><b>Remove lives on the chip</b>, via its menu or Delete. A dedicated Remove button that is
 *       disabled most of the time is a control earning its keep five percent of the time.
 * </ul>
 *
 * <p>The row scrolls horizontally and never wraps, so the window height is fixed however many
 * presets exist.
 */
public final class PresetBar extends ScrollPane {

    private final HBox chips = new HBox(6);
    private final Button addButton = new Button();
    private final Label empty = new Label("No stations saved — tune one in and press +");

    private final LongConsumer onRecall;
    private final Runnable onSave;
    private final Consumer<Preset> onRemove;
    private final java.util.function.BiConsumer<Preset, String> onRename;

    private List<Preset> presets = List.of();
    private long tunedHz;

    public PresetBar(
            LongConsumer onRecall,
            Runnable onSave,
            Consumer<Preset> onRemove,
            java.util.function.BiConsumer<Preset, String> onRename) {
        this.onRecall = onRecall;
        this.onSave = onSave;
        this.onRemove = onRemove;
        this.onRename = onRename;

        getStyleClass().add("preset-bar");
        setFitToHeight(true);
        setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setVbarPolicy(ScrollBarPolicy.NEVER);
        setContent(chips);
        setMinHeight(38);
        setPrefHeight(38);

        chips.setAlignment(Pos.CENTER_LEFT);
        empty.getStyleClass().add("preset-empty");

        addButton.setGraphic(Glyphs.add());
        addButton.getStyleClass().add("preset-add");
        addButton.setTooltip(new javafx.scene.control.Tooltip("Save the tuned station"));
        addButton.setOnAction(e -> onSave.run());
    }

    /** Rebuilds the row. Cheap enough to call on every change; a preset bank is a handful of items. */
    public void setPresets(List<Preset> presets, long tunedHz) {
        this.presets = new ArrayList<>(presets);
        this.tunedHz = tunedHz;
        rebuild();
    }

    /** Marks which chip is tuned without rebuilding, for the common case of stepping the band. */
    public void setTuned(long tunedHz) {
        if (this.tunedHz == tunedHz) {
            return;
        }
        this.tunedHz = tunedHz;
        rebuild();
    }

    /** Recalls preset {@code n} counting from one, for the number keys. Ignored when absent. */
    public boolean recall(int oneBased) {
        if (oneBased < 1 || oneBased > presets.size()) {
            return false;
        }
        onRecall.accept(presets.get(oneBased - 1).frequencyHz());
        return true;
    }

    private void rebuild() {
        chips.getChildren().clear();
        if (presets.isEmpty()) {
            chips.getChildren().add(empty);
        }
        for (Preset preset : presets) {
            chips.getChildren().add(chipFor(preset));
        }
        chips.getChildren().add(addButton);
    }

    private StackPane chipFor(Preset preset) {
        Button chip = new Button(preset.label());
        chip.getStyleClass().add("preset-chip");
        if (preset.frequencyHz() == tunedHz) {
            chip.getStyleClass().add("tuned");
        }
        chip.setOnAction(e -> onRecall.accept(preset.frequencyHz()));
        chip.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DELETE) {
                onRemove.accept(preset);
                e.consume();
            }
        });

        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(e -> beginRename(preset));
        MenuItem remove = new MenuItem("Remove");
        remove.setOnAction(e -> onRemove.accept(preset));
        chip.setContextMenu(new ContextMenu(rename, remove));

        return new StackPane(chip);
    }

    /** Naming happens in place, after the station is already saved. */
    private void beginRename(Preset preset) {
        TextField field = new TextField(preset.name());
        field.getStyleClass().add("preset-chip");
        field.setPrefColumnCount(10);
        field.setOnAction(e -> {
            onRename.accept(preset, field.getText());
            rebuild();
        });
        field.focusedProperty().addListener((o, was, has) -> {
            if (!has) {
                rebuild();
            }
        });

        int index = presets.indexOf(preset);
        if (index >= 0 && index < chips.getChildren().size()) {
            chips.getChildren().set(index, new StackPane(field));
            field.requestFocus();
            field.selectAll();
        }
    }

    /** Offers the name inline immediately after a save, which is when the question is worth asking. */
    public void promptNameFor(Preset preset) {
        setTuned(preset.frequencyHz());
        beginRename(preset);
    }
}
