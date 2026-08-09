package com.modula.tray;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;

/**
 * Draws the tray icon: a broadcast glyph — a dot with two arcs radiating from it — in the palette's
 * own language. Amber while listening, ink when stopped, coral on a fault.
 *
 * <p><b>A glyph rather than the frequency.</b> A tray icon is about 22 pixels; "101.5" is five
 * characters including a decimal point, and shrinking it to fit produces something unreadable that
 * also lies by rounding. The glyph says what the icon is actually for — whether the radio is on —
 * and the frequency is one hover away in the tooltip, where it fits.
 *
 * <p>Pure Java2D and headless-safe, so both backends consume it.
 */
public final class TrayIconRenderer {

    /** The kit's palette. Amber is still the budget: it means "this is on". */
    private static final Color LISTENING = new Color(0xFFB454);

    private static final Color STOPPED = new Color(0x9AA0AC);
    private static final Color FAULT = new Color(0xFF6B5A);

    private TrayIconRenderer() {}

    public static BufferedImage render(TrayDisplay display, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(colorFor(display));

        double centre = size / 2.0;
        double dot = Math.max(2.0, size * 0.15);
        g.fill(new Ellipse2D.Double(centre - dot / 2, centre - dot / 2, dot, dot));

        // Two arcs either side, so the mark reads as radiating rather than as a target.
        g.setStroke(
                new BasicStroke((float) Math.max(1.2, size * 0.085), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int ring = 1; ring <= 2; ring++) {
            double r = size * (0.16 + 0.15 * ring);
            for (int side : new int[] {0, 180}) {
                g.draw(new Arc2D.Double(centre - r, centre - r, r * 2, r * 2, side - 42, 84, Arc2D.OPEN));
            }
        }
        g.dispose();
        return image;
    }

    private static Color colorFor(TrayDisplay display) {
        if (display.attention()) {
            return FAULT;
        }
        return display.listening() ? LISTENING : STOPPED;
    }
}
