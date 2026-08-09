package com.modula.ui;

import javafx.scene.Group;
import javafx.scene.shape.SVGPath;

/**
 * The transport symbols, drawn as shapes rather than typed as characters.
 *
 * <p>◀◀ ▶ ★ (U+25C0, U+25B6, U+2605) are absent from most UI and monospace faces, IBM Plex Mono
 * included. Typed, they render from whatever fallback each platform happens to pick, at whatever
 * size and baseline that face uses — so the transport row looks subtly different on every machine
 * and can shift as fonts change underneath it. As paths they are identical everywhere and take the
 * button's colour from the stylesheet for free.
 */
public final class Glyphs {

    private static final double SIZE = 11;

    private Glyphs() {}

    public static Group tuneUp() {
        return group(triangleRight(0));
    }

    public static Group tuneDown() {
        return group(triangleLeft(0));
    }

    public static Group seekUp() {
        return group(triangleRight(0), triangleRight(SIZE * 0.62));
    }

    public static Group seekDown() {
        return group(triangleLeft(0), triangleLeft(SIZE * 0.62));
    }

    /** A plus, for adding the tuned station to the preset row. */
    public static Group add() {
        SVGPath path = path("M4.5,0 H6.5 V4.5 H11 V6.5 H6.5 V11 H4.5 V6.5 H0 V4.5 H4.5 Z");
        return group(path);
    }

    private static SVGPath triangleRight(double offsetX) {
        double h = SIZE;
        double w = SIZE * 0.55;
        return path("M%f,0 L%f,%f L%f,%f Z".formatted(offsetX, offsetX + w, h / 2, offsetX, h));
    }

    private static SVGPath triangleLeft(double offsetX) {
        double h = SIZE;
        double w = SIZE * 0.55;
        return path("M%f,0 L%f,%f L%f,%f Z".formatted(offsetX + w, offsetX, h / 2, offsetX + w, h));
    }

    private static SVGPath path(String content) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.getStyleClass().add("glyph");
        return path;
    }

    private static Group group(SVGPath... paths) {
        Group group = new Group(paths);
        group.setMouseTransparent(true);
        return group;
    }
}
