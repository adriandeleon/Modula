package com.modula.ui;

import javafx.scene.Scene;
import javafx.stage.Window;

/** Shared window plumbing, so every secondary window looks like the one that opened it. */
public final class Windows {

    private Windows() {}

    /**
     * Copies the owner's stylesheets and theme class onto a new scene.
     *
     * <p>A dialog is its own scene, so it inherits nothing — without this every secondary window
     * would come up in the toolkit's default look while the main one is in Night Dial, which reads as
     * a different application rather than as the same one.
     */
    public static void styleLike(Window owner, Scene scene) {
        if (owner == null || owner.getScene() == null) {
            return;
        }
        Scene source = owner.getScene();
        scene.getStylesheets().setAll(source.getStylesheets());
        if (source.getRoot() != null && source.getRoot().getStyleClass().contains("daylight")) {
            scene.getRoot().getStyleClass().add("daylight");
        }
    }
}
