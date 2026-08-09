package com.modula.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.modula.AppInfo;
import com.modula.config.ConfigStore;
import com.modula.source.RtlSdrNativeSource;
import com.modula.update.ReleaseInfo;

/**
 * What this is, which version, and where its files live.
 *
 * <p>Also the one place that answers "why is it not hearing anything" without a log: it names the
 * dongle it found, or says plainly that it found none — the two most common support questions, and
 * both invisible from anywhere else in the interface.
 */
public final class AboutWindow {

    private static Stage stage;

    private AboutWindow() {}

    /** Shows the dialog, reusing the window if it is already open. */
    public static void show(Window owner, ConfigStore config, ReleaseInfo update) {
        if (stage != null && stage.isShowing()) {
            stage.toFront();
            return;
        }
        VBox body = new VBox(3);
        body.setPadding(new Insets(22, 26, 18, 26));
        body.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(AppInfo.NAME);
        name.getStyleClass().add("about-name");
        Label version = new Label(AppInfo.VERSION + (AppInfo.isSnapshot() ? "  ·  development build" : ""));
        version.getStyleClass().add("about-version");
        Label description = new Label(AppInfo.DESCRIPTION);
        description.getStyleClass().add("about-line");

        body.getChildren().addAll(name, version, description, gap(12));
        body.getChildren().add(row("Hardware", describeHardware()));
        body.getChildren().add(row("Settings", config.directory().toString()));
        body.getChildren()
                .add(row("Presets", config.directory().resolve("presets.txt").toString()));
        if (!AppInfo.BUILD_TIME.isBlank()) {
            body.getChildren().add(row("Built", AppInfo.BUILD_TIME));
        }
        if (update != null) {
            Label available = new Label("Update available: " + update.version());
            available.getStyleClass().add("about-update");
            body.getChildren().addAll(gap(8), available);
        }

        Button close = new Button("Close");
        close.setOnAction(e -> stage.close());
        HBox buttons = new HBox(close);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(16, 0, 0, 0));
        body.getChildren().add(buttons);

        stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.NONE);
        stage.setTitle("About " + AppInfo.NAME);
        stage.setResizable(false);
        Scene scene = new Scene(body);
        Windows.styleLike(owner, scene);
        stage.setScene(scene);
        stage.show();
    }

    /** The most common support question, answered without a log file. */
    private static String describeHardware() {
        if (RtlSdrNativeSource.isAvailable()) {
            String device = RtlSdrNativeSource.describeDevice();
            return device.isBlank() ? "dongle found" : device;
        }
        String reason = RtlSdrNativeSource.unavailableReason();
        return reason.isBlank() ? "none" : reason + " — using rtl_tcp";
    }

    private static HBox row(String key, String value) {
        Label k = new Label(key);
        k.getStyleClass().add("about-key");
        k.setMinWidth(74);
        Label v = new Label(value);
        v.getStyleClass().add("about-line");
        HBox box = new HBox(8, k, v);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Region gap(double height) {
        Region r = new Region();
        r.setMinHeight(height);
        return r;
    }
}
