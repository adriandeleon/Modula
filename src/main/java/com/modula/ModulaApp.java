package com.modula;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.modula.config.ConfigStore;
import com.modula.ui.RadioPane;

/** Entry point. */
public final class ModulaApp extends Application {

    private RadioPane pane;

    @Override
    public void start(Stage stage) {
        pane = new RadioPane(ConfigStore.userDefault());
        stage.setScene(new Scene(pane));
        stage.setTitle("Modula");
        stage.setOnCloseRequest(e -> pane.dispose());
        stage.show();
        pane.requestFocus(); // so the arrow keys tune straight away
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
