package com.modula.ui;

import javafx.application.HostServices;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
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
        show(owner, config, update, null);
    }

    /** @param links opens the home page in a browser; null degrades the link to plain text */
    public static void show(Window owner, ConfigStore config, ReleaseInfo update, HostServices links) {
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

        // The mark beside the name, because About is where the application introduces itself and a
        // wall of text does not. Degrades to no image rather than a broken node if the resource is
        // missing — see AppIcons.
        VBox titles = new VBox(3, name, version, description);
        titles.setAlignment(Pos.CENTER_LEFT);
        javafx.scene.image.Image mark = AppIcons.at(64);
        if (mark == null) {
            body.getChildren().addAll(titles, gap(12));
        } else {
            javafx.scene.image.ImageView view = new javafx.scene.image.ImageView(mark);
            view.setFitWidth(58);
            view.setFitHeight(58);
            view.setSmooth(true);
            HBox header = new HBox(16, view, titles);
            header.setAlignment(Pos.CENTER_LEFT);
            body.getChildren().addAll(header, gap(12));
        }
        // Identity first — who made it, under what terms, and where it lives — then whatever this
        // particular machine happens to have.
        body.getChildren().add(row("Author", AppInfo.AUTHOR));
        body.getChildren().add(row("Licence", AppInfo.LICENSE));
        body.getChildren().add(homeRow(links));
        body.getChildren().add(gap(8));

        body.getChildren().add(row("Hardware", describeHardware()));
        String hint = hardwareHint();
        if (!hint.isBlank()) {
            Label advice = new Label(hint);
            advice.getStyleClass().add("about-fine");
            advice.setWrapText(true);
            advice.setMaxWidth(330);
            VBox.setMargin(advice, new Insets(1, 0, 4, 74));
            body.getChildren().add(advice);
        }
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

        body.getChildren().addAll(gap(10), dependencies());
        Label copyright = new Label(AppInfo.COPYRIGHT + "  \u00b7  " + AppInfo.LICENSE + " licence");
        copyright.getStyleClass().add("about-fine");
        body.getChildren().addAll(gap(10), copyright);

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
        return RtlSdrNativeSource.diagnose().summary() + " — using rtl_tcp";
    }

    /** The fix, when there is one. About has the room the status line does not. */
    private static String hardwareHint() {
        return RtlSdrNativeSource.isAvailable()
                ? ""
                : RtlSdrNativeSource.diagnose().hint();
    }

    /**
     * The home page, clickable when there is something to click with.
     *
     * <p>Falls back to a plain label rather than a dead hyperlink: a link that looks like a link and
     * does nothing is worse than text you can read and type.
     */
    private static HBox homeRow(HostServices links) {
        if (links == null || AppInfo.HOMEPAGE.isBlank()) {
            return row("Home", AppInfo.HOMEPAGE.isBlank() ? "not set for this build" : AppInfo.HOMEPAGE);
        }
        Label key = new Label("Home");
        key.getStyleClass().add("about-key");
        key.setMinWidth(74);
        Hyperlink link = new Hyperlink(AppInfo.HOMEPAGE);
        link.getStyleClass().add("about-link");
        link.setOnAction(e -> links.showDocument(AppInfo.HOMEPAGE));
        HBox box = new HBox(8, key, link);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** What Modula is built on. The list is checked against the pom by DependenciesTest. */
    private static VBox dependencies() {
        VBox box = new VBox(2);
        Label heading = new Label("BUILT WITH");
        heading.getStyleClass().add("about-heading");
        box.getChildren().add(heading);
        for (AppInfo.Dependency d : AppInfo.dependencies()) {
            String name = d.version().isBlank() ? d.name() : d.name() + " " + d.version();
            String right = d.note().isBlank() ? d.license() : d.license() + "  \u00b7  " + d.note();
            Label left = new Label(name);
            left.getStyleClass().add("about-line");
            left.setMinWidth(150);
            Label detail = new Label(right);
            detail.getStyleClass().add("about-fine");
            HBox line = new HBox(8, left, detail);
            line.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(line);
        }
        return box;
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
