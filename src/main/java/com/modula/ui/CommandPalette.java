package com.modula.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Type-to-run over everything the radio can do.
 *
 * <p><b>An in-scene overlay, not a popup window.</b> A separate stage does not reliably take keyboard
 * focus on Windows, which leaves focus orphaned between two scenes and the keyboard dead — the exact
 * failure Editora hit and solved the same way. This is a node over a dimmed backdrop in the window
 * that is already focused.
 *
 * <p>It is also the discoverability surface for the keyboard: every command shows its shortcut beside
 * it, so the palette teaches the accelerators rather than replacing them.
 */
public final class CommandPalette extends StackPane {

    private final TextField input = new TextField();
    private final ListView<Command> results = new ListView<>();
    private final Supplier<List<Command>> commands;

    private Runnable onHidden = () -> {};

    public CommandPalette(Supplier<List<Command>> commands) {
        this.commands = commands;

        getStyleClass().add("palette-backdrop");
        setAlignment(Pos.TOP_CENTER);
        setVisible(false);
        setManaged(false);

        input.getStyleClass().add("palette-input");
        input.setPromptText("Type a command…");

        results.getStyleClass().add("palette-results");
        results.setPrefHeight(230);
        results.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Command command, boolean empty) {
                super.updateItem(command, empty);
                if (empty || command == null) {
                    setGraphic(null);
                    return;
                }
                Label title = new Label(command.title());
                title.getStyleClass().add("palette-title");
                Label detail = new Label(command.detail());
                detail.getStyleClass().add("palette-detail");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox row = new HBox(8, title, spacer, detail);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(0, 6, 0, 0)); // clear of the scrollbar
                setGraphic(row);
            }
        });

        VBox card = new VBox(6, input, results);
        card.getStyleClass().add("palette-card");
        card.setMaxWidth(420);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setMargin(card, new Insets(64, 0, 0, 0));
        getChildren().add(card);

        input.textProperty().addListener((o, was, now) -> refresh(now));
        addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        setOnMouseClicked(e -> {
            if (e.getTarget() == this) {
                hide();
            }
        });
    }

    /** Runs after the palette closes, so the caller can put focus back where it was. */
    public void setOnHidden(Runnable onHidden) {
        this.onHidden = onHidden == null ? () -> {} : onHidden;
    }

    public void show() {
        input.clear();
        refresh("");
        setVisible(true);
        setManaged(true);
        toFront();
        input.requestFocus();
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
        onHidden.run();
    }

    public boolean isShowing() {
        return isVisible();
    }

    private void refresh(String query) {
        List<Command> matching = new ArrayList<>();
        for (Command command : commands.get()) {
            if (command.matches(query)) {
                matching.add(command);
            }
        }
        results.getItems().setAll(matching);
        if (!matching.isEmpty()) {
            results.getSelectionModel().select(0);
        }
    }

    /**
     * The palette owns every key while it is open.
     *
     * <p>Arrows move the selection rather than reaching the tuner underneath — which is why this is a
     * filter on the overlay rather than a handler on the field.
     */
    private void onKey(KeyEvent e) {
        switch (e.getCode()) {
            case ESCAPE -> {
                hide();
                e.consume();
            }
            case ENTER -> {
                Command selected = results.getSelectionModel().getSelectedItem();
                hide();
                if (selected != null) {
                    selected.action().run();
                }
                e.consume();
            }
            case DOWN -> {
                move(1);
                e.consume();
            }
            case UP -> {
                move(-1);
                e.consume();
            }
            default -> {}
        }
    }

    private void move(int delta) {
        int size = results.getItems().size();
        if (size == 0) {
            return;
        }
        int next = Math.floorMod(results.getSelectionModel().getSelectedIndex() + delta, size);
        results.getSelectionModel().select(next);
        results.scrollTo(next);
    }
}
