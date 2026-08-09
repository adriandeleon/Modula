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

    /**
     * Sliders, for settings.
     *
     * <p>Sliders rather than a gear: this is a radio, the panel it opens is a row of controls, and a
     * gear is the icon for a machine's innards. It is also three strokes and a dot at 11 pixels,
     * which a gear's teeth are not.
     */
    public static Group settings() {
        double w = SIZE;
        double[] rows = {1.5, 5.5, 9.5};
        double[] knobs = {0.68, 0.34, 0.58};
        SVGPath[] parts = new SVGPath[rows.length * 2];
        for (int i = 0; i < rows.length; i++) {
            double y = rows[i];
            parts[i * 2] = path("M0,%f H%f V%f H0 Z".formatted(y - 0.6, w, y + 0.6));
            double cx = w * knobs[i];
            double r = 1.7;
            // Two half-arcs: SVG has no circle command and an arc cannot span a full turn.
            parts[i * 2 + 1] = path("M%f,%f A%f,%f 0 1 1 %f,%f A%f,%f 0 1 1 %f,%f Z"
                    .formatted(cx - r, y, r, r, cx + r, y, r, r, cx - r, y));
        }
        return group(parts);
    }

    /** A plus, for adding the tuned station to the preset row. */
    public static Group add() {
        SVGPath path = path("M4.5,0 H6.5 V4.5 H11 V6.5 H6.5 V11 H4.5 V6.5 H0 V4.5 H4.5 Z");
        return group(path);
    }

    /**
     * A filled disc, for record.
     *
     * <p>Drawn rather than typed for the same reason as the transport arrows: U+23FA is absent from
     * most mono faces, and a fallback glyph would be a different size and weight from its neighbours.
     */
    public static Group record() {
        double r = SIZE / 2;
        // Two half-arcs, because SVG has no circle command and an arc cannot span 360 degrees.
        return group(path("M0,%f A%f,%f 0 1 1 %f,%f A%f,%f 0 1 1 0,%f Z".formatted(r, r, r, SIZE, r, r, r, r)));
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
